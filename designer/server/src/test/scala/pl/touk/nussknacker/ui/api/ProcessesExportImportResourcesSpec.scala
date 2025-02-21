package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.model.{ContentTypeRange, ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import pl.touk.nussknacker.engine.api.{ProcessAdditionalFields, StreamMetaData}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.restmodel.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.test.PatientScalaFutures
import pl.touk.nussknacker.ui.api.helpers.TestFactory._
import pl.touk.nussknacker.ui.api.helpers.{EspItTest, ProcessTestData}
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.util.MultipartUtils
import io.circe.syntax._
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Inside, OptionValues}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.marshall.ProcessMarshaller
import pl.touk.nussknacker.ui.api.helpers.TestCategories.TestCat

import scala.language.higherKinds

class ProcessesExportImportResourcesSpec extends AnyFunSuite with ScalatestRouteTest with Matchers with Inside with FailFastCirceSupport
  with PatientScalaFutures with OptionValues with BeforeAndAfterEach with BeforeAndAfterAll with EspItTest {

  import akka.http.scaladsl.server.RouteConcatenation._

  private implicit final val string: FromEntityUnmarshaller[String] = Unmarshaller.stringUnmarshaller.forContentTypes(ContentTypeRange.*)
  private implicit val loggedUser: LoggedUser = LoggedUser("1", "lu", testPermissionEmpty)

  private val processesExportResources = new ProcessesExportResources(futureFetchingProcessRepository, processActivityRepository, processResolving)
  private val routeWithAllPermissions = withAllPermissions(processesExportResources) ~ withAllPermissions(processesRoute)
  private val adminRoute = asAdmin(processesExportResources) ~ asAdmin(processesRoute)

  test("export process from displayable") {
    val processToExport = ProcessTestData.sampleDisplayableProcess
    createEmptyProcess(ProcessTestData.sampleDisplayableProcess.processName)

    Post(s"/processesExport", processToExport) ~> routeWithAllPermissions ~> check {
      status shouldEqual StatusCodes.OK
      val exported = responseAs[String]
      val processDetails = ProcessMarshaller.fromJson(exported).toOption.get

      processDetails shouldBe ProcessConverter.fromDisplayable(processToExport)
    }
  }

  test("export process and import it (as common user)") {
    runImportExportTest(routeWithAllPermissions)
  }

  test("export process and import it (as admin)") {
    runImportExportTest(adminRoute)
  }

  private def runImportExportTest(route: Route): Unit = {
    val processToSave = ProcessTestData.sampleDisplayableProcess.copy(category = TestCat)
    saveProcess(processToSave) {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/processesExport/${processToSave.id}/2") ~> route ~> check {
      val response = responseAs[String]
      val processDetails = ProcessMarshaller.fromJson(response).toOption.get
      assertProcessPrettyPrinted(response, processDetails)

      val modified = processDetails.copy(metaData = processDetails.metaData.withTypeSpecificData(typeSpecificData = StreamMetaData(Some(987))))
      val multipartForm = MultipartUtils.prepareMultiPart(modified.asJson.spaces2, "process")
      Post(s"/processes/import/${processToSave.id}", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        val imported = responseAs[DisplayableProcess]
        imported.properties.typeSpecificProperties.asInstanceOf[StreamMetaData].parallelism shouldBe Some(987)
        imported.id shouldBe processToSave.id
        imported.nodes shouldBe processToSave.nodes
      }
    }
  }

  test("export process in new version") {
    val description = "alamakota"
    val processToSave = ProcessTestData.sampleDisplayableProcess.copy(category = TestCat)
    val processWithDescription = processToSave.copy(properties = processToSave.properties.copy(additionalFields = ProcessAdditionalFields(Some(description), Map.empty, StreamMetaData.typeName)))

    saveProcess(processToSave) {
      status shouldEqual StatusCodes.OK
    }
    updateProcess(processWithDescription) {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/processesExport/${processToSave.id}/2") ~> routeWithAllPermissions ~> check {
      val response = responseAs[String]
      response shouldNot include(description)
      assertProcessPrettyPrinted(response, processToSave)
    }

    Get(s"/processesExport/${processToSave.id}/3") ~> routeWithAllPermissions ~> check {
      val latestProcessVersion = io.circe.parser.parse(responseAs[String])
      latestProcessVersion.toOption.get.spaces2 should include(description)

      Get(s"/processesExport/${processToSave.id}") ~> routeWithAllPermissions ~> check {
        io.circe.parser.parse(responseAs[String]) shouldBe latestProcessVersion
      }

    }

  }

  test("export pdf") {
    val processToSave = ProcessTestData.sampleDisplayableProcess.copy(category = TestCat)
    saveProcess(processToSave) {
      status shouldEqual StatusCodes.OK

      val testSvg = "<svg viewBox=\"0 0 120 70\" xmlns=\"http://www.w3.org/2000/svg\">\n  " +
        "<path d=\"M20,20h20m5,0h20m5,0h20\" stroke=\"#c00000\" stroke-width=\"10\"/>\n  " +
        "<path d=\"M20,40h20m5,0h20m5,0h20M30,30v20m25,0v-20m25,0v20\" stroke=\"#008000\" stroke-width=\"6\"/>\n</svg>"

      Post(s"/processesExport/pdf/${processToSave.id}/2", HttpEntity(testSvg)) ~> routeWithAllPermissions ~> check {

        status shouldEqual StatusCodes.OK
        contentType shouldEqual ContentTypes.`application/octet-stream`
        //just simple sanity check that it's really pdf...
        responseAs[String] should startWith("%PDF")
      }
    }

  }

  private def assertProcessPrettyPrinted(response: String, expectedProcess: CanonicalProcess): Unit = {
    response shouldBe expectedProcess.asJson.spaces2
  }

  private def assertProcessPrettyPrinted(response: String, process: DisplayableProcess): Unit = {
    assertProcessPrettyPrinted(response, ProcessConverter.fromDisplayable(process))
  }

}
