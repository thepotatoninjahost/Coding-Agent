package com.codingagent.agent
import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Intake → ordered tool plan for the offline/runtime path.
 */
enum class ToolKind {
    INDEX_REPOSITORY,
    SEARCH_PROJECT,
    SEARCH_KNOWLEDGE,
    SYNTHESIZE_CODE,
    APPLY_CHANGES,
    RUN_CHECKS,
    VERIFY,
    RECORD_LESSON
}

enum class ToolStepStatus { PENDING, ACTIVE, COMPLETE, FAILED, BLOCKED }

data class ToolInvocation(
    val id: String,
    val kind: ToolKind,
    val purpose: String,
    val dependsOn: List<String> = emptyList(),
    val status: ToolStepStatus = ToolStepStatus.PENDING,
    val evidence: String = ""
)

data class ToolSelectionPlan(
    val request: String,
    val tools: List<ToolInvocation>,
    val rationale: String
)

data class ToolLoopSnapshot(
    val iteration: Int,
    val status: String,
    val reason: String,
    val tools: List<ToolInvocation>
)

class ToolSelector {
    fun select(intake: TaskIntake): ToolSelectionPlan {
        val tools = mutableListOf<ToolInvocation>()
        fun add(kind: ToolKind, purpose: String, dependencies: List<String> = emptyList()): String {
            val id = (tools.size + 1).toString() + "-" + kind.name.lowercase()
            tools += ToolInvocation(id, kind, purpose, dependencies)
            return id
        }

        val indexId = add(ToolKind.INDEX_REPOSITORY, "Build the repository file, symbol, import, and checksum view")
        val needsProjectEvidence =
            intake.contract.targetPaths.isNotEmpty() ||
                intake.contract.targetSymbols.isNotEmpty() ||
                intake.intent in setOf(TaskIntent.INSPECT, TaskIntent.DEBUG, TaskIntent.REFACTOR, TaskIntent.CHANGE, TaskIntent.CREATE)
        val searchId = if (needsProjectEvidence) {
            add(ToolKind.SEARCH_PROJECT, "Locate target files, symbols, and relevant project evidence", listOf(indexId))
        } else null
        add(ToolKind.SEARCH_KNOWLEDGE, "Retrieve relevant local coding references and lessons", listOf(indexId))
        val changeWork = intake.intent in setOf(TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG)
        val synthesisId = if (changeWork) {
            add(ToolKind.SYNTHESIZE_CODE, "Produce a structured, testable change proposal", listOf(searchId ?: indexId))
        } else null
        val applyId = if (changeWork) {
            add(ToolKind.APPLY_CHANGES, "Stage the selected proposal through the workspace mutation API", listOf(synthesisId!!))
        } else null
        val checksId = if (intake.verificationCommands.isNotEmpty()) {
            add(ToolKind.RUN_CHECKS, "Run the project checks selected during intake", listOf(if (changeWork) applyId!! else searchId ?: indexId))
        } else null
        val verifyId = add(
            ToolKind.VERIFY,
            "Run static verification and evaluate acceptance evidence",
            listOf(checksId ?: if (changeWork) applyId!! else searchId ?: indexId)
        )
        add(ToolKind.RECORD_LESSON, "Persist the selected tools, result, and reusable evidence", listOf(verifyId))
        return ToolSelectionPlan(
            intake.originalRequest,
            tools,
            "Tools selected from intent, targets, constraints, and available project checks"
        )
    }
}

object ToolKindMapper {
    fun kindFor(toolName: String): ToolKind? = when (toolName) {
        "list_files", "read_file", "search_project" -> ToolKind.SEARCH_PROJECT
        "search_knowledge", "research_web" -> ToolKind.SEARCH_KNOWLEDGE
        "replace_text", "create_file" -> ToolKind.APPLY_CHANGES
        "run_command" -> ToolKind.RUN_CHECKS
        "verify" -> ToolKind.VERIFY
        else -> null
    }
}

class ToolSelectionLoop(plan: ToolSelectionPlan, private val maxIterations: Int = 32) {
    private val tools = plan.tools.toMutableList()
    private val history = mutableListOf<ToolLoopSnapshot>()
    private var activeId: String? = null
    private var iteration = 0
    private var status = "running"
    private var reason = "tool plan initialized"

    init { snapshot() }

