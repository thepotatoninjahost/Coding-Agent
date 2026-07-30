package com.codingagent.core

enum class RepairStage { COMPILE, TEST, DIAGNOSE, REPAIR, REVERT, COMPLETE, FAILED }

data class RepairAttempt(
    val attempt: Int,
    val stage: RepairStage,
    val report: VerificationReport,
    val diagnosis: String,
    val changes: List<ChangeRecord>,
    val changeSets: List<ChangeSet> = emptyList()
)

data class RepairCycleResult(
    val passed: Boolean,
    val report: VerificationReport,
    val attempts: List<RepairAttempt>,
    val reverted: Boolean,
    val rollback: RollbackResult? = null
)

class CompilerTestRepairCycle(
    private val workspace: ProjectWorkspace,
    private val config: AgentRuntimeConfig
) {
    fun run(
        plan: AgentPlan,
        _intake: TaskIntake,
        existingChangeSets: List<ChangeSet>,
        repair: (String, Int) -> ChangeSet
    ): RepairCycleResult {
        val attempts = mutableListOf<RepairAttempt>()
        var report = execute(plan)
        attempts += RepairAttempt(0, classify(report), report, diagnose(report), emptyList())
        if (report.passed) return RepairCycleResult(true, report, attempts, false)
        var attempt = 1
        while (attempt <= config.maxRepairAttempts) {
            val diagnosis = diagnose(report)
            val changeSet = runCatching { repair(diagnosis, attempt) }.getOrElse {
                attempts += RepairAttempt(attempt, RepairStage.FAILED, report, "Repair synthesis failed: ${it.message}", emptyList())
                return revertAndFail(attempts, report, existingChangeSets)
            }
            if (changeSet.changes.isEmpty()) {
                attempts += RepairAttempt(attempt, RepairStage.FAILED, report, "No repair was produced for: $diagnosis", emptyList())
                return revertAndFail(attempts, report, existingChangeSets)
            }
            report = execute(plan)
            attempts += RepairAttempt(attempt, classify(report), report, diagnosis, changeSet.changes, listOf(changeSet))
            if (report.passed) return RepairCycleResult(true, report, attempts, false)
            attempt++
        }
        return revertAndFail(attempts, report, existingChangeSets)
    }

    private fun execute(plan: AgentPlan): VerificationReport =
        if (plan.checks.isEmpty()) workspace.verify() else workspace.runChecks(plan.checks, config.commandTimeoutSeconds)

    private fun classify(report: VerificationReport): RepairStage = when {
        report.passed -> RepairStage.COMPLETE
        report.commands.any { it.command.contains("test") && it.exitCode != 0 } -> RepairStage.TEST
        report.commands.any { it.exitCode != 0 } -> RepairStage.COMPILE
        else -> RepairStage.DIAGNOSE
    }

    private fun diagnose(report: VerificationReport): String = report.issues
        .joinToString("; ") { "${it.path}:${it.line}: ${it.message}" }
        .ifBlank { "Verification failed without diagnostics" }

    private fun revertAndFail(
        attempts: MutableList<RepairAttempt>,
        report: VerificationReport,
        changeSets: List<ChangeSet>
    ): RepairCycleResult {
        val rollback = if (changeSets.isEmpty()) RollbackResult.Restored else workspace.rollback(changeSets)
        val reverted = rollback == RollbackResult.Restored
        attempts += RepairAttempt(
            attempts.size,
            if (reverted) RepairStage.REVERT else RepairStage.FAILED,
            report,
            if (reverted) "Unrepaired changes reverted" else "Revert was incomplete: $rollback",
            emptyList()
        )
        return RepairCycleResult(false, report, attempts, reverted, rollback)
    }
}
