package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.component.{ComponentId, ComponentType}
import pl.touk.nussknacker.engine.api.process.ProcessName
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.restmodel.component.{ComponentListElement, ComponentUsagesInScenario}
import pl.touk.nussknacker.test.PatientScalaFutures
import pl.touk.nussknacker.ui.api.helpers.{EspItTest, TestCategories, TestProcessingTypes}
import pl.touk.nussknacker.ui.component.{ComponentIdProvider, DefaultComponentIdProvider, DefaultComponentService}
import pl.touk.nussknacker.ui.config.ComponentLinksConfigExtractor

class ComponentResourcesSpec extends AnyFunSpec with ScalatestRouteTest with FailFastCirceSupport
  with Matchers with PatientScalaFutures with BeforeAndAfterEach with BeforeAndAfterAll with EspItTest {

  //These should be defined as lazy val's because of racing, there are some missing tables in db..
  private val defaultComponentIdProvider: ComponentIdProvider = new DefaultComponentIdProvider(Map.empty)
  private lazy val componentService = DefaultComponentService(
    ComponentLinksConfigExtractor.extract(config),
    testProcessingTypeDataProvider.mapCombined(_ => defaultComponentIdProvider),
    processService,
    processCategoryService)
  private lazy val componentRoute = new ComponentResource(componentService)

  //Here we test only response, logic is tested in DefaultComponentServiceSpec
  it("should return users(test, admin) components list") {
    getComponents() ~> check {
      status shouldBe StatusCodes.OK
      val testCatComponents = responseAs[List[ComponentListElement]]
      testCatComponents.nonEmpty shouldBe true

      getComponents(true) ~> check {
        status shouldBe StatusCodes.OK
        val adminComponents = responseAs[List[ComponentListElement]]
        adminComponents.nonEmpty shouldBe true

        adminComponents.size > testCatComponents.size shouldBe true
      }
    }
  }

  it("should return component usages") {
    val processName = ProcessName("someTest")
    val sourceComponentName = "kafka" //it's real component name from DevProcessConfigCreator
    val process = ScenarioBuilder
      .streaming(processName.value)
      .source("source", sourceComponentName)
      .emptySink("sink", "kafka")

    val processId = createProcess(process, TestCategories.Category1, TestProcessingTypes.Streaming)
    val componentId = defaultComponentIdProvider.createComponentId(TestProcessingTypes.Streaming, Some(sourceComponentName), ComponentType.Source)

    getComponentUsages(componentId, isAdmin = true) ~> check {
      status shouldBe StatusCodes.OK
      val processes = responseAs[List[ComponentUsagesInScenario]]
      processes.size shouldBe 1

      val process = processes.head
      process.processId shouldBe processId
      process.name shouldBe processName
      process.processCategory shouldBe TestCategories.Category1
      process.isFragment shouldBe false
    }
  }

  it("should return 404 when component not exist") {
    val componentId = ComponentId("not-exist-component")

    getComponentUsages(componentId, isAdmin = true) ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  private def getComponents(isAdmin: Boolean = false): RouteTestResult =
    Get(s"/components") ~> routeWithPermissions(componentRoute, isAdmin)

  private def getComponentUsages(componentId: ComponentId, isAdmin: Boolean = false): RouteTestResult =
    Get(s"/components/$componentId/usages") ~> routeWithPermissions(componentRoute, isAdmin)
}
