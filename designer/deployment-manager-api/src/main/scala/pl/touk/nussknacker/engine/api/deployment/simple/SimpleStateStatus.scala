package pl.touk.nussknacker.engine.api.deployment.simple

import pl.touk.nussknacker.engine.api.deployment
import pl.touk.nussknacker.engine.api.deployment.ProcessActionType.ProcessActionType
import pl.touk.nussknacker.engine.api.deployment.StateStatus.StatusName
import pl.touk.nussknacker.engine.api.deployment.simple.SimpleStateStatus.ProblemStateStatus.defaultActions
import pl.touk.nussknacker.engine.api.deployment.{ProcessActionType, StateDefinitionDetails, StateStatus}
import pl.touk.nussknacker.engine.api.process.VersionId

import java.net.URI

object SimpleStateStatus {

  // Represents general problem.
  case class ProblemStateStatus(description: String, allowedActions: List[ProcessActionType] = defaultActions) extends StateStatus {
    override def name: StatusName = ProblemStateStatus.name
  }

  object ProblemStateStatus {
    val name: String = "PROBLEM"

    def isProblemStatus(status: StateStatus): Boolean = status.name == name

    val icon: URI = URI.create("/assets/states/error.svg")
    val defaultDescription = "There are some problems with scenario."
    val defaultActions: List[deployment.ProcessActionType.Value] = List(ProcessActionType.Deploy, ProcessActionType.Cancel)

    // Problem factory methods

    val Failed: ProblemStateStatus = ProblemStateStatus(defaultDescription)

    val ArchivedShouldBeCanceled: ProblemStateStatus = ProblemStateStatus("Archived scenario should be canceled.", List(ProcessActionType.Cancel))

    val FailedToGet: ProblemStateStatus =
      ProblemStateStatus(s"Failed to get a state of the scenario.")

    def shouldBeRunning(deployedVersionId: VersionId, user: String): ProblemStateStatus =
      ProblemStateStatus(s"Scenario deployed in version $deployedVersionId by $user is not running.")

    def mismatchDeployedVersion(deployedVersionId: VersionId, exceptedVersionId: VersionId, user: String): ProblemStateStatus =
      ProblemStateStatus(s"Scenario deployed in version $deployedVersionId by $user, expected version $exceptedVersionId.")

    def shouldNotBeRunning(deployed: Boolean): ProblemStateStatus = {
      val shouldNotBeRunningMessage = if (deployed) "Scenario has been canceled but still is running."
      else "Scenario has been never deployed but now is running."
      ProblemStateStatus(shouldNotBeRunningMessage)
    }

    def missingDeployedVersion(exceptedVersionId: VersionId, user: String): ProblemStateStatus =
      ProblemStateStatus(s"Scenario deployed without version by $user, expected version $exceptedVersionId.")

    val ProcessWithoutAction: ProblemStateStatus =
      ProblemStateStatus("Scenario state error - no actions found.")

    val MultipleJobsRunning: ProblemStateStatus =
      ProblemStateStatus("More than one deployment is running.", List(ProcessActionType.Cancel))

  }

  val NotDeployed: StateStatus = StateStatus("NOT_DEPLOYED")
  val DuringDeploy: StateStatus = StateStatus("DURING_DEPLOY")
  val Running: StateStatus = StateStatus("RUNNING")
  val Finished: StateStatus = StateStatus("FINISHED")
  val Restarting: StateStatus = StateStatus("RESTARTING")
  val DuringCancel: StateStatus = StateStatus("DURING_CANCEL")
  val Canceled: StateStatus = StateStatus("CANCELED")

  val DefaultFollowingDeployStatuses: Set[StateStatus] = Set(DuringDeploy, Running)
  def isFinalStatus(status: StateStatus): Boolean =
    List(SimpleStateStatus.Finished, SimpleStateStatus.Canceled).contains(status) || ProblemStateStatus.isProblemStatus(status)

  val statusActionsPF: PartialFunction[StateStatus, List[ProcessActionType]] = {
    case SimpleStateStatus.NotDeployed => List(ProcessActionType.Deploy, ProcessActionType.Archive, ProcessActionType.Rename)
    case SimpleStateStatus.DuringDeploy => List(ProcessActionType.Deploy, ProcessActionType.Cancel)
    case SimpleStateStatus.Running => List(ProcessActionType.Cancel, ProcessActionType.Pause, ProcessActionType.Deploy)
    case SimpleStateStatus.Canceled => List(ProcessActionType.Deploy, ProcessActionType.Archive, ProcessActionType.Rename)
    case SimpleStateStatus.Restarting => List(ProcessActionType.Deploy, ProcessActionType.Cancel)
    case SimpleStateStatus.Finished => List(ProcessActionType.Deploy, ProcessActionType.Archive, ProcessActionType.Rename)
    case SimpleStateStatus.DuringCancel => List(ProcessActionType.Deploy, ProcessActionType.Cancel)
    // When Failed - process is in terminal state in Flink and it doesn't require any cleanup in Flink, but in NK it does
    // - that's why Cancel action is available
    case SimpleStateStatus.ProblemStateStatus(_, allowedActions) => allowedActions
  }

  val definitions: Map[StatusName, StateDefinitionDetails] = Map(
    SimpleStateStatus.ProblemStateStatus.name -> StateDefinitionDetails(
      displayableName = "Problem",
      icon = ProblemStateStatus.icon,
      tooltip = ProblemStateStatus.defaultDescription,
      description = ProblemStateStatus.defaultDescription
    ),
    SimpleStateStatus.NotDeployed.name -> StateDefinitionDetails(
      displayableName = "Not deployed",
      icon = URI.create("/assets/states/not-deployed.svg"),
      tooltip = "The scenario is not deployed.",
      description = "The scenario is not deployed."
    ),
    SimpleStateStatus.DuringDeploy.name -> StateDefinitionDetails(
      displayableName = "During deploy",
      icon = URI.create("/assets/states/deploy-running-animated.svg"),
      tooltip = "The scenario has been already started and currently is being deployed.",
      description = "The scenario is being deployed."
    ),
    SimpleStateStatus.Running.name -> StateDefinitionDetails(
      displayableName = "Running",
      icon = URI.create("/assets/states/deploy-success.svg"),
      tooltip = "The scenario has been successfully deployed and currently is running.",
      description = "The scenario is running."
    ),
    SimpleStateStatus.Canceled.name -> StateDefinitionDetails(
      displayableName = "Canceled",
      icon = URI.create("/assets/states/stopping-success.svg"),
      tooltip = "The scenario has been successfully cancelled.",
      description = "The scenario is canceled."
    ),
    SimpleStateStatus.Restarting.name -> StateDefinitionDetails(
      displayableName = "Restarting",
      icon = URI.create("/assets/states/deploy-restart-animated.svg"),
      tooltip = "Scenario was deployed but now is restarting...",
      description = "Scenario is restarting..."
    ),
    SimpleStateStatus.DuringCancel.name -> StateDefinitionDetails(
      displayableName = "During cancel",
      icon = URI.create("/assets/states/stopping-running-animated.svg"),
      tooltip = "The scenario currently is being canceled.",
      description = "The scenario is being canceled."
    ),
    SimpleStateStatus.Finished.name -> StateDefinitionDetails(
      displayableName = "Finished",
      icon = URI.create("/assets/states/success.svg"),
      tooltip = "The scenario completed successfully.",
      description = "The scenario has finished."
    ),
  )
}
