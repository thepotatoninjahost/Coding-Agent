package com.codingagent.core

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed class AutonomousAgentEvent {
    data class Started(val taskId: String, val request: String) : AutonomousAgentEvent()
    data class Phase(val name: String, val detail: String) : AutonomousAgentEvent()
    data class ToolStarted(val name: String, val arguments: String) : AutonomousAgentEvent()
    data class ToolFinished(val name: String, val result: String, val success: Boolean) : AutonomousAgentEvent()
    data class ModelDelta(val text: String) : AutonomousAgentEvent()
    data class ModelMessage(val content: String) : AutonomousAgentEvent()
    data class Completed(val task: AgentTask) : AutonomousAgentEvent()
    data class Failed(val task: AgentTask?, val message: String) : AutonomousAgentEvent()
    data class Stopped(val task: AgentTask, val message: String) : AutonomousAgentEvent()
    data class ApprovalRequired(val task: AgentTask, val proposal: PendingChangeProposal) : AutonomousAgentEvent()
}

data class AutonomousAgentConfig(
    val maxTurns: Int = 24,
    val commandTimeoutSeconds: Long = 180,
    val maxOutputCharacters: Int = 8_000,
    val maxConsecutiveToolFailures: Int = 5,
    val maxIdenticalToolRepeats: Int = 3,
    val maxEvidenceRefusals: Int = 3
)

/**
 * Evidence-gated autonomous coding agent for local NPU inference.
 * Inspects the repo (list/read/search) before claiming file facts or finishing code-change intents.
 */
