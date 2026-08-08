package com.codingagent.orchestration

enum class ToolKind { INDEX_REPOSITORY, SEARCH_PROJECT, SEARCH_KNOWLEDGE, SYNTHESIZE_CODE, APPLY_CHANGES, RUN_CHECKS, VERIFY, RECORD_LESSON }
enum class ToolStepStatus { PENDING, ACTIVE, COMPLETE, FAILED, BLOCKED }
data class ToolInvocation(val id: String, val kind: ToolKind, val purpose: String, val dependsOn: List<String> = emptyList(), val status: ToolStepStatus = ToolStepStatus.PENDING, val evidence: String = "")
data class ToolSelectionPlan(val request: String, val tools: List<ToolInvocation>, val rationale: String)
data class ToolLoopSnapshot(val iteration: Int, val status: String, val reason: String, val tools: List<ToolInvocation>)

class ToolSelector {
    fun select(intake: com.codingagent.intake.TaskIntake): ToolSelectionPlan {
        val tools = mutableListOf<ToolInvocation>()
        fun add(kind: ToolKind, purpose: String, dependency: String? = tools.lastOrNull()?.id) { tools += ToolInvocation("${tools.size + 1}-${kind.name.lowercase()}", kind, purpose, dependency?.let(::listOf).orEmpty()) }
        add(ToolKind.INDEX_REPOSITORY, "Build the repository file, symbol, import, and checksum view")
        if (intake.contract.targetPaths.isNotEmpty() || intake.contract.targetSymbols.isNotEmpty() || intake.intent in setOf(com.codingagent.intake.TaskIntent.INSPECT, com.codingagent.intake.TaskIntent.DEBUG, com.codingagent.intake.TaskIntent.REFACTOR)) add(ToolKind.SEARCH_PROJECT, "Locate target files, symbols, and relevant project evidence")
        val indexId = tools.first().id
        add(ToolKind.SEARCH_KNOWLEDGE, "Retrieve relevant local coding references and lessons", indexId)
        if (intake.intent in setOf(com.codingagent.intake.TaskIntent.CHANGE, com.codingagent.intake.TaskIntent.CREATE, com.codingagent.intake.TaskIntent.REFACTOR, com.codingagent.intake.TaskIntent.DEBUG)) { add(ToolKind.SYNTHESIZE_CODE, "Produce a structured, testable change proposal"); add(ToolKind.APPLY_CHANGES, "Apply the selected proposal through the workspace mutation API") }
        if (intake.verificationCommands.isNotEmpty()) add(ToolKind.RUN_CHECKS, "Run the project checks selected during intake")
        add(ToolKind.VERIFY, "Run static verification and evaluate acceptance evidence")
        add(ToolKind.RECORD_LESSON, "Persist the selected tools, result, and reusable evidence")
        return ToolSelectionPlan(intake.originalRequest, tools, "Tools selected from intent, targets, constraints, and available project checks")
    }
}

class ToolSelectionLoop(private val plan: ToolSelectionPlan, private val maxIterations: Int = 32) {
    private val tools = plan.tools.toMutableList()
    private val history = mutableListOf<ToolLoopSnapshot>()
    private var activeId: String? = null
    private var iteration = 0
    private var status = "running"
    private var reason = "tool plan initialized"
    init { snapshot() }
    @Synchronized fun next(): ToolInvocation? {
        if (status != "running" || activeId != null) return null
        if (iteration >= maxIterations) { status = "iteration-limit"; reason = "maximum tool iterations reached"; snapshot(); return null }
        val candidate = tools.firstOrNull { it.status == ToolStepStatus.PENDING && it.dependsOn.all { dep -> tools.firstOrNull { tool -> tool.id == dep }?.status == ToolStepStatus.COMPLETE } }
        if (candidate == null) { status = when { tools.all { it.status == ToolStepStatus.COMPLETE } -> "complete"; tools.any { it.status == ToolStepStatus.FAILED || it.status == ToolStepStatus.BLOCKED } -> "failed"; else -> "blocked" }; reason = "no executable tool remains"; snapshot(); return null }
        iteration++; activeId = candidate.id; replace(candidate.copy(status = ToolStepStatus.ACTIVE)); reason = "executing ${candidate.kind}"; snapshot(); return tools.first { it.id == candidate.id }
    }
    @Synchronized fun complete(evidence: String = "") { val id = activeId ?: error("No active tool"); replace(tools.first { it.id == id }.copy(status = ToolStepStatus.COMPLETE, evidence = evidence)); activeId = null; reason = "completed $id"; snapshot() }
    @Synchronized fun fail(message: String) { val id = activeId ?: error("No active tool"); replace(tools.first { it.id == id }.copy(status = ToolStepStatus.FAILED, evidence = message)); activeId = null; status = "failed"; reason = "$id failed: $message"; snapshot() }
    fun currentStatus(): String = status
    fun currentTools(): List<ToolInvocation> = tools.toList()
    fun history(): List<ToolLoopSnapshot> = history.toList()
    fun activeTool(): ToolInvocation? = activeId?.let { id -> tools.firstOrNull { it.id == id } }
    fun isComplete(): Boolean = status == "complete"
    @Synchronized fun blockPending(blockReason: String) { tools.filter { it.status == ToolStepStatus.PENDING }.forEach { replace(it.copy(status = ToolStepStatus.BLOCKED, evidence = blockReason)) }; status = "blocked"; reason = blockReason; snapshot() }
    private fun replace(updated: ToolInvocation) { tools[tools.indexOfFirst { it.id == updated.id }] = updated }
    private fun snapshot() { history += ToolLoopSnapshot(iteration, status, reason, tools.toList()) }
}
