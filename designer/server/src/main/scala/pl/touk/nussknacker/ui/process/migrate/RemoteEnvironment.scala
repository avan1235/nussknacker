package pl.touk.nussknacker.ui.process.migrate

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, RawHeader}
import akka.http.scaladsl.model.{RequestEntity, _}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Decoder
import pl.touk.nussknacker.engine.api.process.{ProcessName, VersionId}
import pl.touk.nussknacker.restmodel.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.restmodel.processdetails.{BasicProcess, ProcessDetails, ProcessVersion, ValidatedProcessDetails}
import pl.touk.nussknacker.restmodel.validation.ValidationResults.{ValidationErrors, ValidationResult}
import pl.touk.nussknacker.ui.EspError
import pl.touk.nussknacker.ui.EspError.XError
import pl.touk.nussknacker.ui.process.ProcessService.UpdateProcessCommand
import pl.touk.nussknacker.ui.process.repository.ProcessRepository.RemoteUserName
import pl.touk.nussknacker.ui.process.repository.UpdateProcessComment
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.util.ProcessComparator
import pl.touk.nussknacker.ui.util.ProcessComparator.Difference

import scala.concurrent.{ExecutionContext, Future}

trait RemoteEnvironment {

  val passUsernameInMigration: Boolean = true

  def compare(localProcess: DisplayableProcess, remoteProcessVersion: Option[VersionId])(implicit ec: ExecutionContext): Future[Either[EspError, Map[String, Difference]]]

  def processVersions(processName: ProcessName)(implicit ec: ExecutionContext): Future[List[ProcessVersion]]

  def migrate(localProcess: DisplayableProcess, category: String)(implicit ec: ExecutionContext, loggedUser: LoggedUser): Future[Either[EspError, Unit]]

  def testMigration(processToInclude: BasicProcess => Boolean = _ => true)(implicit ec: ExecutionContext): Future[Either[EspError, List[TestMigrationResult]]]
}

case class RemoteEnvironmentCommunicationError(statusCode: StatusCode, getMessage: String) extends EspError

case class MigrationValidationError(errors: ValidationErrors) extends EspError {
  override def getMessage: String = {
    val messages = errors.globalErrors.map(_.message) ++
      errors.processPropertiesErrors.map(_.message) ++ errors.invalidNodes.map { case (node, nerror) => s"$node - ${nerror.map(_.message).mkString(", ")}" }
    s"Cannot migrate, following errors occurred: ${messages.mkString(", ")}"
  }
}

case class MigrationToArchivedError(processName: ProcessName, environment: String) extends EspError {
  def getMessage = s"Cannot migrate, scenario ${processName.value} is archived on $environment. You have to unarchive scenario on $environment in order to migrate."
}

case class HttpRemoteEnvironmentConfig(user: String, password: String, targetEnvironmentId: String,
                                       remoteConfig: StandardRemoteEnvironmentConfig, passUsernameInMigration: Boolean = true)

class HttpRemoteEnvironment(httpConfig: HttpRemoteEnvironmentConfig,
                            val testModelMigrations: TestModelMigrations,
                            val environmentId: String)
                           (implicit as: ActorSystem, val materializer: Materializer, ec: ExecutionContext) extends StandardRemoteEnvironment {
  override val config: StandardRemoteEnvironmentConfig = httpConfig.remoteConfig

  override val passUsernameInMigration: Boolean = httpConfig.passUsernameInMigration

  val http = Http()

  override protected def request(uri: Uri, method: HttpMethod, request: MessageEntity, headers: Seq[HttpHeader]): Future[HttpResponse] = {
    http.singleRequest(HttpRequest(uri = uri, method = method, entity = request,
      headers = List(Authorization(BasicHttpCredentials(httpConfig.user, httpConfig.password))) ++ headers))
  }
}

case class StandardRemoteEnvironmentConfig(uri: String, batchSize: Int = 10)

//TODO: extract interface to remote environment?
trait StandardRemoteEnvironment extends FailFastCirceSupport with RemoteEnvironment {

  private type FutureE[T] = EitherT[Future, EspError, T]

  def environmentId: String

  def config: StandardRemoteEnvironmentConfig

  def testModelMigrations: TestModelMigrations

