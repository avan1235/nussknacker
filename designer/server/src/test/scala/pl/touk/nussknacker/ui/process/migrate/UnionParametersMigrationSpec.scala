package pl.touk.nussknacker.ui.process.migrate

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, have}
import pl.touk.nussknacker.engine.build.ScenarioBuilder
import pl.touk.nussknacker.engine.graph.evaluatedparam
import pl.touk.nussknacker.engine.graph.node.CustomNode
import pl.touk.nussknacker.engine.migration.ProcessMigrations
import pl.touk.nussknacker.ui.api.helpers.ProcessTestData.{existingSinkFactory, existingSourceFactory}
import pl.touk.nussknacker.ui.api.helpers.{ProcessTestData, TestFactory, TestProcessUtil, TestProcessingTypes}

import scala.reflect.ClassTag

class UnionParametersMigrationSpec extends AnyFunSuite {

  import pl.touk.nussknacker.engine.spel.Implicits._

  private val migrations = ProcessMigrations.listOf(UnionParametersMigration())

  test("should migrate union node 'value' parameter name to Output expression") {
    val testMigration = newTestModelMigrations(migrations)
    val process =
      ProcessTestData.toValidatedDisplayable(ScenarioBuilder
        .streaming("fooProcess")
        .source("source", existingSourceFactory)
        .customNode("customNode", "groupedBy", "union", "value" -> "#input")
        .emptySink("sink", existingSinkFactory))

    val results = testMigration.testMigrations(List(TestProcessUtil.validatedToProcess(process)), List())

    results should have size 1
    val processMigrationResult = results.find(_.converted.id == process.id).get
    processMigrationResult.shouldFail shouldBe false
    getFirst[CustomNode](processMigrationResult).parameters shouldBe List(evaluatedparam.Parameter("Output expression", "#input"))
  }

  test("should do nothing for union-memo node") {
    val testMigration = newTestModelMigrations(migrations)
    val process =
      ProcessTestData.toValidatedDisplayable(ScenarioBuilder
        .streaming("fooProcess")
        .source("source", existingSourceFactory)
        .customNode("customNode", "groupedBy", "union-memo", "value" -> "#input")
        .emptySink("sink", existingSinkFactory))

    val results = testMigration.testMigrations(List(TestProcessUtil.validatedToProcess(process)), List())

    results should have size 1
    val processMigrationResult = results.find(_.converted.id == process.id).get
    processMigrationResult.shouldFail shouldBe false
    getFirst[CustomNode](processMigrationResult).parameters shouldBe List(evaluatedparam.Parameter("value", "#input"))
  }

  test("should do nothing when union node is missing") {
    val testMigration = newTestModelMigrations(migrations)
    val process =
      ProcessTestData.toValidatedDisplayable(ScenarioBuilder
        .streaming("fooProcess")
        .source("source", existingSourceFactory)
        .emptySink("sink", existingSinkFactory))

    val results = testMigration.testMigrations(List(TestProcessUtil.validatedToProcess(process)), List())

    results should have size 1
    val processMigrationResult = results.find(_.converted.id == process.id).get
    processMigrationResult.shouldFail shouldBe false
  }

  private def getFirst[T: ClassTag](result: TestMigrationResult): T = result.converted.nodes.collectFirst { case t: T => t }.get

  private def newTestModelMigrations(testMigrations: ProcessMigrations): TestModelMigrations = {
    new TestModelMigrations(TestFactory.mapProcessingTypeDataProvider(TestProcessingTypes.Streaming -> testMigrations), TestFactory.processValidation)
  }

}
