package pl.touk.nussknacker.engine.avro.serialization

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import pl.touk.nussknacker.engine.avro.RuntimeSchemaData
import pl.touk.nussknacker.engine.kafka.KafkaConfig

import scala.reflect.{ClassTag, classTag}

/**
  * Factory class for Flink's KeyedDeserializationSchema. It is extracted for purpose when for creation
  * of KeyedDeserializationSchema are needed additional avro related information.
  */
trait KafkaAvroDeserializationSchemaFactory extends Serializable {

  /**
    * Prepare Flink's KafkaDeserializationSchema based on provided information.
    *
    * @param kafkaConfig        Configuration of integration with Kafka.
    * @param keySchemaDataOpt   Schema which will be used as a key reader schema.
    * @param valueSchemaDataOpt Schema which will be used as a value reader schema. In case of None, writer schema will be used.
    * @tparam K Type that should be produced by key deserialization schema.
    * @tparam V Type that should be produced by value deserialization schema. It is important parameter, because factory can
    *           use other deserialization strategy base on it or provide different TypeInformation
    * @return KafkaDeserializationSchema
    */
  def create[K: ClassTag, V: ClassTag](kafkaConfig: KafkaConfig,
                                       keySchemaDataOpt: Option[RuntimeSchemaData],
                                       valueSchemaDataOpt: Option[RuntimeSchemaData]
                                      ): KafkaAvroDeserializationSchema[ConsumerRecord[K, V]]

}

/**
  * Abstract base implementation of [[KafkaAvroDeserializationSchemaFactory]]
  * which uses Kafka's Deserializer in returned Flink's KeyedDeserializationSchema. It deserializes both key and value
  * and wrap it in ConsumerRecord object (transforms raw event represented as ConsumerRecord from Array[Byte] domain to Key-Value-type domain).
  */
abstract class KafkaAvroKeyValueDeserializationSchemaFactory(_useStringAsKey: Boolean)
  extends KafkaAvroDeserializationSchemaFactory {

  protected def createKeyDeserializer[K: ClassTag](schemaDataOpt: Option[RuntimeSchemaData], kafkaConfig: KafkaConfig): Deserializer[K]

  protected def createKeyTypeInfo[K: ClassTag](schemaDataOpt: Option[RuntimeSchemaData], kafkaConfig: KafkaConfig): TypeInformation[K]

  protected def createValueDeserializer[V: ClassTag](schemaDataOpt: Option[RuntimeSchemaData], kafkaConfig: KafkaConfig): Deserializer[V]

  protected def createValueTypeInfo[V: ClassTag](schemaDataOpt: Option[RuntimeSchemaData], kafkaConfig: KafkaConfig): TypeInformation[V]

  override def create[K: ClassTag, V: ClassTag](kafkaConfig: KafkaConfig,
                                                keySchemaDataOpt: Option[RuntimeSchemaData],
                                                valueSchemaDataOpt: Option[RuntimeSchemaData]
                                               ): KafkaAvroDeserializationSchema[ConsumerRecord[K, V]] = {

    new KafkaAvroDeserializationSchema[ConsumerRecord[K, V]] {


      override def useStringAsKey: Boolean = _useStringAsKey

      @transient
      private lazy val keyDeserializer = if (useStringAsKey) {
        {new StringDeserializer}.asInstanceOf[Deserializer[K]]
      } else {
        createKeyDeserializer[K](keySchemaDataOpt, kafkaConfig)
      }
      @transient
      private lazy val valueDeserializer = createValueDeserializer[V](valueSchemaDataOpt, kafkaConfig)

      override def deserialize(record: ConsumerRecord[Array[Byte], Array[Byte]]): ConsumerRecord[K, V] = {
        val key = keyDeserializer.deserialize(record.topic(), record.key())
        val value = valueDeserializer.deserialize(record.topic(), record.value())
        new ConsumerRecord[K, V](
          record.topic(),
          record.partition(),
          record.offset(),
          record.timestamp(),
          record.timestampType(),
          ConsumerRecord.NULL_CHECKSUM.toLong, // ignore deprecated checksum
          record.serializedKeySize(),
          record.serializedValueSize(),
          key,
          value,
          record.headers(),
          record.leaderEpoch()
        )
      }

      override def isEndOfStream(nextElement: ConsumerRecord[K, V]): Boolean = false

      override def getProducedType: TypeInformation[ConsumerRecord[K, V]] = {
        if (useStringAsKey) {
          val clazz = classTag[ConsumerRecord[String, V]].runtimeClass.asInstanceOf[Class[ConsumerRecord[K, V]]]
          TypeInformation.of(clazz)
        } else {
          val clazz = classTag[ConsumerRecord[K, V]].runtimeClass.asInstanceOf[Class[ConsumerRecord[K, V]]]
          TypeInformation.of(clazz)
        }
      }

    }
  }

}
