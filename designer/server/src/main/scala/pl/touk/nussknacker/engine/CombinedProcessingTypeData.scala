package pl.touk.nussknacker.engine

import pl.touk.nussknacker.restmodel.process.ProcessingType
import pl.touk.nussknacker.ui.component.{ComponentIdProvider, DefaultComponentIdProvider}
import pl.touk.nussknacker.ui.process.{ProcessCategoryService, ProcessStateDefinitionService}
import pl.touk.nussknacker.ui.process.ProcessStateDefinitionService.StatusNameToStateDefinitionsMapping

case class CombinedProcessingTypeData(statusNameToStateDefinitionsMapping: StatusNameToStateDefinitionsMapping,
                                      componentIdProvider: ComponentIdProvider,
                                     )

object CombinedProcessingTypeData {

  def create(processingTypes: Map[ProcessingType, ProcessingTypeData],
             categoryService: ProcessCategoryService): CombinedProcessingTypeData = {
    CombinedProcessingTypeData(
      statusNameToStateDefinitionsMapping = ProcessStateDefinitionService.createDefinitionsMappingUnsafe(processingTypes),
      // While creation of component id provider, we validate all component ids but fragments.
      // We assume that fragments cannot have overridden component id thus are not merged/deduplicated across processing types.
      componentIdProvider = DefaultComponentIdProvider.createUnsafe(processingTypes, categoryService)
    )
  }

}
