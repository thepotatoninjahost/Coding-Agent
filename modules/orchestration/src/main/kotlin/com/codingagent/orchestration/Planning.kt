package com.codingagent.orchestration

data class PlanInput(val request: String, val phases: List<Pair<String, String>>, val verificationCommands: List<List<String>> = emptyList())
enum class PlanStepStatus { PENDING, ACTIVE, COMPLETE, FAILED, BLOCKED }
data class PlannedStep(val id: String, val phase: String, val detail: String, val dependsOn: List<String> = emptyList(), val status: PlanStepStatus = PlanStepStatus.PENDING, val evidence: String = "")
data class PlanSnapshot(val revision: Int, val iteration: Int, val status: String, val reason: String, val steps: List<PlannedStep>)

class PlanningLoop(phases: List<Pair<String, String>>, private val maxIterations: Int = 32, private val maxReplans: Int = 3) {
    init { require(phases.isNotEmpty()) { "A plan needs at least one phase" } }
    private val steps = phases.mapIndexed { index, (phase, detail) -> PlannedStep("${index + 1}-$phase", phase, detail, if (index == 0) emptyList() else listOf("${index}-${phases[index - 1].first}")) }.toMutableList()
    private val snapshots = mutableListOf<PlanSnapshot>()
    private var activeId: String? = null
    private var iteration = 0
    private var revision = 0
    private var replans = 0
    private var status = "running"
    private var reason = "plan initialized"
    init { snapshot() }

    @Synchronized fun next(): PlannedStep? {
        if (status != "running" || activeId != null) return null
        if (iteration >= maxIterations) return stop("iteration-limit", "maximum planning iterations reached")
        val candidate = steps.firstOrNull { it.status == PlanStepStatus.PENDING && it.dependsOn.all { dep -> steps.firstOrNull { step -> step.id == dep }?.status == PlanStepStatus.COMPLETE } }
        if (candidate == null) {
            val finalStatus = when { steps.all { it.status == PlanStepStatus.COMPLETE } -> "complete"; steps.any { it.status == PlanStepStatus.FAILED || it.status == PlanStepStatus.BLOCKED } -> "failed"; else -> "blocked" }
            return stop(finalStatus, "no executable planning step remains")
        }
        iteration++
        activeId = candidate.id
        replace(candidate.copy(status = PlanStepStatus.ACTIVE))
        reason = "executing ${candidate.id}"
        snapshot()
        return steps.first { it.id == candidate.id }
    }

    @Synchronized fun complete(evidence: String = "") {
        val id = activeId ?: error("No active planning step")
        replace(steps.first { it.id == id }.copy(status = PlanStepStatus.COMPLETE, evidence = evidence))
        activeId = null
        reason = "completed $id"
        snapshot()
    }

    @Synchronized fun fail(message: String, replan: Boolean = true): Boolean {
        val id = activeId ?: error("No active planning step")
        replace(steps.first { it.id == id }.copy(status = PlanStepStatus.FAILED, evidence = message))
        activeId = null
        reason = "$id failed: $message"
        if (replan && replans < maxReplans) {
            replans++
            revision++
            val diagnosis = "r$revision-diagnose"
            val recovery = "r$revision-recover"
            val completed = steps.filter { it.status == PlanStepStatus.COMPLETE }.map { it.id }
            steps += PlannedStep(diagnosis, "diagnose", "Analyze failure: $message", completed)
            steps += PlannedStep(recovery, "recover", "Re-plan unfinished work after diagnosis", listOf(diagnosis))
            status = "running"
            reason = "replanned after $id failure"
            snapshot()
            return true
        }
        status = "failed"
        snapshot()
        return false
    }

    @Synchronized fun finishIfReady(): Boolean {
        if (status == "running" && activeId == null && steps.all { it.status == PlanStepStatus.COMPLETE }) { status = "complete"; reason = "all planned steps completed"; snapshot() }
        return status == "complete"
    }
    fun currentStatus(): String = status
    fun history(): List<PlanSnapshot> = snapshots.toList()
    fun currentSteps(): List<PlannedStep> = steps.toList()
    private fun replace(updated: PlannedStep) { steps[steps.indexOfFirst { it.id == updated.id }] = updated }
    private fun stop(newStatus: String, newReason: String): PlannedStep? { status = newStatus; reason = newReason; snapshot(); return null }
    private fun snapshot() { snapshots += PlanSnapshot(revision, iteration, status, reason, steps.toList()) }
}
