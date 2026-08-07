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

    fun cancel(reason: String = "Stopped by owner") {
        cancelled.set(true)
        lastCancelReason = reason
    }

    fun isCancelled(): Boolean = cancelled.get()

    @Volatile
    private var lastCancelReason: String = "Stopped by owner"

    override fun execute(request: String): AgentRuntimeResult {
        val events = run(request)
        return when (val terminalEvent = events.lastOrNull()) {
            is AutonomousAgentEvent.ApprovalRequired -> AgentRuntimeResult.NeedsApproval(terminalEvent.task, "Review proposal ${terminalEvent.proposal.id} and confirm twice before applying any code change.", terminalEvent.proposal.id)
            is AutonomousAgentEvent.Completed -> AgentRuntimeResult.Completed(terminalEvent.task)
            is AutonomousAgentEvent.Failed -> terminalEvent.task?.let { AgentRuntimeResult.Failed(it) } ?: error(terminalEvent.message)
            is AutonomousAgentEvent.Stopped -> AgentRuntimeResult.Failed(terminalEvent.task)
            else -> error("Autonomous agent ended without a terminal result")
        }
    }

    fun pendingProposals(): List<PendingChangeProposal> = mutations.pending()
    fun approveProposal(id: String, ownerVerified: Boolean, ownerLabel: String): MutationApprovalResult = mutations.approve(id, ownerVerified, ownerLabel)
    fun rejectProposal(id: String): Boolean = mutations.reject(id)

    fun run(request: String, onEvent: (AutonomousAgentEvent) -> Unit = {}): List<AutonomousAgentEvent> {
        cancelled.set(false)
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val taskId = UUID.randomUUID().toString()
        val events = mutableListOf<AutonomousAgentEvent>(AutonomousAgentEvent.Started(taskId, normalized))
        fun emit(event: AutonomousAgentEvent) {
            events += event
            onEvent(event)
        }

        val intake = TaskIntake.interpret(normalized)
        emit(AutonomousAgentEvent.Phase("INTAKE", "intent=${intake.intent} files=${intake.referencedFiles.joinToString()}"))

        val plan = GoalInterpreter.plan(normalized, intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.summary))

        var researchEvidence = ""
        if (shouldResearch(normalized, intake)) {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Starting deep research"))
            runCatching {
                val mode = ResearchModeDetector.detect(normalized)
                val session = research.deepResearch(normalized, limit = 8, mode = mode) { progress ->
                    lastResearchProgress = "${progress.stage} ${progress.completed}/${progress.total}"
                    emit(AutonomousAgentEvent.Phase("RESEARCH", lastResearchProgress))
                }
                val brief = ResearchBrief.from(session)
                researchEvidence = "\n\nResearch brief:\n${brief.evidence}"
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Learned ${brief.sourceCount} sources"))
            }.onFailure {
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Research failed: ${it.message.orEmpty()}"))
            }
        }

        val transcript = mutableListOf<ModelMessage>()
        var consecutiveFailures = 0
        var lastToolSignature = ""
        var identicalRepeats = 0
        var evidenceRefusals = 0
        val readPaths = linkedSetOf<String>()
        var searchedProject = false

        for (turn in 1..config.maxTurns) {
            if (cancelled.get()) {
                return stopNow(taskId, normalized, plan, events, emit)
            }

            emit(AutonomousAgentEvent.Phase("MODEL", "Turn $turn/${config.maxTurns}"))
            val system = AgentModelProtocol.systemPrompt()
            val tools = AgentModelProtocol.toolsForIntent(intake.intent)
            val userPrompt = buildPrompt(normalized, intake, researchEvidence)
            val modelRequest = ModelRequest(
                system = system,
                user = userPrompt,
                transcript = transcript.toList(),
                tools = tools
            )

            val response = try {
                gateway.stream(modelRequest) { delta ->
                    emit(AutonomousAgentEvent.ModelDelta(delta))
                }
            } catch (e: Exception) {
                val task = failedTask(taskId, normalized, plan, "Model error: ${e.message.orEmpty()}", emptyList())
                emit(AutonomousAgentEvent.Failed(task, task.summary))
                return events
            }

            when (response) {
                is ModelResponse.Failure -> {
                    consecutiveFailures++
                    emit(AutonomousAgentEvent.Phase("MODEL", "Model failure: ${response.message}"))
                    if (consecutiveFailures >= config.maxConsecutiveToolFailures) {
                        val task = failedTask(taskId, normalized, plan, response.message, emptyList())
                        emit(AutonomousAgentEvent.Failed(task, task.summary))
                        return events
                    }
                    transcript += ModelMessage("assistant", response.message)
                    continue
                }
                is ModelResponse.Text -> {
                    val content = DegenerateOutput.sanitizeModelText(response.content)
                    if (DegenerateOutput.isDegenerate(content)) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.ModelMessage("[degenerate output suppressed]"))
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val task = failedTask(taskId, normalized, plan, "Repeated degenerate model output", emptyList())
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                        transcript += ModelMessage("assistant", content.take(500))
                        continue
                    }
                    emit(AutonomousAgentEvent.ModelMessage(content))
                    val gate = missingEvidenceMessage(intake, readPaths, searchedProject)
                    if (gate != null) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.Phase("TOOL", gate))
                        transcript += ModelMessage("assistant", content)
                        transcript += ModelMessage("user", gate)
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val task = failedTask(taskId, normalized, plan, gate, emptyList())
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                        continue
                    }
                    val task = AgentTask(
                        taskId, normalized, "completed", plan, changeSets.flatMap { it.changes },
                        VerificationReport(true, emptyList()),
                        listOf("${Instant.now()}: model reply (${response.content.length} chars); evidence files=${readPaths.joinToString().ifBlank { "none-required" }}"),
                        content.take(500)
                    )
                    journal.record(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return events
                }
                is ModelResponse.ToolCalls -> {
                    transcript += ModelMessage("assistant", response.raw.orEmpty().ifBlank { response.calls.joinToString { it.name } })
                    for (call in response.calls) {
                        if (cancelled.get()) {
                            return stopNow(taskId, normalized, plan, events, emit)
                        }
                        val argsJson = call.arguments
                        val signature = "${call.name}|$argsJson"
                        if (signature == lastToolSignature) {
                            identicalRepeats++
                            if (identicalRepeats >= config.maxIdenticalToolRepeats) {
                                val task = failedTask(taskId, normalized, plan, "Repeated identical tool call: ${call.name}", emptyList())
                                emit(AutonomousAgentEvent.Failed(task, task.summary))
                                return events
                            }
                        } else {
                            lastToolSignature = signature
                            identicalRepeats = 0
                        }

                        emit(AutonomousAgentEvent.ToolStarted(call.name, argsJson))
                        val toolResult = executeTool(call.name, argsJson)
                        val success = !toolResult.startsWith("ERROR:")
                        if (success) consecutiveFailures = 0 else consecutiveFailures++
                        emit(AutonomousAgentEvent.ToolFinished(call.name, toolResult.take(config.maxOutputCharacters), success))

                        when (call.name) {
                            "read_file" -> {
                                val path = runCatching { JSONObject(argsJson).optString("path") }.getOrDefault("")
                                if (path.isNotBlank() && success) readPaths += path
                            }
                            "search_project", "list_files" -> searchedProject = true
                        }

                        if (call.name == "approve_change" || call.name == "propose_change" || call.name == "replace_text" || call.name == "create_file") {
                            val proposal = mutations.pending().lastOrNull()
                            if (proposal != null && (call.name == "approve_change" || call.name == "propose_change")) {
                                val task = approvalTask(taskId, normalized, plan, proposal)
                                emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
                                return events
                            }
                        }

                        transcript += ModelMessage(
                            role = "tool",
                            content = toolResult.take(config.maxOutputCharacters),
                            toolName = call.name
                        )

                        if (consecutiveFailures >= config.maxConsecutiveToolFailures) {
                            val task = failedTask(taskId, normalized, plan, "Too many consecutive tool failures", emptyList())
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                    }
                }
            }
        }

        val task = failedTask(taskId, normalized, plan, "Exceeded max turns without completion", emptyList())
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
            taskId, request, "stopped", plan, changeSets.flatMap { it.changes },
            VerificationReport(false, emptyList()), listOf("${Instant.now()}: $message"), message
        )
        journal.record(task)
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
        val targets = intake.contract.targetPaths.map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }
        if (targets.isNotEmpty()) {
            val unresolved = targets.filter { target ->
                val t = target.lowercase()
                readPaths.none { read ->
                    val r = read.lowercase()
                    r == t || r.endsWith("/$t") || r.endsWith(t) || t.endsWith(r)
                }
            }
            if (unresolved.isNotEmpty()) {
                return "Required file evidence missing. Call read_file on: ${unresolved.joinToString(", ")}"
            }
            return null
        }
        // Classify from Current request only when ChatWorkspace prefixes conversation history.
        val focus = intake.originalRequest
            .substringAfter("Current request:", intake.originalRequest)
            .trim()
            .ifBlank { intake.originalRequest }
        val needsInspect = intake.intent == TaskIntent.INSPECT ||
            intake.intent == TaskIntent.EXPLAIN ||
            Regex("\\b(analy[sz]e|report|explain|summarize|review|inspect|what does|describe)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(focus)
        if (needsInspect && readPaths.isEmpty() && !searchedProject) {
            return "Required repository evidence missing. Call read_file, list_files, or search_project before finishing."
        }
        return null
    }

    private fun shouldResearch(request: String, intake: TaskIntake): Boolean {
        val lower = request.lowercase()
        if (Regex("\\b(research|look up|search the web|google|documentation online|how does .+ work online)\\b").containsMatchIn(lower)) return true
        if (intake.intent == TaskIntent.EXPLAIN && Regex("\\b(library|framework|api|sdk|package|crate|npm|pip)\\b").containsMatchIn(lower)) return true
        return false
    }

    private fun buildPrompt(request: String, intake: TaskIntake, evidence: String): String = buildString {
        append("You are a local coding agent on the user's phone. Answer and act on the request using the project files.\n")
        append("Prefer reading and searching the project. Propose file changes with tools; never claim a file was written until approval.\n")
        append("If the user names a file, you MUST call read_file on that path before any final report. Inventing contents is forbidden.\n")
        append("Respond in plain English. Be direct.\n\n")
        append("User request:\n").append(request)
        append("\n\nTyped intake:\n").append(intake.summary)
        append("\nIntent: ").append(intake.intent)
        if (intake.referencedFiles.isNotEmpty()) {
            append("\nReferenced files: ").append(intake.referencedFiles.joinToString())
        }
        if (intake.constraints.isNotEmpty()) {
            append("\nConstraints: ").append(intake.constraints.joinToString())
        }
        append("\n\nLatest verified evidence:\n").append(evidence.take(config.maxOutputCharacters))
        append("\n\nUse tools when you need repository facts. Do not invent file contents.")
    }

    private fun executeTool(name: String, argumentsJson: String): String {
        return runCatching {
            val arguments = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            when (name) {
                "list_files" -> files.list(arguments.optString("path")).joinToString("\n").limitOutput()
                "read_file" -> files.read(arguments.getString("path")).let { "path=${it.path}\n${it.content}" }.limitOutput()
                "search_project" -> files.search(arguments.getString("query"), arguments.optInt("limit", 20))
                    .joinToString("\n") { "${it.path}:${it.line}: ${it.snippet}" }.limitOutput()
                "replace_text" -> {
                    val proposal = mutations.proposeReplace(
                        path = arguments.getString("path"),
                        oldText = arguments.getString("old_text"),
                        newText = arguments.getString("new_text"),
                        reason = arguments.optString("reason", "replace_text")
                    )
                    "PROPOSED ${proposal.id} approvals=0/2 path=${arguments.getString("path")}"
                }
                "create_file" -> {
                    val proposal = mutations.proposeCreate(
                        path = arguments.getString("path"),
                        content = arguments.getString("content"),
                        reason = arguments.optString("reason", "create_file")
                    )
                    "PROPOSED ${proposal.id} approvals=0/2 path=${arguments.getString("path")}"
                }
                "run_command" -> {
                    val cmd = arguments.getString("command")
                    val result = terminal.run(cmd)
                    buildString {
                        append("exit=${result.exitCode}")
                        if (result.timedOut) append(" timedOut=true")
                        append("\nstdout:\n").append(result.stdout.take(config.maxOutputCharacters / 2))
                        append("\nstderr:\n").append(result.stderr.take(config.maxOutputCharacters / 2))
                    }
                }
                "verify" -> {
                    val report = workspace.verify()
                    buildString {
                        append("ok=${report.ok}\n")
                        report.issues.take(30).forEach { issue ->
                            append(issue.path).append(':').append(issue.line).append(" — ").append(issue.message).append('\n')
                        }
                    }.limitOutput()
                }
                "approve_change" -> {
                    val id = arguments.getString("proposal_id")
                    when (val result = mutations.approve(id, ownerVerified = false, ownerLabel = "model")) {
                        is MutationApprovalResult.AwaitingSecond -> "Awaiting second owner approval (${result.proposal.approvalCount}/2)"
                        is MutationApprovalResult.Applied -> "Applied ${result.proposal.id}"
                        is MutationApprovalResult.Rejected -> "Rejected: ${result.reason}"
                    }
                }
                "web_research" -> {
                    val query = arguments.getString("query")
                    val mode = ResearchModeDetector.detect(query)
                    val session = research.deepResearch(query, limit = arguments.optInt("limit", 6), mode = mode) {}
                    val brief = ResearchBrief.from(session)
                    "Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, ${brief.wordCount} words, ${brief.codeExampleCount} code examples.\nProgress: $lastResearchProgress\n${brief.evidence}".limitOutput()
                }
                else -> "ERROR: unknown tool $name"
            }
        }.getOrElse { "ERROR: ${it.message.orEmpty().ifBlank { it.javaClass.simpleName }}" }
    }

    private fun String.limitOutput(): String =
        if (length <= config.maxOutputCharacters) this else take(config.maxOutputCharacters) + "\n…[truncated]"

    private fun failedTask(id: String, request: String, plan: AgentPlan, message: String, changes: List<ChangeRecord>) =
        AgentTask(id, request, "failed", plan, changes, VerificationReport(false, listOf(VerificationIssue("<agent>", 0, message))), listOf("${Instant.now()}: $message"), message)

    private fun approvalTask(id: String, request: String, plan: AgentPlan, proposal: PendingChangeProposal) =
        AgentTask(id, request, "waiting-approval", plan, proposal.changeSet.changes, proposal.verification, listOf("${Instant.now()}: proposal ${proposal.id} staged; awaiting two owner approvals"), "Review proposal ${proposal.id} and confirm twice before applying any code change")
}
