package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.stream.Materializer
import cats.instances.future._
import cats.instances.list._
import cats.syntax.traverse._
import com.typesafe.scalalogging.LazyLogging
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import pl.touk.nussknacker.engine.api.deployment.{DataFreshnessPolicy, ProcessState}
import pl.touk.nussknacker.engine.api.process.{ProcessId, ProcessName, VersionId}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.util.Implicits._
import pl.touk.nussknacker.restmodel.displayedgraph.{DisplayableProcess, ValidatedDisplayableProcess}
import pl.touk.nussknacker.restmodel.process._
import pl.touk.nussknacker.restmodel.processdetails.{BaseProcessDetails, BasicProcess, ProcessDetails, ProcessShapeFetchStrategy, ValidatedProcessDetails}
import pl.touk.nussknacker.restmodel.validation.ValidationResults.ValidationResult
import pl.touk.nussknacker.security.Permission
import pl.touk.nussknacker.ui.EspError.XError
import pl.touk.nussknacker.ui._
import pl.touk.nussknacker.ui.api.EspErrorToHttp._
import pl.touk.nussknacker.ui.api.ProcessesResources.ProcessesQuery
import pl.touk.nussknacker.ui.listener.ProcessChangeEvent._
import pl.touk.nussknacker.ui.listener.{ProcessChangeEvent, ProcessChangeListener, User}
import pl.touk.nussknacker.ui.process.ProcessService.{CreateProcessCommand, UpdateProcessCommand}
import pl.touk.nussknacker.ui.process._
import pl.touk.nussknacker.ui.process.deployment.DeploymentService
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.process.repository.{FetchingProcessRepository, ProcessActionRepository}
import pl.touk.nussknacker.ui.process.repository.FetchingProcessRepository.FetchProcessesDetailsQuery
import pl.touk.nussknacker.ui.process.repository.ProcessRepository.RemoteUserName
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.uiresolving.UIProcessResolving
import pl.touk.nussknacker.ui.util._

import scala.concurrent.{ExecutionContext, Future}

