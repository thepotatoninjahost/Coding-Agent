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
    private val projectRoot: java.io.File,
    private val runtime: CodingAgentRuntime,
    private val knowledge: AgentKnowledge,
    private val model: ModelGateway,
    private val research: DeepResearchProvider? = null,
    private val mutations: MutationCoordinator,
    private val config: AutonomousAgentConfig = AutonomousAgentConfig()
) {
    private val stopped = AtomicBoolean(false)
    private val files = ProjectWorkspace(projectRoot)

    fun stop() {
        stopped.set(true)
    }

    fun pendingProposals(): List<PendingChangeProposal> = mutations.pending()

    fun run(request: String, onEvent: (AutonomousAgentEvent) -> Unit = {}): List<AutonomousAgentEvent> {
        val events = mutableListOf<AutonomousAgentEvent>()
        fun emit(event: AutonomousAgentEvent) {
            events += event
            onEvent(event)
        }

        val taskId = UUID.randomUUID().toString()
        emit(AutonomousAgentEvent.Started(taskId, request))
        stopped.set(false)

        val intake = TaskIntake.interpret(request)
        emit(AutonomousAgentEvent.Phase("INTAKE", "intent=${intake.intent} files=${intake.referencedFiles.joinToString()}"))

        val plan = GoalInterpreter.plan(request, intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.summary))

        var researchEvidence = ""
        var lastResearchProgress = ""
        if (intake.needsResearch && research != null) {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Starting deep research"))
            runCatching {
                val mode = ResearchModeDetector.detect(request)
                val session = research.deepResearch(request, limit = 8, mode = mode) { progress ->
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

        val transcript = mutableListOf<com.codingagent.core.ModelMessage>()
        var consecutiveFailures = 0
        var lastToolSignature = ""
        var identicalRepeats = 0
        var evidenceRefusals = 0
        val readPaths = linkedSetOf<String>()
        var searchedProject = false

        for (turn in 1..config.maxTurns) {
            if (stopped.get()) {
                val task = stopNow(taskId, request, plan, "Stopped by owner")
                emit(AutonomousAgentEvent.Stopped(task, task.summary))
                return events
            }

            emit(AutonomousAgentEvent.Phase("MODEL", "Turn $turn/${config.maxTurns}"))
            val system = AgentModelProtocol.systemPrompt()
            val tools = AgentModelProtocol.toolsForIntent(intake.intent)
            val userPrompt = buildPrompt(request, intake, researchEvidence)
            val modelRequest = ModelRequest(
                system = system,
                user = userPrompt,
                transcript = transcript.toList(),
                tools = tools
            )

            val response = try {
                model.stream(modelRequest) { delta ->
                    emit(AutonomousAgentEvent.ModelDelta(delta))
                }
            } catch (e: Exception) {
                val task = failedTask(taskId, request, plan, "Model error: ${e.message.orEmpty()}", emptyList())
                emit(AutonomousAgentEvent.Failed(task, task.summary))
                return events
            }

            when (response) {
                is ModelResponse.Failure -> {
                    consecutiveFailures++
                    emit(AutonomousAgentEvent.Phase("MODEL", "Model failure: ${response.message}"))
                    if (consecutiveFailures >= config.maxConsecutiveToolFailures) {
                        val task = failedTask(taskId, request, plan, response.message, emptyList())
                        emit(AutonomousAgentEvent.Failed(task, task.summary))
                        return events
                    }
                    transcript += com.codingagent.core.ModelMessage("assistant", response.message)
                    continue
                }
                is ModelResponse.Text -> {
                    val content = DegenerateOutput.sanitizeModelText(response.content)
                    if (DegenerateOutput.isDegenerate(content)) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.ModelMessage("[degenerate output suppressed]"))
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val task = failedTask(taskId, request, plan, "Repeated degenerate model output", emptyList())
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                        transcript += com.codingagent.core.ModelMessage("assistant", content.take(500))
                        continue
                    }
                    emit(AutonomousAgentEvent.ModelMessage(content))
                    val gate = evidenceGate(intake, readPaths, searchedProject, content)
                    if (gate != null) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.Phase("TOOL", gate))
                        transcript += com.codingagent.core.ModelMessage("assistant", content)
                        transcript += com.codingagent.core.ModelMessage("user", gate)
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val task = failedTask(taskId, request, plan, gate, emptyList())
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                        continue
                    }
                    val task = AgentTask(
                        taskId, request, "completed", plan, emptyList(),
                        VerificationReport(true, emptyList()),
                        listOf("${Instant.now()}: model reply (${response.content.length} chars); evidence files=${readPaths.joinToString().ifBlank { "none-required" }}"),
                        content.take(500)
                    )
                    emit(AutonomousAgentEvent.Completed(task))
                    return events
                }
                is ModelResponse.ToolCalls -> {
                    transcript += com.codingagent.core.ModelMessage("assistant", response.raw.orEmpty().ifBlank { response.calls.joinToString { it.name } })
                    for (call in response.calls) {
                        if (stopped.get()) {
                            val task = stopNow(taskId, request, plan, "Stopped during tools")
                            emit(AutonomousAgentEvent.Stopped(task, task.summary))
                            return events
                        }
                        val argsJson = call.arguments
                        val signature = "${call.name}|$argsJson"
                        if (signature == lastToolSignature) {
                            identicalRepeats++
                            if (identicalRepeats >= config.maxIdenticalToolRepeats) {
                                val task = failedTask(taskId, request, plan, "Repeated identical tool call: ${call.name}", emptyList())
                                emit(AutonomousAgentEvent.Failed(task, task.summary))
                                return events
                            }
                        } else {
                            lastToolSignature = signature
                            identicalRepeats = 0
                        }

                        emit(AutonomousAgentEvent.ToolStarted(call.name, argsJson))
                        val toolResult = executeTool(call.name, argsJson, mutations)
                        val success = !toolResult.startsWith("ERROR:")
                        if (success) consecutiveFailures = 0 else consecutiveFailures++
                        emit(AutonomousAgentEvent.ToolFinished(call.name, toolResult.take(config.maxOutputCharacters), success))

                        when (call.name) {
                            "read_file" -> {
                                val path = runCatching { JSONObject(argsJson).optString("path") }.getOrDefault("")
                                if (path.isNotBlank() && success) readPaths += path
                            }
                            // list_files / search_project both count as repository inspection evidence
                            "search_project", "list_files" -> searchedProject = true
                        }

                        if (call.name == "approve_change" || call.name == "propose_change") {
                            val proposal = mutations.pending().lastOrNull()
                            if (proposal != null) {
                                val task = approvalTask(taskId, request, plan, proposal)
                                emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
                                return events
                            }
                        }

                        transcript += com.codingagent.core.ModelMessage(
                            role = "tool",
                            content = toolResult.take(config.maxOutputCharacters),
                            toolName = call.name
                        )

                        if (consecutiveFailures >= config.maxConsecutiveToolFailures) {
                            val task = failedTask(taskId, request, plan, "Too many consecutive tool failures", emptyList())
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            return events
                        }
                    }
                }
            }
        }

        val task = failedTask(taskId, request, plan, "Exceeded max turns without completion", emptyList())
        emit(AutonomousAgentEvent.Failed(task, task.summary))
        return events
    }

    private fun stopNow(
        id: String,
        request: String,
        plan: AgentPlan,
        message: String
    ): AgentTask = AgentTask(
        id, request, "stopped", plan, emptyList(),
        VerificationReport(false, listOf(VerificationIssue("<agent>", 0, message))),
        listOf("${Instant.now()}: $message"), message
    )

    private fun evidenceGate(
        intake: TaskIntake,
        readPaths: Set<String>,
        searchedProject: Boolean,
        reply: String
    ): String? {
        // Greetings / free chat (UNKNOWN) never require tool evidence.
        if (intake.intent == TaskIntent.UNKNOWN || intake.intent == TaskIntent.CHAT) return null
        val mentionsFiles = intake.referencedFiles.isNotEmpty()
        val claimsFileAnalysis = reply.contains("file", ignoreCase = true) &&
            (reply.contains("contains", ignoreCase = true) || reply.contains("line", ignoreCase = true) ||
                reply.contains("function", ignoreCase = true) || reply.contains("class ", ignoreCase = true))

        if (mentionsFiles) {
            val unresolved = intake.referencedFiles.filter { ref ->
                readPaths.none { it.contains(ref, ignoreCase = true) || ref.contains(it, ignoreCase = true) }
            }
            if (unresolved.isNotEmpty() && claimsFileAnalysis) {
                return "Required file evidence missing. Call read_file on: ${unresolved.joinToString(", ")}"
            }
        }

        val isCodeChangeIntent = intake.intent == TaskIntent.IMPLEMENT ||
            intake.intent == TaskIntent.FIX ||
            intake.intent == TaskIntent.REFACTOR ||
            intake.intent == TaskIntent.TEST

        if (isCodeChangeIntent && readPaths.isEmpty() && !searchedProject) {
            // Allow pure planning answers that do not claim to have inspected the repo
            val claimsInspection = reply.contains("I looked", ignoreCase = true) ||
                reply.contains("I read", ignoreCase = true) ||
                reply.contains("in the project", ignoreCase = true) ||
                reply.contains("your codebase", ignoreCase = true)
            if (claimsInspection) {
                return "Required repository evidence missing. Call read_file, list_files, or search_project before finishing."
            }
        }
        return null
    }

    private fun buildPrompt(request: String, intake: TaskIntake, evidence: String): String = buildString {
        append("Owner request:\n")
        append(request.take(2000))
        append("\n\nInterpreted intent: ").append(intake.intent)
        if (intake.referencedFiles.isNotEmpty()) {
            append("\nReferenced files: ").append(intake.referencedFiles.joinToString())
        }
        if (intake.constraints.isNotEmpty()) {
            append("\nConstraints: ").append(intake.constraints.joinToString())
        }
        append("\n\nLatest verified evidence:\n").append(evidence.take(config.maxOutputCharacters))
        append("\n\nUse tools when you need repository facts. Do not invent file contents.")
    }

    private fun executeTool(name: String, argumentsJson: String, mutations: MutationCoordinator): String {
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
                    val result = files.runCommand(cmd, config.commandTimeoutSeconds)
                    buildString {
                        append("exit=${result.exitCode}")
                        if (result.timedOut) append(" timedOut=true")
                        append("\nstdout:\n").append(result.stdout.take(config.maxOutputCharacters / 2))
                        append("\nstderr:\n").append(result.stderr.take(config.maxOutputCharacters / 2))
                    }
                }
                "verify" -> {
                    val report = files.verify()
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
                    val provider = research ?: return@runCatching "ERROR: research provider unavailable"
                    val mode = ResearchModeDetector.detect(query)
                    val session = provider.deepResearch(query, limit = arguments.optInt("limit", 6), mode = mode) {}
                    val brief = ResearchBrief.from(session)
                    "Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, ${brief.wordCount} words, ${brief.codeExampleCount} code examples.\nProgress: $lastResearchProgress\n${brief.evidence}".limitOutput()
                }
                else -> "ERROR: unknown tool $name"
            }
        }.getOrElse { "ERROR: ${it.message.orEmpty().ifBlank { it.javaClass.simpleName }}" }
    }

    private fun String.limitOutput(): String =
        if (length <= config.maxOutputCharacters) this else take(config.maxOutputCharacters) + "\n…[truncated]"

    private fun sanitizeSummary(report: VerificationReport): String = buildString {
        if (!report.ok) {
            append("The model produced repetitive garbage instead of a coherent report. ")
            append("Static verification found ")
            append(report.issues.size)
            append(" issue(s)")
            if (report.issues.isEmpty()) append(" (none).")
            else {
                append(":")
                report.issues.take(20).forEach { issue ->
                    append("\n- "); append(issue.path); append(":"); append(issue.line); append(" — "); append(issue.message)
                }
            }
        }
    }

    private fun failedTask(id: String, request: String, plan: AgentPlan, message: String, changes: List<ChangeRecord>) =
        AgentTask(id, request, "failed", plan, changes, VerificationReport(false, listOf(VerificationIssue("<agent>", 0, message))), listOf("${Instant.now()}: $message"), message)

    private fun approvalTask(id: String, request: String, plan: AgentPlan, proposal: PendingChangeProposal) =
        AgentTask(id, request, "waiting-approval", plan, proposal.changeSet.changes, proposal.verification, listOf("${Instant.now()}: proposal ${proposal.id} staged; awaiting two owner approvals"), "Review proposal ${proposal.id} and confirm twice before applying any code change")
}