  def baseUri = Uri(config.uri)

  implicit def materializer: Materializer

  override def processVersions(processName: ProcessName)(implicit ec: ExecutionContext): Future[List[ProcessVersion]] =
    invokeJson[ProcessDetails](HttpMethods.GET, List("processes", processName.value)).map { result =>
      result.fold(_ => List(), _.history)
    }

  protected def request(uri: Uri, method: HttpMethod, request: MessageEntity, headers: Seq[HttpHeader]): Future[HttpResponse]

  override def compare(localProcess: DisplayableProcess, remoteProcessVersion: Option[VersionId])(implicit ec: ExecutionContext): Future[Either[EspError, Map[String, Difference]]] = {
    val id = localProcess.id
    (for {
      process <- EitherT(fetchProcessVersion(id, remoteProcessVersion))
      compared <- EitherT.rightT[Future, EspError](ProcessComparator.compare(localProcess, process.json))
    } yield compared).value
  }

  override def migrate(localProcess: DisplayableProcess, category: String)
                      (implicit ec: ExecutionContext, loggedUser: LoggedUser): Future[Either[EspError, Unit]] = {
    (for {
      validation <- EitherT(validateProcess(localProcess))
      _ <- EitherT.fromEither[Future](if (validation.errors != ValidationErrors.success) Left[EspError, Unit](MigrationValidationError(validation.errors)) else Right(()))
      processEither <- fetchProcessDetails(localProcess.id)
      _ <- processEither match {
        case Right(processDetails) if processDetails.isArchived => EitherT.leftT[Future, EspError](MigrationToArchivedError(processDetails.idWithName.name, environmentId))
        case Right(_) => EitherT.rightT[Future, EspError](())
        case Left(RemoteEnvironmentCommunicationError(StatusCodes.NotFound, _)) =>
          val userToForward = if (passUsernameInMigration) Some(loggedUser) else None
          createProcessOnRemote(localProcess, category, userToForward)
        case Left(other) => EitherT.leftT[Future, EspError](other)
      }
      usernameToPass = if (passUsernameInMigration) Some(RemoteUserName(loggedUser.username)) else None
      _ <- EitherT {
        saveProcess(localProcess, UpdateProcessComment(s"Scenario migrated from $environmentId by ${loggedUser.username}"), usernameToPass)
      }
    } yield ()).value
  }

  private def createProcessOnRemote(localProcess: DisplayableProcess, category: String, loggedUser: Option[LoggedUser])
                                   (implicit ec: ExecutionContext): FutureE[Unit] = {
    val remoteUserNameHeader: List[HttpHeader] = loggedUser.map(user => RawHeader(RemoteUserName.headerName, user.username)).toList
    EitherT {
      invokeForSuccess(
        HttpMethods.POST, List("processes", localProcess.id, category),
        //TODO replace `isSubprocess` with `isFragment` after 1.10 release
        Query(("isSubprocess", localProcess.metaData.isFragment.toString)), HttpEntity.Empty, remoteUserNameHeader
      )
    }
  }

  override def testMigration(processToInclude: BasicProcess => Boolean = _ => true)(implicit ec: ExecutionContext): Future[Either[EspError, List[TestMigrationResult]]] = {
    (for {
      allBasicProcesses <- EitherT(fetchProcesses)
      basicProcesses = allBasicProcesses.filterNot(_.isFragment).filter(processToInclude)
      basicFragments = allBasicProcesses.filter(_.isFragment).filter(processToInclude)
      processes <- fetchGroupByGroup(basicProcesses)
      fragments <- fetchGroupByGroup(basicFragments)
    } yield testModelMigrations.testMigrations(processes, fragments)).value
  }

  private def fetchGroupByGroup[T](basicProcesses: List[BasicProcess])
                                  (implicit ec: ExecutionContext): FutureE[List[ValidatedProcessDetails]] = {
    basicProcesses.map(_.name).grouped(config.batchSize)
      .foldLeft(EitherT.rightT[Future, EspError](List.empty[ValidatedProcessDetails])) { case (acc, processesGroup) =>
        for {
          current <- acc
          fetched <- fetchProcessesDetails(processesGroup)
        } yield current ::: fetched
      }
  }

