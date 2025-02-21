package pl.touk.nussknacker.engine.embedded

import akka.actor.ActorSystem
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.ModelData.BaseModelDataExt
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.deployment.simple.SimpleStateStatus.ProblemStateStatus
import pl.touk.nussknacker.engine.api.process.ProcessName
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.deployment.{DeploymentData, DeploymentId, ExternalDeploymentId, User}
import pl.touk.nussknacker.engine.embedded.requestresponse.RequestResponseDeploymentStrategy
import pl.touk.nussknacker.engine.embedded.streaming.StreamingDeploymentStrategy
import pl.touk.nussknacker.engine.lite.api.runtimecontext.LiteEngineRuntimeContextPreparer
import pl.touk.nussknacker.engine.lite.metrics.dropwizard.{DropwizardMetricsProviderFactory, LiteMetricRegistryFactory}
import pl.touk.nussknacker.engine.{BaseModelData, CustomProcessValidator, ModelData}
import pl.touk.nussknacker.lite.manager.{LiteDeploymentManager, LiteDeploymentManagerProvider}
import sttp.client3.SttpBackend

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class EmbeddedDeploymentManagerProvider extends LiteDeploymentManagerProvider {

  override def createDeploymentManager(modelData: BaseModelData, engineConfig: Config)
                                      (implicit ec: ExecutionContext, actorSystem: ActorSystem,
                                       sttpBackend: SttpBackend[Future, Any],
                                       deploymentService: ProcessingTypeDeploymentService): DeploymentManager = {
    val strategy = forMode(engineConfig)(
      new StreamingDeploymentStrategy,
      RequestResponseDeploymentStrategy(engineConfig)
    )

    val metricRegistry = LiteMetricRegistryFactory.usingHostnameAsDefaultInstanceId.prepareRegistry(engineConfig)
    val contextPreparer = new LiteEngineRuntimeContextPreparer(new DropwizardMetricsProviderFactory(metricRegistry))

    strategy.open(modelData.asInvokableModelData, contextPreparer)
    new EmbeddedDeploymentManager(modelData.asInvokableModelData, deploymentService, strategy)
  }

  override protected def defaultRequestResponseSlug(scenarioName: ProcessName, config: Config): String =
    RequestResponseDeploymentStrategy.defaultSlug(scenarioName)

  override def additionalValidators(config: Config): List[CustomProcessValidator] = forMode(config)(
    Nil,
    List(EmbeddedRequestResponseScenarioValidator)
  )

  override def name: String = "lite-embedded"

}


/*
  FIXME: better synchronization - comment below isn't true anymore + make HA ready
  Currently we assume that all operations that modify state (i.e. deploy and cancel) are performed from
  ManagementActor, which provides synchronization. Hence, we ignore all synchronization issues, except for
  checking status, but for this @volatile on interpreters should suffice.
 */
class EmbeddedDeploymentManager(override protected val modelData: ModelData,
                                processingTypeDeploymentService: ProcessingTypeDeploymentService,
                                deploymentStrategy: DeploymentStrategy)(implicit ec: ExecutionContext) extends LiteDeploymentManager
  with LazyLogging with DeploymentManagerInconsistentStateHandlerMixIn {

  private val retrieveDeployedScenariosTimeout = 10.seconds
  @volatile private var deployments: Map[ProcessName, ScenarioDeploymentData] = {
    val deployedScenarios = Await.result(processingTypeDeploymentService.getDeployedScenarios, retrieveDeployedScenariosTimeout)
    deployedScenarios.map(data => deployScenario(data.processVersion, data.deploymentData, data.resolvedScenario, throwInterpreterRunExceptionsImmediately = false)).toMap
  }

  override def validate(processVersion: ProcessVersion, deploymentData: DeploymentData, canonicalProcess: CanonicalProcess): Future[Unit] = Future.successful(())

  override def deploy(processVersion: ProcessVersion, deploymentData: DeploymentData, canonicalProcess: CanonicalProcess, savepointPath: Option[String]): Future[Option[ExternalDeploymentId]] = {
    Future.successful(deployScenarioClosingOldIfNeeded(processVersion, deploymentData, canonicalProcess, throwInterpreterRunExceptionsImmediately = true))
  }

  private def deployScenarioClosingOldIfNeeded(processVersion: ProcessVersion,
                                               deploymentData: DeploymentData,
                                               parsedResolvedScenario: CanonicalProcess,
                                               throwInterpreterRunExceptionsImmediately: Boolean): Option[ExternalDeploymentId] = {
    deployments.get(processVersion.processName).collect { case ScenarioDeploymentData(_, processVersion, Success(oldVersion)) =>
      oldVersion.close()
      logger.debug(s"Closed already deployed scenario: $processVersion")
    }
    val deploymentEntry: (ProcessName, ScenarioDeploymentData) = deployScenario(processVersion, deploymentData, parsedResolvedScenario, throwInterpreterRunExceptionsImmediately)
    deployments += deploymentEntry
    Some(ExternalDeploymentId(deploymentEntry._2.deploymentId.value))
  }

  private def deployScenario(processVersion: ProcessVersion,
                             deploymentData: DeploymentData,
                             parsedResolvedScenario: CanonicalProcess,
                             throwInterpreterRunExceptionsImmediately: Boolean) = {

    val interpreterTry = runInterpreter(processVersion, parsedResolvedScenario)
    interpreterTry match {
      case Failure(ex) if throwInterpreterRunExceptionsImmediately =>
        throw ex
      case Failure(ex) =>
        logger.error("Exception during deploy scenario. Scenario will be in Failed state", ex)
      case Success(_) =>
        logger.debug(s"Deployed scenario $processVersion")
    }
    processVersion.processName -> ScenarioDeploymentData(deploymentData.deploymentId, processVersion, interpreterTry)
  }

  private def runInterpreter(processVersion: ProcessVersion, parsedResolvedScenario: CanonicalProcess) = {
    val jobData = JobData(parsedResolvedScenario.metaData, processVersion)
    deploymentStrategy.onScenarioAdded(jobData, parsedResolvedScenario)
  }

  override def cancel(name: ProcessName, user: User): Future[Unit] = {
    deployments.get(name) match {
      case None => Future.failed(new IllegalArgumentException(s"Cannot find scenario $name"))
      case Some(ScenarioDeploymentData(_, _, interpreterTry)) => Future.successful {
        deployments -= name
        interpreterTry.foreach { interpreter =>
          interpreter.close()
          logger.debug(s"Scenario $name stopped")
        }
      }
    }
  }

  override protected def getFreshProcessStates(name: ProcessName): Future[List[StatusDetails]] = {
    Future.successful(
      deployments.get(name).map { interpreterData =>
        StatusDetails(
          status = interpreterData.scenarioDeployment.fold(ex => ProblemStateStatus(s"Scenario compilation errors"), _.status()),
          deploymentId = Some(interpreterData.deploymentId),
          externalDeploymentId = Some(ExternalDeploymentId(interpreterData.deploymentId.value)),
          version = Some(interpreterData.processVersion))
      }.toList
    )
  }

  override def processStateDefinitionManager: ProcessStateDefinitionManager = EmbeddedProcessStateDefinitionManager

  override def close(): Unit = {
    deployments.values.foreach(_.scenarioDeployment.foreach(_.close()))
    deploymentStrategy.close()
    logger.info("All embedded scenarios successfully closed")
  }

  override protected def executionContext: ExecutionContext = ec

  private case class ScenarioDeploymentData(deploymentId: DeploymentId,
                                            processVersion: ProcessVersion,
                                            scenarioDeployment: Try[Deployment])
}