    @Synchronized
    fun completeKind(toolKind: ToolKind, evidence: String = "") {
        val target = tools.firstOrNull { it.kind == toolKind } ?: return
        if (target.status == ToolStepStatus.PENDING || target.status == ToolStepStatus.ACTIVE) {
            replace(target.copy(status = ToolStepStatus.COMPLETE, evidence = evidence))
            activeId = null
            reason = "completed " + target.id
            snapshot()
        }
    }

    fun authorize(toolName: String, toolKind: ToolKind): String? {
        val target = tools.firstOrNull { it.kind == toolKind }
            ?: return "Tool " + toolName + " is not part of the active execution plan"
        val blockedDependency = target.dependsOn.firstOrNull { dependency ->
            tools.firstOrNull { it.id == dependency }?.status != ToolStepStatus.COMPLETE
        }
        return blockedDependency?.let { dependency ->
            "Tool " + toolName + " is blocked until planned tool " + dependency + " completes"
        }
    }

    fun recordSuccess(toolName: String, toolKind: ToolKind, evidence: String = "") {
        val target = tools.firstOrNull { it.kind == toolKind } ?: return
        if (target.status == ToolStepStatus.ACTIVE || target.status == ToolStepStatus.PENDING) {
            replace(target.copy(status = ToolStepStatus.COMPLETE, evidence = evidence))
            activeId = null
            reason = "completed " + target.id + " from " + toolName
            snapshot()
        }
    }

    fun recordFailure(toolName: String, toolKind: ToolKind, message: String) {
        val target = tools.firstOrNull { it.kind == toolKind } ?: return
        if (target.status == ToolStepStatus.ACTIVE || target.status == ToolStepStatus.PENDING) {
            replace(target.copy(status = ToolStepStatus.FAILED, evidence = message))
            activeId = null
            status = "failed"
            reason = target.id + " failed via " + toolName + ": " + message
            snapshot()
        }
    }

    fun next(): ToolInvocation? {
        if (status != "running") return null
        if (iteration >= maxIterations) {
            status = "iteration-limit"
            reason = "maximum tool iterations reached"
            snapshot()
            return null
        }
        val candidate = tools.firstOrNull { tool ->
            tool.status == ToolStepStatus.PENDING && tool.dependsOn.all { dependency ->
                tools.firstOrNull { it.id == dependency }?.status == ToolStepStatus.COMPLETE
            }
        }
        if (candidate == null) {
            status = when {
                tools.all { it.status == ToolStepStatus.COMPLETE } -> "complete"
                tools.any { it.status == ToolStepStatus.FAILED || it.status == ToolStepStatus.BLOCKED } -> "failed"
                else -> "blocked"
            }
            reason = "no executable tool remains"
            snapshot()
            return null
        }
        iteration++
        activeId = candidate.id
        replace(candidate.copy(status = ToolStepStatus.ACTIVE))
        reason = "executing ${candidate.kind}"
        snapshot()
        return tools.first { it.id == candidate.id }
    }

    @Synchronized
    fun complete(evidence: String = "") {
        val id = activeId ?: error("No active tool")
        val tool = tools.first { it.id == id }
        replace(tool.copy(status = ToolStepStatus.COMPLETE, evidence = evidence))
        activeId = null
        reason = "completed $id"
        snapshot()
    }

    @Synchronized
    fun fail(message: String) {
        val id = activeId ?: error("No active tool")
        val tool = tools.first { it.id == id }
        replace(tool.copy(status = ToolStepStatus.FAILED, evidence = message))
        activeId = null
        status = "failed"
        reason = "$id failed: $message"
        snapshot()
    }

    fun currentStatus(): String = status
    fun currentTools(): List<ToolInvocation> = tools.toList()
    fun history(): List<ToolLoopSnapshot> = history.toList()

    fun activeTool(): ToolInvocation? = activeId?.let { id -> tools.firstOrNull { it.id == id } }

    fun isComplete(): Boolean = status == "complete"

    fun blockPending(reason: String) {
        tools.filter { it.status == ToolStepStatus.PENDING }.forEach { replace(it.copy(status = ToolStepStatus.BLOCKED, evidence = reason)) }
        status = "blocked"
        this.reason = reason
        snapshot()
    }

    private fun replace(updated: ToolInvocation) {
        tools[tools.indexOfFirst { it.id == updated.id }] = updated
    }

    private fun snapshot() {
        history += ToolLoopSnapshot(iteration, status, reason, tools.toList())
    }
}