  private def fetchProcesses(implicit ec: ExecutionContext): Future[Either[EspError, List[BasicProcess]]] = {
    invokeJson[List[BasicProcess]](HttpMethods.GET, List("processes"), Query(("isArchived", "false")))
  }

  private def fetchProcessVersion(id: String, remoteProcessVersion: Option[VersionId])
                                 (implicit ec: ExecutionContext): Future[Either[EspError, ProcessDetails]] = {
    invokeJson[ProcessDetails](HttpMethods.GET, List("processes", id) ++ remoteProcessVersion.map(_.value.toString).toList, Query())
  }

  private def fetchProcessDetails(id: String)
                                 (implicit ec: ExecutionContext): FutureE[Either[EspError, ProcessDetails]] = {
    EitherT(invokeJson[ProcessDetails](HttpMethods.GET, List("processes", id)).map(_.asRight))
  }

  private def fetchProcessesDetails(names: List[ProcessName])(implicit ec: ExecutionContext) = EitherT {
    invokeJson[List[ValidatedProcessDetails]](
      HttpMethods.GET,
      "processesDetails" :: Nil,
      Query(
        ("names", names.map(ns => URLEncoder.encode(ns.value, StandardCharsets.UTF_8)).mkString(",")),
        ("isArchived", "false"),
      )
    )
  }

  private def validateProcess(process: DisplayableProcess)(implicit ec: ExecutionContext): Future[Either[EspError, ValidationResult]] = {
    for {
      processToValidate <- Marshal(process).to[MessageEntity]
      validation <- invokeJson[ValidationResult](HttpMethods.POST, List("processValidation"), requestEntity = processToValidate)
    } yield validation
  }

  private def saveProcess(process: DisplayableProcess, comment: UpdateProcessComment, forwardedUserName: Option[RemoteUserName])(implicit ec: ExecutionContext): Future[Either[EspError, ValidationResult]] = {
    for {
      processToSave <- Marshal(UpdateProcessCommand(process, comment, forwardedUserName)).to[MessageEntity](marshaller, ec)
      response <- invokeJson[ValidationResult](HttpMethods.PUT, List("processes", process.id), requestEntity = processToSave)
    } yield response
  }

  private def invoke[T](method: HttpMethod, pathParts: List[String], query: Query = Query.Empty, requestEntity: RequestEntity = HttpEntity.Empty, headers: Seq[HttpHeader])
                       (f: HttpResponse => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val pathEncoded = pathParts.foldLeft[Path](baseUri.path)(_ / _)
    val uri = baseUri.withPath(pathEncoded).withQuery(query)

    request(uri, method, requestEntity, headers) flatMap f
  }

  private def invokeForSuccess(method: HttpMethod, pathParts: List[String], query: Query = Query.Empty, requestEntity: RequestEntity, headers: Seq[HttpHeader])(implicit ec: ExecutionContext): Future[XError[Unit]] =
    invoke(method, pathParts, query, requestEntity, headers) { response =>
      if (response.status.isSuccess()) {
        response.discardEntityBytes()
        Future.successful(().asRight)
      } else {
        Unmarshaller.stringUnmarshaller(response.entity)
          .map(error => RemoteEnvironmentCommunicationError(response.status, error).asLeft)
      }
    }

  private def invokeStatus(method: HttpMethod, pathParts: List[String])(implicit ec: ExecutionContext): Future[StatusCode] =
    invoke(method, pathParts, headers = Nil) { response =>
      response.discardEntityBytes()
      Future.successful(response.status)
    }

  private def invokeJson[T: Decoder](method: HttpMethod, pathParts: List[String],
                                     query: Query = Query.Empty, requestEntity: RequestEntity = HttpEntity.Empty)
                                    (implicit ec: ExecutionContext): Future[Either[EspError, T]] = {
    invoke(method, pathParts, query, requestEntity, headers = Nil) { response =>
      if (response.status.isSuccess()) {
        Unmarshal(response.entity).to[T].map(Either.right)
      } else {
        Unmarshaller.stringUnmarshaller(response.entity)
          .map(error => Either.left(RemoteEnvironmentCommunicationError(response.status, error)))
      }
    }
  }
}
