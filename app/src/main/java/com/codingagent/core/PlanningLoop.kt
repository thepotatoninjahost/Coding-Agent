package com.codingagent.core

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
