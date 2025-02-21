package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpResponse, ResponseEntity, StatusCodes}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.SecurityDirectives
import com.typesafe.config.{Config, ConfigRenderOptions}
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.syntax._
import net.ceedubs.ficus.Ficus._
import pl.touk.nussknacker.engine.ModelData
import pl.touk.nussknacker.engine.api.deployment.simple.SimpleStateStatus.ProblemStateStatus
import pl.touk.nussknacker.engine.api.deployment.{DataFreshnessPolicy, ProcessState}
import pl.touk.nussknacker.engine.util.Implicits.RichScalaMap
import pl.touk.nussknacker.engine.version.BuildInfo
import pl.touk.nussknacker.restmodel.displayedgraph.{DisplayableProcess, ValidatedDisplayableProcess}
import pl.touk.nussknacker.restmodel.processdetails.BaseProcessDetails
import pl.touk.nussknacker.ui.process.deployment.DeploymentService
import pl.touk.nussknacker.ui.process.processingtypedata.{ProcessingTypeDataProvider, ProcessingTypeDataReload}
import pl.touk.nussknacker.ui.process.repository.FetchingProcessRepository
import pl.touk.nussknacker.ui.process.repository.FetchingProcessRepository.FetchProcessesDetailsQuery
import pl.touk.nussknacker.ui.process.ProcessCategoryService
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.validation.ProcessValidation

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class AppResources(config: Config,
                   processingTypeDataReload: ProcessingTypeDataReload,
                   modelData: ProcessingTypeDataProvider[ModelData, _],
                   processRepository: FetchingProcessRepository[Future],
                   processValidation: ProcessValidation,
                   deploymentService: DeploymentService,
                   exposeConfig: Boolean,
                   processCategoryService: ProcessCategoryService
                  )(implicit ec: ExecutionContext)
  extends Directives with FailFastCirceSupport with LazyLogging with RouteWithUser with RouteWithoutUser with SecurityDirectives {

  //We use duplicated pathPrefix("app") code - look at comment in NussknackerApp where routes are created
  def publicRoute(): Route = pathPrefix("app") {
    path("healthCheck") {
      get {
        complete {
          createHealthCheckHttpResponse(OK)
        }
      }
    } ~ path("buildInfo") {
      get {
        complete {
          val configuredBuildInfo = config.getAs[Map[String, String]]("globalBuildInfo").getOrElse(Map())
          val globalBuildInfo = (BuildInfo.toMap.mapValuesNow(_.toString) ++ configuredBuildInfo).mapValuesNow(_.asJson)
          val modelDataInfo = modelData.all.mapValuesNow(_.configCreator.buildInfo()).asJson
          (globalBuildInfo + ("processingType" -> modelDataInfo)).asJson
        }
      }
    }
  }

  private def createHealthCheckHttpResponse(status: HealthCheckProcessResponseStatus, message: Option[String] = None, processes: Option[Set[String]] = None): Future[HttpResponse] =
    Marshal(HealthCheckProcessResponse(status, message, processes))
      .to[ResponseEntity]
      .map(res => HttpResponse(
        status = status match {
          case OK => StatusCodes.OK
          case ERROR => StatusCodes.InternalServerError
        },
        entity = res))

  def securedRoute(implicit user: LoggedUser): Route =
    pathPrefix("app") {
      path("healthCheck" / "process" / "deployment") {
        get {
          complete {
            processesWithProblemStateStatus.map[Future[HttpResponse]] { set =>
              if (set.isEmpty) {
                createHealthCheckHttpResponse(OK)
              } else {
                logger.warn(s"Scenarios with status PROBLEM: ${set.keys}")
                logger.debug(s"Scenarios with status PROBLEM: $set")
                createHealthCheckHttpResponse(ERROR, Some("Scenarios with status PROBLEM"), Some(set.keys.toSet))
              }
            }.recover[Future[HttpResponse]] {
              case NonFatal(e) =>
                logger.error("Failed to get statuses", e)
                createHealthCheckHttpResponse(ERROR, Some("Failed to retrieve job statuses"))
            }
          }
        }
      } ~ path("healthCheck" / "process" / "validation")  {
        get {
          complete {
            processesWithValidationErrors.map[Future[HttpResponse]] { processes =>
              if (processes.isEmpty) {
                createHealthCheckHttpResponse(OK)
              } else {
                createHealthCheckHttpResponse(ERROR, Some("Scenarios with validation errors"), Some(processes.toSet))
              }
            }
          }
        }
      } ~ path("config" / "categoriesWithProcessingType") {
        get {
          complete {
            processCategoryService.getUserCategoriesWithType(user)
          }
        }
      } ~ path("config") {
        if(!exposeConfig) reject
        //config can contain sensitive information, so only Admin can see it
        else authorize(user.isAdmin) {
          get {
            complete {
              io.circe.parser.parse(config.root().render(ConfigRenderOptions.concise())).left.map(_.message)
            }
          }
        }
      } ~ pathPrefix("processingtype" / "reload") {
        authorize(user.isAdmin) {
          post {
            pathEnd {
              complete {
                processingTypeDataReload.reloadAll()
                HttpResponse(StatusCodes.NoContent)
              }
            }
          }
        }
      }
    }

  private def processesWithProblemStateStatus(implicit ec: ExecutionContext, user: LoggedUser): Future[Map[String, ProcessState]] = {
    for {
      processes <- processRepository.fetchProcessesDetails[Unit](FetchProcessesDetailsQuery.deployed)
      statusMap <- Future.sequence(mapNameToProcessState(processes)).map(_.toMap)
      withProblem = statusMap.collect {
        case (name, processStatus@ProcessState(_, _@ProblemStateStatus(_, _), _, _, _, _, _, _, _, _)) => (name, processStatus)
      }
    } yield withProblem
  }

  private def processesWithValidationErrors(implicit ec: ExecutionContext, user: LoggedUser): Future[List[String]] = {
    processRepository.fetchProcessesDetails[DisplayableProcess](FetchProcessesDetailsQuery.unarchivedProcesses).map { processes =>
      val processesWithErrors = processes
        .map(process => new ValidatedDisplayableProcess(process.json, processValidation.validate(process.json)))
        .filter(process => !process.validationResult.errors.isEmpty)
      processesWithErrors.map(_.id)
    }
  }

  private def mapNameToProcessState(processes: Seq[BaseProcessDetails[_]])(implicit user: LoggedUser) : Seq[Future[(String, ProcessState)]] = {
    // Problems should be detected by Healtcheck very quickly. Because of that we return fresh states for list of processes
    implicit val freshnessPolicy: DataFreshnessPolicy = DataFreshnessPolicy.Fresh
    processes.map(process => deploymentService.getProcessState(process).map((process.name, _)))
  }
}
