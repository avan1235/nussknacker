package pl.touk.nussknacker.engine.management.sample.modelconfig

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueFactory._
import net.ceedubs.ficus.Ficus._
import pl.touk.nussknacker.engine.component.ComponentExtractor
import pl.touk.nussknacker.engine.modelconfig.{InputConfigDuringExecution, ModelConfigLoader}

class SampleModelConfigLoader extends ModelConfigLoader {

  override def resolveInputConfigDuringExecution(inputConfig: Config, configWithDefaults: Config, classLoader: ClassLoader): InputConfigDuringExecution = {
    val withExtractors = ComponentExtractor(classLoader).loadAdditionalConfig(inputConfig, configWithDefaults)
    val valueFromOriginalConfig = configWithDefaults.getAs[String]("configValueToLoadFrom").getOrElse("notFound")
    InputConfigDuringExecution(
      withExtractors
        .withValue("configLoadedMs", fromAnyRef(System.currentTimeMillis()))
        .withValue("configValueToLoad", fromAnyRef(valueFromOriginalConfig))
    )
  }

}
