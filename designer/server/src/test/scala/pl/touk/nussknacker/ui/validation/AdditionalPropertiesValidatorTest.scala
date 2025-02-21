package pl.touk.nussknacker.ui.validation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.{ProcessAdditionalFields, StreamMetaData}
import pl.touk.nussknacker.engine.api.definition.{FixedExpressionValue, FixedValuesParameterEditor, FixedValuesValidator, LiteralParameterValidator, MandatoryParameterValidator, RegExpParameterValidator, StringParameterEditor}
import pl.touk.nussknacker.engine.api.component.AdditionalPropertyConfig
import pl.touk.nussknacker.restmodel.validation.ValidationResults.{NodeValidationError, NodeValidationErrorType}
import pl.touk.nussknacker.ui.api.helpers.{ProcessTestData, TestFactory}

class AdditionalPropertiesValidatorTest extends AnyFunSuite with Matchers {
  private val reqFieldName = "propReq"
  private val regexpFieldName = "propRegExp"
  private val optionalFieldName = "propOpt"
  private val optFixedFieldName = "propOptFixed"
  private val possibleValues = List(FixedExpressionValue("a", "a"), FixedExpressionValue("b", "b"))
  private val label = "foo"

  private val validator = new AdditionalPropertiesValidator(
    TestFactory.mapProcessingTypeDataProvider(
      "streaming" -> Map(
        reqFieldName -> AdditionalPropertyConfig(
          None,
          None,
          Some(List(LiteralParameterValidator.integerValidator, MandatoryParameterValidator)),
          Some(label)),
        regexpFieldName -> AdditionalPropertyConfig(
          None,
          None,
          Some(List(LiteralParameterValidator.numberValidator)),
          Some(label)),
        optionalFieldName -> AdditionalPropertyConfig(
          None,
          Some(StringParameterEditor),
          None,
          Some(label)),
        optFixedFieldName -> AdditionalPropertyConfig(
          None,
          Some(FixedValuesParameterEditor(possibleValues)),
          Some(List(FixedValuesValidator(possibleValues))),
          Some(label)
        )
      ))
  )

  test("validate non empty config with required property") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propReq" -> "5"
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors shouldBe List.empty
  }

  test("validate non empty config without required property") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propOpt" -> "a"
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(NodeValidationError("MissingRequiredProperty", _, _, Some(reqFieldName), NodeValidationErrorType.SaveAllowed)) =>
    }
  }

  test("validate non empty config with empty required property") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propReq" -> ""
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(
        NodeValidationError("EmptyMandatoryParameter", _, _, Some("propReq"), NodeValidationErrorType.SaveAllowed)
      ) =>
    }
  }

  test("validate regexp config with empty property") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propReq" -> "1",
        "propRegExp" -> ""
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List() =>
    }
  }

  test("validate config with invalid property") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propReq" -> "1",
        "propRegExp" -> "asd"
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(NodeValidationError("MismatchParameter", _, _, Some("propRegExp"),  NodeValidationErrorType.SaveAllowed)) =>
    }
  }

  test("validate non empty config with required property with wrong type") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propReq" -> "some text"
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(NodeValidationError("InvalidIntegerLiteralParameter", _, _, Some("propReq"), NodeValidationErrorType.SaveAllowed)) =>
    }
  }

  test("validate empty config") {
    val process = ProcessTestData.displayableWithAdditionalFields(None)

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(NodeValidationError("MissingRequiredProperty", _, _, Some(reqFieldName), NodeValidationErrorType.SaveAllowed)) =>
    }
  }

  test("validate non empty config with fixed value property with wrong value") {
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        optFixedFieldName -> "some text"
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(
      NodeValidationError("InvalidPropertyFixedValue", _, _, Some(optFixedFieldName), NodeValidationErrorType.SaveAllowed),
      NodeValidationError("MissingRequiredProperty", _, _, Some(reqFieldName), NodeValidationErrorType.SaveAllowed)
      ) =>
    }
  }

  test("validate non empty config with unknown property") {
    val unknownProperty = "unknown"
    val process = ProcessTestData.displayableWithAdditionalFields(Some(
      ProcessAdditionalFields(None, properties = Map(
        "propReq" -> "5",
        "unknown" -> "some text"
      ), StreamMetaData.typeName)
    ))

    val result = validator.validate(process)

    result.errors.processPropertiesErrors should matchPattern {
      case List(NodeValidationError("UnknownProperty", _, _, Some(unknownProperty), NodeValidationErrorType.SaveAllowed)) =>
    }
  }
}
