package pl.touk.nussknacker.engine.flink.api.process

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import pl.touk.nussknacker.engine.api.{Context, MethodToInvoke}
import pl.touk.nussknacker.engine.api.process.{Source, SourceFactory, SourceTestSupport}
import pl.touk.nussknacker.engine.flink.api.compat.ExplicitUidInOperatorsSupport
import pl.touk.nussknacker.engine.flink.api.timestampwatermark.TimestampWatermarkHandler
import pl.touk.nussknacker.engine.flink.util.context.{BasicFlinkContextInitializer, FlinkContextInitializer}

import scala.reflect._

/**
  * Source with methods specific for Flink.
  *
  * @tparam T - type of event that is generated by this source. This is needed to handle e.g. syntax suggestions in UI
  */
//TODO: remove type T parameter
trait FlinkSource[T] extends Source[T] {

  def sourceStream(env: StreamExecutionEnvironment,
                   flinkNodeContext: FlinkCustomNodeContext): DataStream[Context]

}

/**
  * Source with typical source stream trasformations:
  *  - adds source using provided SourceFunction (here raw source produces raw data)
  *  - sets UID
  *  - assigns timestamp and watermarks
  *  - initializes Context that is streamed within output DataStream (here raw data are mapped to Context)
  * It separates raw event data produced by SourceFunction and data released to the stream as Context variables.
  * By default it uses simple implementation of initializer, see [[pl.touk.nussknacker.engine.flink.util.context.BasicFlinkContextInitializer]].
  *
  * @tparam T - type of event that is generated by this source. This is needed to handle e.g. syntax suggestions in UI
  */
trait FlinkIntermediateRawSource[T] extends ExplicitUidInOperatorsSupport { self: Source[T] =>

  // We abstracting to stream so theoretically it shouldn't be defined on this level but:
  // * for test mechanism purpose we need to know what type will be generated.
  // * for production sources (eg BaseFlinkSource, KafkaSource) it is used to determine type information for flinkSourceFunction
  def typeInformation: TypeInformation[T]

  def timestampAssigner : Option[TimestampWatermarkHandler[T]]

  val contextInitializer: FlinkContextInitializer[T] = new BasicFlinkContextInitializer[T]

  def prepareSourceStream(env: StreamExecutionEnvironment, flinkNodeContext: FlinkCustomNodeContext, sourceFunction: SourceFunction[T]): DataStream[Context] = {
    env.setStreamTimeCharacteristic(if (timestampAssigner.isDefined) TimeCharacteristic.EventTime else TimeCharacteristic.IngestionTime)

    val rawSourceWithUid = setUidToNodeIdIfNeed(flinkNodeContext, env
      .addSource[T](sourceFunction)(typeInformation)
      .name(s"${flinkNodeContext.metaData.id}-${flinkNodeContext.nodeId}-source"))

    val rawSourceWithUidAndTimestamp = timestampAssigner
      .map(_.assignTimestampAndWatermarks(rawSourceWithUid))
      .getOrElse(rawSourceWithUid)

    val typeInformationFromNodeContext = flinkNodeContext.typeInformationDetection.forContext(flinkNodeContext.validationContext.left.get)
    rawSourceWithUidAndTimestamp
      .map(contextInitializer.initContext(flinkNodeContext.metaData.id, flinkNodeContext.nodeId))(typeInformationFromNodeContext)
  }
}

/**
  * Support for test mechanism for typical flink sources.
  *
  * @tparam T - type of event that is generated by this source. This is needed to handle e.g. syntax suggestions in UI
  */
trait FlinkSourceTestSupport[T] extends FlinkIntermediateRawSource[T] with SourceTestSupport[T] { self: Source[T] =>

  //TODO: design better way of handling test data in generic FlinkSource
  //Probably we *still* want to use CollectionSource (and have some custom logic in parser if needed), but timestamps
  //have to be handled here for now
  def timestampAssignerForTest : Option[TimestampWatermarkHandler[T]]

}

/**
  * Typical source with methods specific for Flink, user has only to define Source
  *
  * @tparam T - type of event that is generated by this source. This is needed to handle e.g. syntax suggestions in UI
  */
trait BasicFlinkSource[T] extends FlinkSource[T] with FlinkIntermediateRawSource[T] {

  def flinkSourceFunction: SourceFunction[T]

  override def sourceStream(env: StreamExecutionEnvironment, flinkNodeContext: FlinkCustomNodeContext): DataStream[Context] = {
    prepareSourceStream(env, flinkNodeContext, flinkSourceFunction)
  }
}

//Serializable to make Flink happy, e.g. kafkaMocks.MockSourceFactory won't work properly otherwise
abstract class FlinkSourceFactory[T: ClassTag] extends SourceFactory[T] with Serializable {

  def clazz: Class[T] = classTag[T].runtimeClass.asInstanceOf[Class[T]]

}

object FlinkSourceFactory {

  def noParam[T: ClassTag](source: FlinkSource[T]): FlinkSourceFactory[T] =
    new NoParamSourceFactory[T](source)

  case class NoParamSourceFactory[T: ClassTag](source: FlinkSource[T]) extends FlinkSourceFactory[T] {
    @MethodToInvoke
    def create(): Source[T] = source
  }

}
