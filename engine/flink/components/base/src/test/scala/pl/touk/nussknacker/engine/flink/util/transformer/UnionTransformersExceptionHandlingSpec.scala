package pl.touk.nussknacker.engine.flink.util.transformer

import cats.data.NonEmptyList
import org.scalatest.funsuite.AnyFunSuite
import pl.touk.nussknacker.engine.ModelData
import pl.touk.nussknacker.engine.api.CustomStreamTransformer
import pl.touk.nussknacker.engine.api.process.{EmptyProcessConfigCreator, ProcessObjectDependencies, WithCategories}
import pl.touk.nussknacker.engine.build.GraphBuilder
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.flink.test.{CorrectExceptionHandlingSpec, MiniClusterExecutionEnvironment}
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.process.runner.TestFlinkRunner
import pl.touk.nussknacker.engine.spel.Implicits._

class UnionTransformersExceptionHandlingSpec extends AnyFunSuite with CorrectExceptionHandlingSpec {
  override protected def registerInEnvironment(env: MiniClusterExecutionEnvironment, modelData: ModelData, scenario: CanonicalProcess): Unit
  = TestFlinkRunner.registerInEnvironmentWithModel(env, modelData)(scenario)

  private val durationExpression = "T(java.time.Duration).parse('PT1M')"

  private val configCreator = new EmptyProcessConfigCreator() {
    override def customStreamTransformers(processObjectDependencies: ProcessObjectDependencies): Map[String, WithCategories[CustomStreamTransformer]] =
      Map(
        "union" -> WithCategories(UnionTransformer),
        "union-memo" -> WithCategories(UnionWithMemoTransformer))
  }

  test("should handle exceptions in union keys") {
    checkExceptions(configCreator) { case (graph, generator) =>
      val prepared = graph
        .split("branches",
          GraphBuilder.branchEnd("union1", "union1"),
          GraphBuilder.branchEnd("union2", "union2"),
        )
      NonEmptyList.of(prepared,
        GraphBuilder.join("union1", "union", Some("out4"),
          List(("union1", List[(String, Expression)](("Output expression", generator.throwFromString())))))
          .emptySink("end3", "empty"),
        GraphBuilder.join("union2", "union-memo", Some("out4"),
          List(("union2", List[(String, Expression)](("key", generator.throwFromString()), ("value", generator.throwFromString())))),
          "stateTimeout" -> durationExpression).emptySink("end4", "empty")
      )
    }
  }

}