//TODO: Move remained business logic to processService
class ProcessesResources(
                          val processRepository: FetchingProcessRepository[Future],
                          processService: ProcessService,
                          deploymentService: DeploymentService,
                          processToolbarService: ProcessToolbarService,
                          processResolving: UIProcessResolving,
                          val processAuthorizer: AuthorizeProcess,
                          processChangeListener: ProcessChangeListener
                        )(implicit val ec: ExecutionContext, mat: Materializer)
  extends Directives
    with FailFastCirceSupport
    with EspPathMatchers
    with RouteWithUser
    with LazyLogging
    with AuthorizeProcessDirectives
    with ProcessDirectives {

  import akka.http.scaladsl.unmarshalling.Unmarshaller._

  def securedRoute(implicit user: LoggedUser): Route = {
    encodeResponse {
      path("archive") {
        get {
          complete {
            processService.getArchivedProcessesAndFragments[Unit].toBasicProcess
          }
        }
      } ~ path("unarchive" / Segment) { processName =>
        (post & processId(processName)) { processId =>
          canWrite(processId) {
            complete {
              processService.unArchiveProcess(processId)
                .map(toResponse(StatusCodes.OK))
                .withSideEffect(_ => sideEffectAction(OnUnarchived(processId.id)))
            }
          }
        }
      } ~ path("archive" / Segment) { processName =>
        (post & processId(processName)) { processId =>
          canWrite(processId) {
            complete {
              processService.archiveProcess(processId)
                .map(toResponse(StatusCodes.OK))
                .withSideEffect(_ => sideEffectAction(OnArchived(processId.id)))
            }
          }
        }
      } ~ path("processes") {
        get {
          processesQuery { query =>
            complete {
              // To not overload engine, for list of processes we provide statuses that can be cached
              implicit val freshnessPolicy: DataFreshnessPolicy = DataFreshnessPolicy.CanBeCached
              processRepository.fetchProcessesDetails[Unit](query.toRepositoryQuery)
                .flatMap(_.map(enrichDetailsWithProcessState[Unit]).sequence).toBasicProcess
            }
          }
        }
      } ~ path("processesDetails") {
        (get & processesQuery & skipValidateAndResolveParameter) { (query, skipValidateAndResolve) =>
          complete {
            val processes = processRepository.fetchProcessesDetails[CanonicalProcess](query.toRepositoryQuery)
            if (skipValidateAndResolve) {
              toProcessDetailsAll(processes)
            } else {
              validateAndReverseResolveAll(processes)
            }
          }
        }
      } ~ path("processes" / "status") {
        get {
          complete {
            for {
              processes <- processService.getProcesses[Unit]
              statuses <- fetchProcessStatesForProcesses(processes)
            } yield statuses
          }
        }
      } ~ path("processes" / "import" / Segment) { processName =>
        processId(processName) { processId =>
          (canWrite(processId) & post) {
            fileUpload("process") { case (_, byteSource) =>
              complete {
                MultipartUtils
                  .readFile(byteSource)
                  .flatMap(processService.importProcess(processId, _))
                  .map(toResponseEither[ValidatedDisplayableProcess])
              }
            }
          }
        }
      } ~ path("processes" / Segment / "deployments") { processName =>
        processId(processName) { processId =>
          complete {
            //FIXME: We should provide Deployment definition and return there all deployments, not actions..
            processService.getProcessActions(processId.id)
          }
        }
      } ~ path("processes" / Segment) { processName =>
        processId(processName) { processId =>
          (delete & canWrite(processId)) {
            complete {
              processService
                .deleteProcess(processId)
                .map(toResponse(StatusCodes.OK))
                .withSideEffect(_ => sideEffectAction(OnDeleted(processId.id)))
            }
          } ~ (put & canWrite(processId)) {
            entity(as[UpdateProcessCommand]) { updateCommand =>
              canOverrideUsername(processId.id, updateCommand.forwardedUserName)(ec, user) {
                complete {
                  processService
                    .updateProcess(processId, updateCommand)
                    .withSideEffect(response => sideEffectAction(response.toOption.flatMap(_.processResponse)) { resp =>
                      OnSaved(resp.id, resp.versionId)
                    })
                    .map(_.map(_.validationResult))
                    .map(toResponseEither[ValidationResult])
                }
              }
            }
          } ~ (get & skipValidateAndResolveParameter) { skipValidateAndResolve =>
            complete {
              implicit val freshnessPolicy: DataFreshnessPolicy = DataFreshnessPolicy.Fresh
              processRepository.fetchLatestProcessDetailsForProcessId[CanonicalProcess](processId.id).flatMap[ToResponseMarshallable] {
                case Some(process) if skipValidateAndResolve => enrichDetailsWithProcessState(process).map(toProcessDetails)
                case Some(process) => enrichDetailsWithProcessState(process).map(validateAndReverseResolve)
                case None => Future.successful(HttpResponse(status = StatusCodes.NotFound, entity = "Scenario not found"))
              }
            }
          }
        }
      } ~ path("processes" / Segment / "rename" / Segment) { (processName, newName) =>
        (put & processId(processName)) { processId =>
          canWrite(processId) {
            complete {
              processService
                .renameProcess(processId, ProcessName(newName))
                .withSideEffect(response => sideEffectAction(response) { resp =>
                  OnRenamed(processId.id, resp.oldName, resp.newName)
                })
                .map(toResponseEither[UpdateProcessNameResponse])
            }
          }
        }
      } ~ path("processes" / Segment / VersionIdSegment) { (processName, versionId) =>
        (get & processId(processName) & skipValidateAndResolveParameter) { (processId, skipValidateAndResolve) =>
          complete {
            implicit val freshnessPolicy: DataFreshnessPolicy = DataFreshnessPolicy.Fresh
            processRepository.fetchProcessDetailsForId[CanonicalProcess](processId.id, versionId).flatMap[ToResponseMarshallable] {
              case Some(process) if skipValidateAndResolve => enrichDetailsWithProcessState(process).map(toProcessDetails)
              case Some(process) => enrichDetailsWithProcessState(process).map(validateAndReverseResolve)
              case None => Future.successful(HttpResponse(status = StatusCodes.NotFound, entity = "Scenario not found"))
            }
          }
        }
      } ~ path("processes" / Segment / Segment) { (processName, category) =>
        authorize(user.can(category, Permission.Write)) {
          optionalHeaderValue(RemoteUserName.extractFromHeader) { remoteUserName =>
            canOverrideUsername(category, remoteUserName)(user) {
              //TODO change it to `parameters(Symbol("isFragment") ? false)` after NU 1.10 release
              parameters(Symbol("isFragment").as[Boolean].optional, Symbol("isSubprocess").withDefault(false)) { (isFragment, isSubprocess) =>
                post {
                  complete {
                    processService
                      .createProcess(CreateProcessCommand(ProcessName(processName), category, isFragment.getOrElse(isSubprocess), remoteUserName))
                      .withSideEffect(response => sideEffectAction(response) { process =>
                        OnSaved(process.id, process.versionId)
                      })
                      .map(toResponseEither[ProcessResponse](_, StatusCodes.Created))
                  }
                }
              }
            }
          }
        }
      } ~ path("processes" / Segment / "status") { processName =>
        (get & processId(processName)) { processId =>
          complete {
            implicit val freshnessPolicy: DataFreshnessPolicy = DataFreshnessPolicy.Fresh
            deploymentService.getProcessState(processId).map(ToResponseMarshallable(_))
          }
        }
      } ~ path("processes" / Segment / "toolbars") { processName =>
        (get & processId(processName)) { processId =>
          complete {
            processService
              .getProcess[Unit](processId)
              .map(resp => resp.map(processToolbarService.getProcessToolbarSettings))
              .map(toResponseEither[ProcessToolbarSettings])
          }
        }
      } ~ path("processes" / "category" / Segment / Segment) { (processName, category) =>
        (post & processId(processName)) { processId =>
          hasAdminPermission(user) {
            complete {
              processService
                .updateCategory(processId, category)
                .withSideEffect(response => sideEffectAction(response) { resp =>
                  OnCategoryChanged(processId.id, resp.oldCategory, resp.newCategory)
                })
                .map(toResponseEither[UpdateProcessCategoryResponse])
            }
          }
        }
      } ~ path("processes" / Segment / VersionIdSegment / "compare" / VersionIdSegment) { (processName, thisVersion, otherVersion) =>
        (get & processId(processName)) { processId =>
          complete {
            withJson(processId.id, thisVersion) { thisDisplayable =>
              withJson(processId.id, otherVersion) { otherDisplayable =>
                ProcessComparator.compare(thisDisplayable, otherDisplayable)
              }
            }
          }
        }
      }

    }
  }

  private def sideEffectAction(event: ProcessChangeEvent)(implicit user: LoggedUser): Unit = {
    implicit val listenerUser: User = ListenerApiUser(user)
    processChangeListener.handle(event)
  }

  private def sideEffectAction[T](response: XError[T])(eventAction: T => ProcessChangeEvent)(implicit user: LoggedUser): Unit = {
    sideEffectAction(response.toOption)(eventAction)
  }

  private def sideEffectAction[T](response: Option[T])(eventAction: T => ProcessChangeEvent)(implicit user: LoggedUser): Unit = {
    implicit val listenerUser: User = ListenerApiUser(user)
    response.foreach(resp => processChangeListener.handle(eventAction(resp)))
  }

  private def fetchProcessStatesForProcesses(processes: List[BaseProcessDetails[Unit]])(implicit user: LoggedUser): Future[Map[String, ProcessState]] = {
    // To not overload engine, for list of processes we provide statuses that can be cached
    implicit val freshnessPolicy: DataFreshnessPolicy = DataFreshnessPolicy.CanBeCached
    processes.map(process => deploymentService.getProcessState(process).map(status => process.name -> status))
      .sequence[Future, (String, ProcessState)].map(_.toMap)
  }

  private def enrichDetailsWithProcessState[PS: ProcessShapeFetchStrategy](process: BaseProcessDetails[PS])
                                                                          (implicit user: LoggedUser,
                                                                           freshnessPolicy: DataFreshnessPolicy): Future[BaseProcessDetails[PS]] = {
    if (process.isFragment)
      Future.successful(process)
    else
      deploymentService.getProcessState(process).map(state => process.copy(state = Some(state)))
  }

  private def withJson(processId: ProcessId, version: VersionId)
                      (process: DisplayableProcess => ToResponseMarshallable)(implicit user: LoggedUser): ToResponseMarshallable
  = processRepository.fetchProcessDetailsForId[DisplayableProcess](processId, version).map { maybeProcess =>
    maybeProcess.map(_.json) match {
      case Some(displayable) => process(displayable)
      case None => HttpResponse(status = StatusCodes.NotFound, entity = s"Scenario $processId in version $version not found"): ToResponseMarshallable
    }
  }

  private def validateAndReverseResolveAll(processDetails: Future[List[BaseProcessDetails[CanonicalProcess]]]): Future[List[ValidatedProcessDetails]] = {
    processDetails.flatMap(all => Future.sequence(all.map(validateAndReverseResolve)))
  }

  private def validateAndReverseResolve(processDetails: BaseProcessDetails[CanonicalProcess]): Future[ValidatedProcessDetails] = {
    val validatedDetails = processDetails.mapProcess { canonical: CanonicalProcess =>
      val processingType = processDetails.processingType
      val validationResult = processResolving.validateBeforeUiReverseResolving(canonical, processingType, processDetails.processCategory)
      processResolving.reverseResolveExpressions(canonical, processingType, processDetails.processCategory, validationResult)
    }
    Future.successful(validatedDetails)
  }

  private def toProcessDetails(canonicalProcessDetails: BaseProcessDetails[CanonicalProcess]): Future[ProcessDetails] = {
    val processDetails = canonicalProcessDetails.mapProcess(canonical => ProcessConverter.toDisplayable(canonical, canonicalProcessDetails.processingType, canonicalProcessDetails.processCategory))
    Future.successful(processDetails)
  }

  private def toProcessDetailsAll(canonicalProcessDetails: Future[List[BaseProcessDetails[CanonicalProcess]]]): Future[List[ProcessDetails]] = {
    canonicalProcessDetails.flatMap(all => Future.sequence(all.map(toProcessDetails)))
  }

  private implicit class ToBasicConverter(self: Future[List[BaseProcessDetails[_]]]) {
    def toBasicProcess: Future[List[BasicProcess]] = self.map(f => f.map(bpd => BasicProcess(bpd)))
  }

  private def processesQuery: Directive1[ProcessesQuery] = {
    parameters(
      Symbol("isFragment").as[Boolean].?,
      Symbol("isSubprocess").as[Boolean].?, //TODO remove `isSubprocess` after NU 1.10 release
      Symbol("isArchived").as[Boolean].?,
      Symbol("isDeployed").as[Boolean].?,
      Symbol("categories").as(CsvSeq[String]).?,
      Symbol("processingTypes").as(CsvSeq[String]).?,
      Symbol("names").as(CsvSeq[String]).?,
    ).as(ProcessesQuery.apply _)
  }

  private def skipValidateAndResolveParameter = {
    parameters(Symbol("skipValidateAndResolve").as[Boolean].withDefault(false))
  }
}

object ProcessesResources {
  case class UnmarshallError(message: String) extends Exception(message) with FatalError

  case class ProcessesQuery(isFragment: Option[Boolean],
                            isSubprocess: Option[Boolean],
                            isArchived: Option[Boolean],
                            isDeployed: Option[Boolean],
                            categories: Option[Seq[String]],
                            processingTypes: Option[Seq[String]],
                            names: Option[Seq[String]],
                           ) {
    def toRepositoryQuery: FetchProcessesDetailsQuery = FetchProcessesDetailsQuery(
      isFragment = isFragment.orElse(isSubprocess),
      isArchived = isArchived,
      isDeployed = isDeployed,
      categories = categories,
      processingTypes = processingTypes,
      names = names.map(_.map(ProcessName(_))),
    )
  }

  object ProcessesQuery {
    def empty: ProcessesQuery = ProcessesQuery(None, None, None, None, None, None, None)
  }
}