class AutonomousAgent(
    private val root: java.io.File,
    private val runtime: CodingAgentRuntime,
    private val knowledge: AgentKnowledge,
    private val gateway: ModelGateway,
    private val config: AutonomousAgentConfig = AutonomousAgentConfig(),
    private val research: DeepResearchProvider = DurableDeepResearchProvider(root.resolve(".coding-agent/research")),
    private val mutations: MutationCoordinator = MutationCoordinator(ProjectWorkspace(root))
) : CodingAgentExecutor {
    private val workspace = ProjectWorkspace(root)
    private val files = ProjectFileService(workspace)
    private val terminal = TerminalSession(root, config.commandTimeoutSeconds)
    private val journal = AgentJournal(root)
    private val changeSets = mutableListOf<ChangeSet>()
    private var lastResearchProgress: String = "not started"
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var lastCancelReason: String = "Stopped by owner"

    override fun execute(request: String): AgentRuntimeResult {
        val events = run(request)
        return when (val terminal = events.lastOrNull()) {
            is AutonomousAgentEvent.ApprovalRequired -> AgentRuntimeResult.NeedsApproval(
                terminal.task,
                "Review proposal ${terminal.proposal.id} and confirm twice before applying any code change.",
                terminal.proposal.id
            )
            is AutonomousAgentEvent.Completed -> AgentRuntimeResult.Completed(terminal.task)
            is AutonomousAgentEvent.Stopped -> AgentRuntimeResult.Failed(terminal.task)
            is AutonomousAgentEvent.Failed -> terminal.task?.let { AgentRuntimeResult.Failed(it) }
                ?: AgentRuntimeResult.Failed(
                    AgentTask(
                        id = UUID.randomUUID().toString(),
                        request = request,
                        status = "failed",
                        plan = AgentPlan(request, emptyList(), emptyList()),
                        changes = emptyList(),
                        verification = VerificationReport(false, emptyList()),
                        events = emptyList(),
                        summary = terminal.message
                    )
                )
            else -> AgentRuntimeResult.Failed(
                AgentTask(
                    id = UUID.randomUUID().toString(),
                    request = request,
                    status = "failed",
                    plan = AgentPlan(request, emptyList(), emptyList()),
                    changes = emptyList(),
                    verification = VerificationReport(false, emptyList()),
                    events = emptyList(),
                    summary = "Agent finished without a terminal event"
                )
            )
        }
    }

    fun cancel(reason: String = "Stopped by owner") {
        lastCancelReason = reason
        cancelled.set(true)
        gateway.stopGeneration()
    }

    fun run(request: String, onEvent: (AutonomousAgentEvent) -> Unit = {}): List<AutonomousAgentEvent> {
        cancelled.set(false)
        val events = mutableListOf<AutonomousAgentEvent>()
        val taskId = UUID.randomUUID().toString()
        val normalized = request.trim()
        events += AutonomousAgentEvent.Started(taskId, normalized)
        onEvent(events.last())

        fun emit(event: AutonomousAgentEvent) {
            events += event
            onEvent(event)
        }

        val intake = TaskIntakeParser(root).parse(normalized)
        emit(AutonomousAgentEvent.Phase("INTAKE", "intent=${intake.intent} targets=${intake.contract.targetPaths.joinToString()}"))

        val plan = AgentPlanner(workspace).plan(intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.steps.joinToString(" → ") { it.phase }))

        var researchEvidence = ""
        if (shouldResearch(normalized, intake)) {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Starting deep research"))
            runCatching {
                val mode = ResearchModeDetector.detect(normalized)
                val session = research.deepResearch(normalized, targetSources = 6, mode = mode) { p ->
                    lastResearchProgress = p
                    emit(AutonomousAgentEvent.Phase("RESEARCH", lastResearchProgress))
                }
                val brief = ResearchBriefBuilder.build(session)
                researchEvidence = brief.evidence
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Learned ${brief.sourceCount} sources"))
            }.onFailure {
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Research failed: ${it.message.orEmpty()}"))
            }
        }

        val history = mutableListOf<ModelMessage>()
        val readPaths = mutableSetOf<String>()
        var searchedProject = false
        var consecutiveToolFailures = 0
        var evidenceRefusals = 0
        val toolFingerprints = mutableMapOf<String, Int>()
        val pendingChanges = mutableListOf<ChangeRecord>()

        for (turn in 1..config.maxTurns) {
            if (cancelled.get()) {
                return stopNow(taskId, normalized, plan, events) { emit(it) }
            }

            emit(AutonomousAgentEvent.Phase("MODEL", "Turn $turn/${config.maxTurns}"))

            val prompt = buildPrompt(normalized, intake, plan, researchEvidence, history, readPaths, searchedProject, pendingChanges)
            val response = try {
                gateway.generateWithTools(
                    messages = listOf(ModelMessage.system(prompt.system), ModelMessage.user(prompt.user)),
                    tools = TOOL_SCHEMAS,
                    onDelta = { delta ->
                        emit(AutonomousAgentEvent.ModelDelta(delta))
                    }
                )
            } catch (e: Exception) {
                val task = failedTask(taskId, normalized, plan, "Model error: ${e.message.orEmpty()}", pendingChanges)
                emit(AutonomousAgentEvent.Failed(task, task.summary))
                return events
            }

            when (response) {
                is ModelResponse.Error -> {
                    emit(AutonomousAgentEvent.Phase("MODEL", "Model failure: ${response.message}"))
                    if (turn >= 3) {
                        val task = failedTask(taskId, normalized, plan, response.message, pendingChanges)
                        emit(AutonomousAgentEvent.Failed(task, task.summary))
                        return events
                    }
                    continue
                }
                is ModelResponse.Text -> {
                    val content = response.content.trim()
                    if (DegenerateOutput.isDegenerate(content)) {
                        emit(AutonomousAgentEvent.ModelMessage("[degenerate output suppressed]"))
                        if (++evidenceRefusals >= config.maxEvidenceRefusals) {
                            val task = failedTask(taskId, normalized, plan, "Repeated degenerate model output", pendingChanges)
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                        continue
                    }
                    emit(AutonomousAgentEvent.ModelMessage(content))
                    val gate = missingEvidenceMessage(intake, readPaths, searchedProject)
                    if (gate != null) {
                        emit(AutonomousAgentEvent.Phase("TOOL", gate))
                        evidenceRefusals++
                        if (evidenceRefusals >= config.maxEvidenceRefusals) {
                            val task = failedTask(taskId, normalized, plan, gate, pendingChanges)
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                        history += ModelMessage.assistant(content)
                        history += ModelMessage.user("Evidence required before finishing: $gate. Use list_files, read_file, or search_project first.")
                        continue
                    }
                    val verification = workspace.verify()
                    val task = AgentTask(
                        taskId,
                        normalized,
                        if (verification.passed) "completed" else "completed-with-issues",
                        plan,
                        pendingChanges.toList(),
                        verification,
                        events.map { it.toString() }.takeLast(40),
                        content.take(2_000)
                    )
                    emit(AutonomousAgentEvent.Completed(task))
                    journal.record(task)
                    return events
                }
                is ModelResponse.ToolCall -> {
                    if (cancelled.get()) {
                        return stopNow(taskId, normalized, plan, events) { emit(it) }
                    }
                    val argsJson = response.arguments.toString()
                    val fingerprint = "${response.name}|$argsJson"
                    val repeats = (toolFingerprints[fingerprint] ?: 0) + 1
                    toolFingerprints[fingerprint] = repeats
                    if (repeats > config.maxIdenticalToolRepeats) {
                        val task = failedTask(taskId, normalized, plan, "Identical tool call repeated too many times: ${response.name}", pendingChanges)
                        emit(AutonomousAgentEvent.Failed(task, task.summary))
                        return events
                    }

                    emit(AutonomousAgentEvent.ToolStarted(response.name, argsJson))
                    val toolResult = executeTool(response.name, response.arguments, readPaths, pendingChanges)
                    val success = !toolResult.startsWith("ERROR:")
                    if (!success) consecutiveToolFailures++ else consecutiveToolFailures = 0
                    emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult.take(config.maxOutputCharacters), success))

                    if (response.name in setOf("list_files", "search_project", "list_dir")) searchedProject = true
                    if (response.name == "read_file") {
                        response.arguments.optString("path").takeIf { it.isNotBlank() }?.let { readPaths += it }
                    }
                    if (response.name in setOf("write_file", "create_file", "replace_text", "apply_patch")) {
                        val proposal = mutations.propose(pendingChanges, normalized)
                        if (proposal != null) {
                            val task = approvalTask(taskId, normalized, plan, proposal)
                            emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
                            return events
                        }
                    }

                    history += ModelMessage.assistantToolCall(response.name, argsJson, response.id)
                    history += ModelMessage.toolResult(response.id, toolResult)

                    if (consecutiveToolFailures >= config.maxConsecutiveToolFailures) {
                        val task = failedTask(taskId, normalized, plan, "Too many consecutive tool failures", pendingChanges)
                        emit(AutonomousAgentEvent.Failed(task, task.summary))
                        return events
                    }
                }
            }
        }

        val task = failedTask(taskId, normalized, plan, "Reached max turns without completion", pendingChanges)
        emit(AutonomousAgentEvent.Failed(task, task.summary))
        return events
    }

    private fun stopNow(
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>,
        emit: (AutonomousAgentEvent) -> Unit
    ): List<AutonomousAgentEvent> {
        val message = lastCancelReason
        val task = AgentTask(
            taskId,
            request,
            "stopped",
            plan,
            emptyList(),
            VerificationReport(false, emptyList()),
            events.map { it.toString() }.takeLast(20),
            message
        )
        emit(AutonomousAgentEvent.Stopped(task, message))
        return events
    }

    private fun missingEvidenceMessage(
        intake: TaskIntake,
        readPaths: Set<String>,
        searchedProject: Boolean
    ): String? {
        // Greetings / free chat (UNKNOWN) never require tool evidence.
        if (intake.intent == TaskIntent.UNKNOWN) return null
        if (intake.intent in setOf(TaskIntent.IMPLEMENT, TaskIntent.REFACTOR, TaskIntent.FIX, TaskIntent.TEST) &&
            readPaths.isEmpty() && !searchedProject
        ) {
            return "Before claiming code changes or finishing, inspect the repo with list_files / search_project / read_file."
        }
        if (intake.contract.targetPaths.isNotEmpty() &&
            intake.contract.targetPaths.none { target -> readPaths.any { it.equals(target, ignoreCase = true) || it.endsWith(target) } } &&
            !searchedProject
        ) {
            return "Read or search the declared target path(s) first: ${intake.contract.targetPaths.joinToString()}."
        }
        return null
    }

    private fun shouldResearch(request: String, intake: TaskIntake): Boolean {
        if (intake.intent == TaskIntent.UNKNOWN) return false
        val lower = request.lowercase()
        return lower.contains("research") || lower.contains("how to") || lower.contains("best practice") ||
            lower.contains("library") || lower.contains("api") || intake.intent == TaskIntent.EXPLAIN
    }

    private fun buildPrompt(
        request: String,
        intake: TaskIntake,
        plan: AgentPlan,
        researchEvidence: String,
        history: List<ModelMessage>,
        readPaths: Set<String>,
        searchedProject: Boolean,
        pendingChanges: List<ChangeRecord>
    ): Pair<String, String> {
        val system = buildString {
            appendLine("You are a careful coding agent running on-device.")
            appendLine("Use tools to inspect the project before asserting file contents or applying edits.")
            appendLine("Available tools: list_files, read_file, search_project, write_file, create_file, replace_text, run_command, verify.")
            appendLine("For code-change intents, gather evidence first. UNKNOWN/greeting intents may answer directly.")
            if (researchEvidence.isNotBlank()) {
                appendLine("Research evidence:")
                appendLine(researchEvidence.take(1500))
            }
        }
        val user = buildString {
            appendLine("Request: $request")
            appendLine("Intent: ${intake.intent}")
            appendLine("Plan: ${plan.steps.joinToString { it.phase }}")
            appendLine("Read paths so far: ${readPaths.joinToString().ifBlank { "(none)" }}")
            appendLine("Searched project: $searchedProject")
            if (pendingChanges.isNotEmpty()) appendLine("Pending changes: ${pendingChanges.size}")
            if (history.isNotEmpty()) {
                appendLine("Recent tool/model turns:")
                history.takeLast(6).forEach { appendLine(it.role + ": " + it.content.take(400)) }
            }
        }
        return system to user
    }

    private fun executeTool(
        name: String,
        arguments: JSONObject,
        readPaths: MutableSet<String>,
        pendingChanges: MutableList<ChangeRecord>
    ): String {
        return runCatching {
            when (name) {
                "list_files", "list_dir" -> {
                    val path = arguments.optString("path", ".")
                    files.list(path).joinToString("\n").ifBlank { "(empty)" }.limitOutput()
                }
                "read_file" -> {
                    val path = arguments.getString("path")
                    val content = files.read(path)
                    readPaths += path
                    content.limitOutput()
                }
                "search_project" -> {
                    val query = arguments.getString("query")
                    files.search(query).joinToString("\n").ifBlank { "No matches" }.limitOutput()
                }
                "write_file", "create_file" -> {
                    val path = arguments.getString("path")
                    val content = arguments.getString("content")
                    pendingChanges += ChangeRecord(path, content, if (name == "create_file") "create" else "write")
                    "Staged $name for $path (${content.length} chars); awaiting approval"
                }
                "replace_text" -> {
                    val path = arguments.getString("path")
                    val old = arguments.getString("old")
                    val new = arguments.getString("new")
                    pendingChanges += ChangeRecord(path, "replace:$old=>$new", "replace")
                    "Staged replace in $path; awaiting approval"
                }
                "run_command" -> {
                    val cmd = arguments.getString("command")
                    terminal.execute(cmd).limitOutput()
                }
                "verify" -> {
                    val report = workspace.verify()
                    buildString {
                        appendLine("passed=${report.passed} issues=${report.issues.size}")
                        report.issues.take(20).forEach { appendLine("${it.path}:${it.line} ${it.message}") }
                    }.limitOutput()
                }
                "research_web", "web_research" -> {
                    val query = arguments.getString("query")
                    val mode = ResearchModeDetector.detect(query)
                    val session = research.deepResearch(query, targetSources = arguments.optInt("limit", 6), mode = mode) {}
                    val brief = ResearchBriefBuilder.build(session)
                    ("Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, " +
                        "${brief.wordCount} words, ${brief.codeExampleCount} code examples.\n" +
                        "Progress: $lastResearchProgress\n${brief.evidence}").limitOutput()
                }
                else -> "ERROR: unknown tool $name"
            }
        }.getOrElse { "ERROR: ${it.message.orEmpty().ifBlank { it.javaClass.simpleName }}" }
    }

    private fun String.limitOutput(): String =
        if (length <= config.maxOutputCharacters) this else take(config.maxOutputCharacters) + "\n…[truncated]"

    private fun failedTask(
        id: String,
        request: String,
        plan: AgentPlan,
        message: String,
        changes: List<ChangeRecord>
    ) = AgentTask(
        id,
        request,
        "failed",
        plan,
        changes,
        VerificationReport(false, listOf(VerificationIssue("<agent>", 0, message))),
        listOf("${Instant.now()}: $message"),
        message
    )

    private fun approvalTask(
        id: String,
        request: String,
        plan: AgentPlan,
        proposal: PendingChangeProposal
    ) = AgentTask(
        id,
        request,
        "waiting-approval",
        plan,
        proposal.changeSet.changes,
        proposal.verification,
        listOf("${Instant.now()}: proposal ${proposal.id} staged; awaiting two owner approvals"),
        "Review proposal ${proposal.id} and confirm twice before applying any code change"
    )

    companion object {
        private val TOOL_SCHEMAS = emptyList<ToolSchema>() // schemas supplied by ModelGateway / Nexa path
    }
}
