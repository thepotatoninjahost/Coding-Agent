package com.codingagent.agent
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentStep

/**
 * ONE JOB: Advance and track ordered plan steps through execution.
 */
enum class PlanStepStatus { PENDING, ACTIVE, COMPLETE, FAILED, BLOCKED }

data class PlannedStep(
    val id: String,
    val phase: String,
    val detail: String,
    val dependsOn: List<String> = emptyList(),
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val evidence: String = ""
)

data class PlanSnapshot(
    val revision: Int,
    val iteration: Int,
    val status: String,
    val reason: String,
    val steps: List<PlannedStep>
)

class PlanningLoop(
    plan: AgentPlan,
    private val maxIterations: Int = 32,
    private val maxReplans: Int = 3
) {
    private val steps = plan.steps.mapIndexed { index, step ->
        PlannedStep(
            id = "${index + 1}-${step.phase}",
            phase = step.phase,
            detail = step.detail,
            dependsOn = if (index == 0) emptyList() else listOf("${index}-${plan.steps[index - 1].phase}")
        )
    }.toMutableList()
    private val snapshots = mutableListOf<PlanSnapshot>()
    private var activeId: String? = null
    private var iteration = 0
    private var revision = 0
    private var replans = 0
    private var status = "running"
    private var reason = "plan initialized"

    init { snapshot() }

    @Synchronized
    fun authorizeTool(toolName: String, toolKind: ToolKind): String? {
        val completed = steps.filter { it.status == PlanStepStatus.COMPLETE }.map { it.phase }.toSet()
        return when (toolKind) {
            ToolKind.SEARCH_PROJECT ->
                if ("understand" in completed) null else "Tool " + toolName + " is blocked until repository understanding is established"
            ToolKind.SEARCH_KNOWLEDGE ->
                if ("understand" in completed) null else "Tool " + toolName + " is blocked until repository understanding is established"
            ToolKind.APPLY_CHANGES -> {
                val evidenceReady = "understand" in completed &&
                    ("target" in completed || "scope" in completed || "inspect" in completed)
                if (!evidenceReady) {
                    "Tool " + toolName + " is blocked until the target and project evidence are established"
                } else {
                    completePhase("change", "model produced a concrete mutation proposal")
                    null
                }
            }
            ToolKind.RUN_CHECKS ->
                if ("change" in completed || "inspect" in completed || "verify" in completed) null
                else "Tool " + toolName + " is blocked until the work reaches verification"
            ToolKind.VERIFY ->
                if ("change" in completed || "inspect" in completed || "verify" in completed) null
                else "Tool " + toolName + " is blocked until the task reaches verification"
            else -> null
        }
    }

    @Synchronized
    fun recordSuccess(toolName: String, toolKind: ToolKind, evidence: String = "") {
        when (toolKind) {
            ToolKind.SEARCH_PROJECT -> {
                completePhase("understand", evidence)
                steps.filter { it.phase == "target" || it.phase == "scope" }.forEach { completePhase(it.phase, evidence) }
                if (steps.none { it.phase == "target" || it.phase == "scope" }) completePhase("inspect", evidence)
            }
            ToolKind.SEARCH_KNOWLEDGE -> completePhase("research", evidence)
            ToolKind.APPLY_CHANGES -> completePhase("change", evidence)
            ToolKind.RUN_CHECKS, ToolKind.VERIFY -> completePhase("verify", evidence)
            else -> Unit
        }
    }

    @Synchronized
    fun recordFailure(toolName: String, toolKind: ToolKind, message: String) {
        val phase = when (toolKind) {
            ToolKind.SEARCH_PROJECT -> listOf("understand", "target", "scope", "inspect")
            ToolKind.SEARCH_KNOWLEDGE -> listOf("research", "constraints")
            ToolKind.APPLY_CHANGES -> listOf("change")
            ToolKind.RUN_CHECKS, ToolKind.VERIFY -> listOf("verify")
            else -> emptyList()
        }.firstOrNull { candidate ->
            steps.any { it.phase == candidate && it.status != PlanStepStatus.COMPLETE }
        }
        if (phase != null) {
            val step = steps.first { it.phase == phase }
            replace(step.copy(status = PlanStepStatus.PENDING, evidence = toolName + ": " + message))
            reason = "recoverable failure in " + phase + ": " + message
            snapshot()
        }
    }

    @Synchronized
    fun completePhase(phase: String, evidence: String = "") {
        val step = steps.firstOrNull { it.phase == phase && it.status != PlanStepStatus.COMPLETE } ?: return
        replace(step.copy(status = PlanStepStatus.COMPLETE, evidence = evidence))
        activeId = null
        reason = "completed " + phase
        snapshot()
    }

    @Synchronized
    fun next(): PlannedStep? {
        if (status != "running") return null
        if (iteration >= maxIterations) {
            status = "iteration-limit"
            reason = "maximum planning iterations reached"
            snapshot()
            return null
        }
        val candidate = steps.firstOrNull { step ->
            step.status == PlanStepStatus.PENDING && step.dependsOn.all { dependency ->
                steps.firstOrNull { it.id == dependency }?.status == PlanStepStatus.COMPLETE
            }
        }
        if (candidate == null) {
            if (steps.all { it.status == PlanStepStatus.COMPLETE }) {
                status = "complete"
                reason = "all planned steps completed"
            } else if (steps.any { it.status == PlanStepStatus.FAILED || it.status == PlanStepStatus.BLOCKED }) {
                status = "failed"
                reason = "a planned step failed or was blocked"
            } else {
                status = "blocked"
                reason = "no executable step is available"
            }
            snapshot()
            return null
        }
        iteration++
        activeId = candidate.id
        replace(candidate.copy(status = PlanStepStatus.ACTIVE))
        reason = "executing ${candidate.id}"
        snapshot()
        return steps.first { it.id == candidate.id }
    }

    @Synchronized
    fun complete(evidence: String = "") {
        val id = activeId ?: error("No active planning step")
        val step = steps.first { it.id == id }
        replace(step.copy(status = PlanStepStatus.COMPLETE, evidence = evidence))
        activeId = null
        reason = "completed $id"
        snapshot()
    }

    @Synchronized
    fun fail(message: String, replan: Boolean = true): Boolean {
        val id = activeId ?: error("No active planning step")
        val step = steps.first { it.id == id }
        replace(step.copy(status = PlanStepStatus.FAILED, evidence = message))
        activeId = null
        reason = "$id failed: $message"
        if (replan && replans < maxReplans) {
            replans++
            revision++
            val diagnosisId = "r$revision-diagnose"
            val recoveryId = "r$revision-recover"
            val completed = steps.filter { it.status == PlanStepStatus.COMPLETE }.map { it.id }
            steps += PlannedStep(diagnosisId, "diagnose", "Analyze failure: $message", completed)
            steps += PlannedStep(recoveryId, "recover", "Re-plan the unfinished work after diagnosis", listOf(diagnosisId))
            status = "running"
            reason = "replanned after $id failure"
            snapshot()
            return true
        }
        status = "failed"
        snapshot()
        return false
    }

    @Synchronized
    fun finishIfReady(): Boolean {
        if (status == "running" && steps.all { it.status == PlanStepStatus.COMPLETE }) {
            status = "complete"
            reason = "all planned steps completed"
            snapshot()
        }
        return status == "complete"
    }

    fun currentStatus(): String = status
    fun history(): List<PlanSnapshot> = snapshots.toList()
    fun currentSteps(): List<PlannedStep> = steps.toList()

    private fun replace(updated: PlannedStep) {
        val index = steps.indexOfFirst { it.id == updated.id }
        steps[index] = updated
    }

    private fun snapshot() {
        snapshots += PlanSnapshot(revision, iteration, status, reason, steps.toList())
    }
}
