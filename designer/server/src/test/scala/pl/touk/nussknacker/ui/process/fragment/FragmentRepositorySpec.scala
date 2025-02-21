package pl.touk.nussknacker.ui.process.fragment

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pl.touk.nussknacker.engine.api.process.VersionId
import pl.touk.nussknacker.test.VeryPatientScalaFutures
import pl.touk.nussknacker.ui.api.helpers.{EspItTest, ProcessTestData, TestProcessingTypes}
import pl.touk.nussknacker.ui.process.marshall.ProcessConverter

import scala.language.higherKinds

class FragmentRepositorySpec extends AnyFlatSpec with ScalatestRouteTest with Matchers with BeforeAndAfterEach with EspItTest with VeryPatientScalaFutures {

  import pl.touk.nussknacker.ui.api.helpers.TestCategories._

  it should "fetches fragment by its version" in {
    val sampleFragment = ProcessConverter.toDisplayable(ProcessTestData.sampleFragment, TestProcessingTypes.Streaming, TestCat)
    val sampleFragment2 = ProcessConverter.toDisplayable(ProcessTestData.sampleFragment2, TestProcessingTypes.Streaming, TestCat)
    savefragment(sampleFragment) {
      status shouldEqual StatusCodes.OK
    }
    updateProcess(sampleFragment2) {
      status shouldEqual StatusCodes.OK
    }

    ProcessTestData.sampleFragment.metaData.id shouldBe ProcessTestData.sampleFragment2.metaData.id
    ProcessTestData.sampleFragment should not be ProcessTestData.sampleFragment2

    fragmentRepository.loadFragments() shouldBe Set(FragmentDetails(ProcessTestData.sampleFragment2, TestCat))
    val fragmentId = ProcessTestData.sampleFragment.metaData.id
    fragmentRepository.loadFragments(Map(fragmentId -> VersionId(1))) shouldBe Set(FragmentDetails(ProcessTestData.emptyFragment, TestCat))
    fragmentRepository.loadFragments(Map(fragmentId -> VersionId(2))) shouldBe Set(FragmentDetails(ProcessTestData.sampleFragment, TestCat))
    fragmentRepository.loadFragments(Map(fragmentId -> VersionId(3))) shouldBe Set(FragmentDetails(ProcessTestData.sampleFragment2, TestCat))
    fragmentRepository.loadFragments() shouldBe Set(FragmentDetails(ProcessTestData.sampleFragment2, TestCat))
  }

}
