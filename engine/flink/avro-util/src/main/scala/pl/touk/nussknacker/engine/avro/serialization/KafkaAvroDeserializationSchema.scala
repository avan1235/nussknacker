package pl.touk.nussknacker.engine.avro.serialization

import org.apache.flink.streaming.connectors.kafka.KafkaDeserializationSchema

abstract class KafkaAvroDeserializationSchema[T] extends KafkaDeserializationSchema[T] {

  def useStringAsKey: Boolean

}