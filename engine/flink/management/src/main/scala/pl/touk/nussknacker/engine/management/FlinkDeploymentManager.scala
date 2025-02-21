package pl.touk.nussknacker.engine.management

import cats.data.OptionT
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax.EncoderOps
import pl.touk.nussknacker.engine.ModelData._
import pl.touk.nussknacker.engine.api.ProcessVersion
import pl.touk.nussknacker.engine.api.deployment._
import pl.touk.nussknacker.engine.api.deployment.inconsistency.InconsistentStateDetector
import pl.touk.nussknacker.engine.api.deployment.simple.SimpleStateStatus
import pl.touk.nussknacker.engine.api.process.{ProcessIdWithName, ProcessName}
import pl.touk.nussknacker.engine.api.test.ScenarioTestData
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.deployment.{DeploymentData, ExternalDeploymentId, User}
import pl.touk.nussknacker.engine.management.FlinkDeploymentManager.prepareProgramArgs
import pl.touk.nussknacker.engine.testmode.TestProcess.TestResults
import pl.touk.nussknacker.engine.{BaseModelData, ModelData}

import scala.concurrent.{ExecutionContext, Future}

abstract class FlinkDeploymentManager(modelData: BaseModelData, shouldVerifyBeforeDeploy: Boolean, mainClassName: String)
                                     (implicit ec: ExecutionContext, deploymentService: ProcessingTypeDeploymentService)
  extends DeploymentManager with PostprocessingProcessStatus with AlwaysFreshProcessState with LazyLogging {

  private lazy val testRunner = new FlinkProcessTestRunner(modelData.asInvokableModelData)

  private lazy val verification = new FlinkProcessVerifier(modelData.asInvokableModelData)

  /**
    * Gets status from engine, handles finished state, resolves possible inconsistency with lastAction and formats status using `ProcessStateDefinitionManager`
    */
  override def getProcessState(idWithName: ProcessIdWithName, lastStateAction: Option[ProcessAction])(implicit freshnessPolicy: DataFreshnessPolicy): Future[WithDataFreshnessStatus[ProcessState]] = {
    for {
      statusesWithFreshness <- getProcessStates(idWithName.name)
      _ = logger.debug(s"Statuses for ${idWithName.name.value}: $statusesWithFreshness")
      actionAfterPostprocessOpt <- postprocess(idWithName, statusesWithFreshness.value)
      engineStateResolvedWithLastAction = InconsistentStateDetector.resolve(statusesWithFreshness.value, actionAfterPostprocessOpt.orElse(lastStateAction))
    } yield statusesWithFreshness.copy(value = processStateDefinitionManager.processState(engineStateResolvedWithLastAction))
  }

  //There is small problem here: if no one invokes process status for long time, Flink can remove process from history
  // - then it 's gone, not finished.
  //TODO: it should be checked periodically instead of checking on each getProcessState invocation
  // (consider moving marking finished deployments to InconsistentStateDetector as one "detectAndResolveAndFixStatus")
  override def postprocess(idWithName: ProcessIdWithName, statusDetailsList: List[StatusDetails]): Future[Option[ProcessAction]] = {
    val allDeploymentIdsAsCorrectActionIds = Option(statusDetailsList.map(details => details.deploymentId.flatMap(_.toActionIdOpt).map(id => (id, details.status))))
      .filter(_.forall(_.isDefined))
      .map(_.flatten)
    allDeploymentIdsAsCorrectActionIds
      .map(markEachFinishedDeploymentAsExecutionFinishedAndReturnLastStateAction(idWithName, _))
      .getOrElse {
        legacyMarkProcessFinished(idWithName.name, statusDetailsList)
      }
  }

  // TODO: This method is for backward compatibility. Remove it after switching all Flink jobs into mandatory deploymentId in StatusDetails
  private def legacyMarkProcessFinished(name: ProcessName, statusDetailsList: List[StatusDetails]) = {
    statusDetailsList.headOption
      .filter(_.status == SimpleStateStatus.Finished)
      .map { _ =>
        logger.debug(s"Flink job doesn't contain deploymentId for process: $name. Will be used legacy method of marking process as finished by adding cancel action")
        deploymentService.markProcessFinishedIfLastActionDeploy(name)
      }
      .sequence
      .map(_.flatten)
  }

  private def markEachFinishedDeploymentAsExecutionFinishedAndReturnLastStateAction(idWithName: ProcessIdWithName,
                                                                                    deploymentActionStatuses: List[(ProcessActionId, StateStatus)]): Future[Option[ProcessAction]] = {
    val finishedDeploymentActionsIds = deploymentActionStatuses.collect {
      case (id, SimpleStateStatus.Finished) => id
    }
    Future.sequence(finishedDeploymentActionsIds.map(deploymentService.markActionExecutionFinished)).flatMap { markingResult =>
      Option(markingResult).filter(_.contains(true)).map { _ =>
        deploymentService.getLastStateAction(idWithName.id)
      }.getOrElse(Future.successful(None))
    }
  }

  override def validate(processVersion: ProcessVersion, deploymentData: DeploymentData, canonicalProcess: CanonicalProcess): Future[Unit] = {
    for {
      oldJob <- oldJobToStop(processVersion)
      _ <- checkRequiredSlotsExceedAvailableSlots(canonicalProcess, oldJob.flatMap(_.externalDeploymentId))
    } yield ()
  }

  override def deploy(processVersion: ProcessVersion, deploymentData: DeploymentData, canonicalProcess: CanonicalProcess, savepointPath: Option[String]): Future[Option[ExternalDeploymentId]] = {
    val processName = processVersion.processName

    val stoppingResult = for {
      oldJob <- OptionT(oldJobToStop(processVersion))
      deploymentId <- OptionT.fromOption[Future](oldJob.externalDeploymentId)
      maybeSavePoint <- OptionT.liftF(stopSavingSavepoint(processVersion, deploymentId, canonicalProcess))
    } yield {
      logger.info(s"Deploying $processName. Saving savepoint finished")
      maybeSavePoint
    }

    for {
      maybeSavepoint <- stoppingResult.value
      // In case of redeploy we double check required slots which is not bad because can be some run between jobs and it is better to check it again
      _ <- checkRequiredSlotsExceedAvailableSlots(canonicalProcess, None)
      runResult <- runProgram(
        processName,
        mainClassName,
        prepareProgramArgs(modelData.inputConfigDuringExecution.serialized, processVersion, deploymentData, canonicalProcess),
        savepointPath.orElse(maybeSavepoint)
      )
      _ <- runResult.map(waitForDuringDeployFinished(processName, _)).getOrElse(Future.successful(()))
    } yield runResult
  }

  protected def waitForDuringDeployFinished(processName: ProcessName, deploymentId: ExternalDeploymentId): Future[Unit]

  private def oldJobToStop(processVersion: ProcessVersion): Future[Option[StatusDetails]] = {
    getFreshProcessStates(processVersion.processName)
      // TODO: handle stopping of more than one jobs before deploy
      .map(InconsistentStateDetector.extractAtMostOneStatus)
      .map(_.filter(details => SimpleStateStatus.DefaultFollowingDeployStatuses.contains(details.status)))
  }

  protected def checkRequiredSlotsExceedAvailableSlots(canonicalProcess: CanonicalProcess, currentlyDeployedJobId: Option[ExternalDeploymentId]): Future[Unit]

  override def savepoint(processName: ProcessName, savepointDir: Option[String]): Future[SavepointResult] = {
    // TODO: savepoint for given deployment id
    requireSingleRunningJob(processName) {
      makeSavepoint(_, savepointDir)
    }
  }

  override def stop(processName: ProcessName, savepointDir: Option[String], user: User): Future[SavepointResult] = {
    // TODO: savepoint for given deployment id
    requireSingleRunningJob(processName) {
      stop(_, savepointDir)
    }
  }

  override def test[T](processName: ProcessName, canonicalProcess: CanonicalProcess, scenarioTestData: ScenarioTestData, variableEncoder: Any => T): Future[TestResults[T]] = {
    testRunner.test(canonicalProcess, scenarioTestData, variableEncoder)
  }

  override def customActions: List[CustomAction] = List.empty

  override def invokeCustomAction(actionRequest: CustomActionRequest, canonicalProcess: CanonicalProcess): Future[Either[CustomActionError, CustomActionResult]] =
    Future.successful(Left(CustomActionNotImplemented(actionRequest)))

  private def requireSingleRunningJob[T](processName: ProcessName)(action: ExternalDeploymentId => Future[T]): Future[T] = {
    val name = processName.value
    getFreshProcessStates(processName).flatMap { statuses =>
      val runningDeploymentIds = statuses.collect {
        case StatusDetails(SimpleStateStatus.Running, _, Some(deploymentId), _, _, _, _) => deploymentId
      }
      runningDeploymentIds match {
        case Nil =>
          Future.failed(new IllegalStateException(s"Job $name not found"))
        case single :: Nil =>
          action(single)
        case moreThanOne =>
          Future.failed(new IllegalStateException(s"Multiple running jobs: ${moreThanOne.mkString(", ")}"))
      }
    }
  }

  private def checkIfJobIsCompatible(savepointPath: String, canonicalProcess: CanonicalProcess, processVersion: ProcessVersion): Future[Unit] =
    if (shouldVerifyBeforeDeploy)
      verification.verify(processVersion, canonicalProcess, savepointPath)
    else Future.successful(())

  private def stopSavingSavepoint(processVersion: ProcessVersion, deploymentId: ExternalDeploymentId, canonicalProcess: CanonicalProcess): Future[String] = {
    logger.debug(s"Making savepoint of  ${processVersion.processName}. Deployment: $deploymentId")
    for {
      savepointResult <- makeSavepoint(deploymentId, savepointDir = None)
      savepointPath = savepointResult.path
      _ <- checkIfJobIsCompatible(savepointPath, canonicalProcess, processVersion)
      _ <- cancel(deploymentId)
    } yield savepointPath
  }

  protected def cancel(deploymentId: ExternalDeploymentId): Future[Unit]

  protected def makeSavepoint(deploymentId: ExternalDeploymentId, savepointDir: Option[String]): Future[SavepointResult]

  protected def stop(deploymentId: ExternalDeploymentId, savepointDir: Option[String]): Future[SavepointResult]

  protected def runProgram(processName: ProcessName, mainClass: String, args: List[String], savepointPath: Option[String]): Future[Option[ExternalDeploymentId]]

  override def processStateDefinitionManager: ProcessStateDefinitionManager = FlinkProcessStateDefinitionManager
}

object FlinkDeploymentManager {

  def prepareProgramArgs(serializedConfig: String, processVersion: ProcessVersion, deploymentData: DeploymentData, canonicalProcess: CanonicalProcess) : List[String] =
    List(canonicalProcess.asJson.spaces2, processVersion.asJson.spaces2, deploymentData.asJson.spaces2, serializedConfig)

}
