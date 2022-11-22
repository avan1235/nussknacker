package pl.touk.nussknacker.engine.json

import org.everit.json.schema.Schema
import org.everit.json.schema.loader.SchemaLoader
import org.json.JSONObject

object JsonSchemaBuilder {

  def parseSchema(rawJsonSchema: String): Schema =
    parseSchema(rawJsonSchema, useDefaults = true)

  //TODO: parsing schema should be consistent with [io.confluent.kafka.schemaregistry.json.JsonSchema.rawSchema]
  def parseSchema(rawJsonSchema: String, useDefaults: Boolean): Schema = {
    val rawSchema: JSONObject = new JSONObject(rawJsonSchema)

    SchemaLoader
      .builder()
      .useDefaults(useDefaults)
      .schemaJson(rawSchema)
      .draftV7Support()
      .build()
      .load()
      .build()
      .asInstanceOf[Schema]
  }

}
