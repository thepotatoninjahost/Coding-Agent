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
        fun add(kind: ToolKind, purpose: String) {
            val dependency = tools.lastOrNull()?.id?.let(::listOf).orEmpty()
            tools += ToolInvocation("${tools.size + 1}-${kind.name.lowercase()}", kind, purpose, dependency)
        }
        fun addAfter(kind: ToolKind, purpose: String, dependencyId: String) {
            tools += ToolInvocation("${tools.size + 1}-${kind.name.lowercase()}", kind, purpose, listOf(dependencyId))
        }

        add(ToolKind.INDEX_REPOSITORY, "Build the repository file, symbol, import, and checksum view")
        val indexId = tools.last().id
        if (intake.contract.targetPaths.isNotEmpty() || intake.contract.targetSymbols.isNotEmpty() || intake.intent in setOf(TaskIntent.INSPECT, TaskIntent.DEBUG, TaskIntent.REFACTOR)) {
            add(ToolKind.SEARCH_PROJECT, "Locate target files, symbols, and relevant project evidence")
        }
        val searchId = tools.last().id
        addAfter(ToolKind.SEARCH_KNOWLEDGE, "Retrieve relevant local coding references and lessons", indexId)
        val knowledgeId = tools.last().id
        if (intake.intent in setOf(TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG)) {
            addAfter(ToolKind.SYNTHESIZE_CODE, "Produce a structured, testable change proposal", knowledgeId)
            addAfter(ToolKind.APPLY_CHANGES, "Apply the selected proposal through the workspace mutation API", tools.last().id)
        }
        if (intake.verificationCommands.isNotEmpty()) {
            addAfter(ToolKind.RUN_CHECKS, "Run the project checks selected during intake", tools.last().id)
        }
        addAfter(ToolKind.VERIFY, "Run static verification and evaluate acceptance evidence", tools.last().id)
        addAfter(ToolKind.RECORD_LESSON, "Persist the selected tools, result, and reusable evidence", tools.last().id)
        return ToolSelectionPlan(intake.originalRequest, tools, "Tools selected from intent, targets, constraints, and available project checks")
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
