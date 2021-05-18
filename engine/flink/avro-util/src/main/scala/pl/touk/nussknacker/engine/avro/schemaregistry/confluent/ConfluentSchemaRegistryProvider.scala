package pl.touk.nussknacker.engine.avro.schemaregistry.confluent

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import org.apache.avro.Schema
import pl.touk.nussknacker.engine.api.process.ProcessObjectDependencies
import pl.touk.nussknacker.engine.avro.schemaregistry.{SchemaRegistryError, SchemaRegistryProvider, SchemaRegistryUnsupportedTypeError}
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.client.{CachedConfluentSchemaRegistryClientFactory, ConfluentSchemaRegistryClient, ConfluentSchemaRegistryClientFactory}
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.formatter.ConfluentAvroToJsonFormatterFactory
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.serialization.jsonpayload.{ConfluentJsonPayloadSerializerFactory, ConfluentKeyValueKafkaJsonDeserializerFactory}
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.serialization.{ConfluentAvroSerializationSchemaFactory, ConfluentKeyValueKafkaAvroDeserializationFactory}
import pl.touk.nussknacker.engine.avro.serialization.{KafkaAvroDeserializationSchemaFactory, KafkaAvroSerializationSchemaFactory}
import pl.touk.nussknacker.engine.kafka.{KafkaConfig, RecordFormatterFactory}

class ConfluentSchemaRegistryProvider(schemaRegistryClientFactory: ConfluentSchemaRegistryClientFactory,
                                      val serializationSchemaFactory: KafkaAvroSerializationSchemaFactory,
                                      _deserializationSchemaFactory: Boolean => KafkaAvroDeserializationSchemaFactory,
                                      kafkaConfig: KafkaConfig) extends SchemaRegistryProvider {

  override def deserializationSchemaFactory(useStringAsKey: Boolean): KafkaAvroDeserializationSchemaFactory = _deserializationSchemaFactory(useStringAsKey)

  override def recordFormatterFactory: RecordFormatterFactory =
    new ConfluentAvroToJsonFormatterFactory(schemaRegistryClientFactory, kafkaConfig)

  override def createSchemaRegistryClient: ConfluentSchemaRegistryClient =
    schemaRegistryClientFactory.createSchemaRegistryClient(kafkaConfig)

  override def validateSchema(schema: Schema): ValidatedNel[SchemaRegistryError, Schema] =
  /* kafka-avro-serializer does not support Array at top level
  [https://github.com/confluentinc/schema-registry/issues/1298] */
    if (schema.getType == Schema.Type.ARRAY)
      Invalid(NonEmptyList.of(
        SchemaRegistryUnsupportedTypeError("Unsupported Avro type. Top level Arrays are not supported")))
    else
      Valid(schema)
}

object ConfluentSchemaRegistryProvider extends Serializable {

  def apply(processObjectDependencies: ProcessObjectDependencies): ConfluentSchemaRegistryProvider =
    ConfluentSchemaRegistryProvider(CachedConfluentSchemaRegistryClientFactory(), processObjectDependencies)

  def apply(schemaRegistryClientFactory: ConfluentSchemaRegistryClientFactory, processObjectDependencies: ProcessObjectDependencies): ConfluentSchemaRegistryProvider =
    avroPayload(
      schemaRegistryClientFactory,
      processObjectDependencies
    )

  def avroPayload(schemaRegistryClientFactory: ConfluentSchemaRegistryClientFactory,
                  processObjectDependencies: ProcessObjectDependencies): ConfluentSchemaRegistryProvider = {
    ConfluentSchemaRegistryProvider(
      schemaRegistryClientFactory,
      new ConfluentAvroSerializationSchemaFactory(schemaRegistryClientFactory),
      (useStringAsKey: Boolean) => new ConfluentKeyValueKafkaAvroDeserializationFactory(schemaRegistryClientFactory, useStringAsKey),
      processObjectDependencies
    )
  }

  def apply(schemaRegistryClientFactory: ConfluentSchemaRegistryClientFactory,
            serializationSchemaFactory: KafkaAvroSerializationSchemaFactory,
            deserializationSchemaFactory: Boolean => KafkaAvroDeserializationSchemaFactory,
            processObjectDependencies: ProcessObjectDependencies): ConfluentSchemaRegistryProvider = {
    val kafkaConfig = KafkaConfig.parseConfig(processObjectDependencies.config)
    new ConfluentSchemaRegistryProvider(
      schemaRegistryClientFactory,
      serializationSchemaFactory,
      deserializationSchemaFactory,
      kafkaConfig
    )
  }

  def jsonPayload(schemaRegistryClientFactory: ConfluentSchemaRegistryClientFactory,
                  processObjectDependencies: ProcessObjectDependencies,
                  formatKey: Boolean): ConfluentSchemaRegistryProvider = {
    ConfluentSchemaRegistryProvider(
      schemaRegistryClientFactory,
      new ConfluentJsonPayloadSerializerFactory(schemaRegistryClientFactory),
      (useStringAsKey: Boolean) => new ConfluentKeyValueKafkaJsonDeserializerFactory(schemaRegistryClientFactory, useStringAsKey),
      processObjectDependencies
    )
  }
}
