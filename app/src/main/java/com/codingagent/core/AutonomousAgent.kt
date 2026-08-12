package com.codingagent.core

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONE JOB: Full evidence-driven tool-calling agent loop with dual-approval mutations.
 */
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

    @Volatile
    private var lastCancelReason: String = "Stopped by owner"

    fun cancel(reason: String = "Stopped by owner") {
        cancelled.set(true)
        lastCancelReason = reason
    }

    fun isCancelled(): Boolean = cancelled.get()

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
        emit(AutonomousAgentEvent.Phase("INTAKE", "Inspecting the request and repository"))
        val intake = runtime.intake(normalized)
        val plan = AgentPlanner(workspace).plan(intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.steps.joinToString(" → ") { it.phase }))
        if (!intake.executionReady) {
            val task = AgentTask(taskId, normalized, "needs-input", plan, emptyList(), VerificationReport(false, emptyList()), emptyList(), intake.clarificationQuestion ?: "Clarify the requested operation")
            emit(AutonomousAgentEvent.Failed(task, task.summary))
            journal.record(task)
            return events
        }

        var researchEvidence = ""
        if (shouldResearch(normalized, intake)) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Gathering a small set of sources (optional path)"))
            val mode = ResearchModeDetector.detect(normalized)
            val session = runCatching {
                research.deepResearch(normalized, 6, mode) { progress ->
                    if (cancelled.get()) return@deepResearch
                    emit(AutonomousAgentEvent.Phase("RESEARCH", "${progress.stage}: ${progress.completed}/${progress.total}; learned ${progress.successful}, failed ${progress.failed}"))
                }
            }.getOrNull()
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            if (session != null && session.sources.isNotEmpty()) {
                val brief = ResearchBriefBuilder.build(session)
                researchEvidence = "\n\nResearch brief:\n${brief.evidence}"
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Learned ${brief.sourceCount} sources (${brief.wordCount} words)"))
            } else {
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Skipped or empty; continuing with local project only"))
            }
        } else {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Skipped — local project is enough for this request"))
        }

        val transcript = mutableListOf<com.codingagent.core.ModelMessage>()
        var lastEvidence = "Repository indexed files: ${workspace.summary().files.size}. " +
            "Call list_files or search_project before read_file. Do not invent paths." +
            researchEvidence
        var consecutiveFailures = 0
        var lastToolSignature: String? = null
        var identicalRepeats = 0
        val readPaths = linkedSetOf<String>()
        var searchedProject = false
        var evidenceRefusals = 0

        for (turn in 0 until config.maxTurns) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("MODEL", "Decision turn ${turn + 1}/${config.maxTurns}"))
            val response = gateway.stream(
                ModelRequest(
                    AgentModelProtocol.SYSTEM,
                    buildPrompt(normalized, intake, lastEvidence),
                    AgentModelProtocol.tools(),
                    transcript.toList(),
                    researchRequired = false
                )
            ) { delta ->
                if (!cancelled.get()) emit(AutonomousAgentEvent.ModelDelta(delta))
            }
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            when (response) {
                is ModelResponse.Failure -> {
                    val task = failedTask(taskId, normalized, plan, response.message, changeSets.flatMap { it.changes })
                    emit(AutonomousAgentEvent.Failed(task, response.message))
                    journal.record(task)
                    return events
                }
                is ModelResponse.Text -> {
                    emit(AutonomousAgentEvent.ModelMessage(response.content))
                    val missing = missingEvidenceMessage(intake, readPaths, searchedProject)
                    if (missing != null) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.Phase("EVIDENCE", missing))
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val msg = "Refused to complete without reading the target file(s). $missing"
                            val task = failedTask(taskId, normalized, plan, msg, changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, msg))
                            journal.record(task)
                            return events
                        }
                        transcript += com.codingagent.core.ModelMessage("assistant", response.content.take(1_200))
                        transcript += com.codingagent.core.ModelMessage(
                            "user",
                            "SYSTEM CONSTRAINT: $missing You must call the read_file tool on the target path before any final report. Do not invent file contents."
                        )
                        lastEvidence = missing + "\n\n" + lastEvidence.take(config.maxOutputCharacters / 2)
                        continue
                    }
                    val report = workspace.verify()
                    val summary = sanitizeModelText(response.content, report)
                    val status = when {
                        isDegenerate(response.content) -> "completed-with-warning"
                        !report.passed -> "completed-with-issues"
                        else -> "completed"
                    }
                    val evidenceLabel = buildString {
                        if (readPaths.isNotEmpty()) append("read=").append(readPaths.joinToString())
                        if (searchedProject) {
                            if (isNotEmpty()) append("; ")
                            append("searched=true")
                        }
                        if (isEmpty()) append("none")
                    }
                    val task = AgentTask(
                        taskId, normalized, status, plan,
                        changeSets.flatMap { it.changes }, report,
                        listOf("${Instant.now()}: model reply (${response.content.length} chars); evidence=$evidenceLabel; verify_issues=${report.issues.size}"),
                        summary
                    )
                    journal.record(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return events
                }
                is ModelResponse.ToolCall -> {
                    val signature = "${response.name}|${response.arguments.trim()}"
                    if (signature == lastToolSignature) {
                        identicalRepeats++
                        if (identicalRepeats >= config.maxIdenticalToolRepeats) {
                            if ((response.name == "list_files" || response.name == "search_project") &&
                                lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:") &&
                                isListingRequest(normalized)
                            ) {
                                val report = workspace.verify()
                                val summary = formatListingSummary(lastEvidence, report)
                                val task = AgentTask(
                                    taskId, normalized, "completed", plan,
                                    changeSets.flatMap { it.changes }, report,
                                    listOf("${Instant.now()}: stopped identical ${response.name} loop; returned last listing"),
                                    summary
                                )
                                journal.record(task)
                                emit(AutonomousAgentEvent.Completed(task))
                                return events
                            }
                            if ((response.name == "list_files" || response.name == "search_project") &&
                                lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:")
                            ) {
                                transcript += com.codingagent.core.ModelMessage(
                                    "user",
                                    "SYSTEM: ${response.name} already returned evidence. " +
                                        "Do NOT call it again. Write a clear final answer to the user using that evidence."
                                )
                                lastEvidence = lastEvidence.take(config.maxOutputCharacters)
                                lastToolSignature = ""
                                identicalRepeats = 0
                                continue
                            }
                            val msg = "Aborted: tool ${response.name} repeated identically $identicalRepeats times"
                            val task = failedTask(taskId, normalized, plan, msg, changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, msg))
                            journal.record(task)
                            return events
                        }
                    } else {
                        lastToolSignature = signature
                        identicalRepeats = 1
                    }
                    emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments))
                    if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
                    val toolResult = executeTool(response.name, response.arguments)
                    lastEvidence = toolResult
                    transcript += com.codingagent.core.ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, response.callId, response.name, response.arguments)
                    transcript += com.codingagent.core.ModelMessage("tool", "${response.name}: $toolResult", response.callId)
                    val success = !toolResult.startsWith("ERROR:")
                    if (success) {
                        when (response.name) {
                            "read_file" -> {
                                val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                                if (!path.isNullOrBlank()) readPaths += path.trim().trimStart('/')
                            }
                            "search_project", "list_files" -> searchedProject = true
                        }
                    }
                    emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult, success))
                    if (success) {
                        consecutiveFailures = 0
                        if (response.name == "list_files" || response.name == "search_project") {
                            if (isListingRequest(normalized)) {
                                val report = workspace.verify()
                                val summary = formatListingSummary(toolResult, report)
                                val task = AgentTask(
                                    taskId, normalized, "completed", plan,
                                    changeSets.flatMap { it.changes }, report,
                                    listOf("${Instant.now()}: answered listing via ${response.name}"),
                                    summary
                                )
                                journal.record(task)
                                emit(AutonomousAgentEvent.Completed(task))
                                return events
                            }
                            transcript += com.codingagent.core.ModelMessage(
                                "user",
                                "SYSTEM: ${response.name} already returned the result above. " +
                                    "Use that result to answer the user. Do NOT call ${response.name} again with the same arguments."
                            )
                        }
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= config.maxConsecutiveToolFailures) {
                            val msg = "Aborted after $consecutiveFailures consecutive tool failures (last: ${response.name})"
                            val task = failedTask(taskId, normalized, plan, msg, changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, msg))
                            journal.record(task)
                            return events
                        }
                    }
                    val proposalId = toolResult.substringAfter("PROPOSAL_READY id=", "").substringBefore(' ').takeIf { it.isNotBlank() }
                    if (proposalId != null) {
                        val proposal = mutations.get(proposalId)
                        if (proposal == null) {
                            val task = failedTask(taskId, normalized, plan, "The mutation proposal disappeared before approval: $proposalId", changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            journal.record(task)
                        } else {
                            val task = approvalTask(taskId, normalized, plan, proposal)
                            emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
                            journal.record(task)
                        }
                        return events
                    }
                }
            }
        }
        val task = failedTask(taskId, normalized, plan, "The model exceeded the autonomous turn budget", changeSets.flatMap { it.changes })
        emit(AutonomousAgentEvent.Failed(task, task.summary))
        journal.record(task)
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
        val needsInspect = intake.intent == TaskIntent.INSPECT ||
            intake.intent == TaskIntent.EXPLAIN ||
            Regex("\\b(analy[sz]e|report|explain|summarize|review|inspect|what does|describe|error|bug|issue|fix)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(intake.originalRequest)
        if (needsInspect && readPaths.isEmpty() && !searchedProject) {
            return "Required repository evidence missing. Call read_file or search_project before finishing so the answer is based on real project content."
        }
        val wantsErrorHunt = Regex("\\b(error|bug|issue|broken|fail|fix|lint)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(intake.originalRequest)
        if (wantsErrorHunt && readPaths.isEmpty() && workspace.summary().files.isNotEmpty()) {
            return "Error/issue analysis requires reading project source. Call read_file on at least one relevant source file first."
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
        appendLine("You are a Coding-Agent on the user's phone: a senior developer with tools. Plan → gather real evidence → act with one tool → observe → verify → iterate until the goal is completed.")
        appendLine()
        appendLine("Hard constraints for this turn:")
        appendLine("1. Never invent paths or file contents. Discover with list_files / search_project / read_file.")
        appendLine("2. If the user names a file, call read_file on it before any analysis or final answer.")
        appendLine("3. Exactly one precise tool call this turn. Observe the full result before the next step.")
        appendLine("4. Code changes only stage a proposal. Dual owner approval is required; never claim a change was applied until the tool returns APPLIED.")
        appendLine("5. Call verify after changes or when hunting bugs/errors. Never report a fake pass.")
        appendLine("6. Persist until the goal is met. Only stop early when you need a specific missing input from the user that tools cannot supply — state exactly what you need.")
        appendLine("7. Be direct and technical. Prefer truth over guesses. On failure: diagnose, adjust, retry correctly.")
        appendLine("8. Final answers must synthesize evidence into a clear reply. Never paste raw search dumps as the answer unless the user only asked to list files.")
        appendLine()
        appendLine("User request:")
        appendLine(request)
        appendLine()
        val targets = intake.contract.targetPaths.joinToString().ifBlank { "none yet" }
        appendLine("Intake: ${intake.summary} | Intent: ${intake.intent} | Targets: $targets")
        appendLine()
        appendLine("Latest evidence (ground truth from tools):")
        append(evidence.take(config.maxOutputCharacters))
    }

    private fun executeTool(name: String, rawArguments: String): String {
        return try {
            val arguments = JSONObject(rawArguments)
            when (name) {
                "list_files" -> {
                    val listed = files.list(arguments.optString("path"))
                    val result = if (listed.isEmpty()) {
                        "(no files in this directory)"
                    } else {
                        listed.joinToString("\n")
                    }
                    result.limitOutput()
                }
                "read_file" -> {
                    files.read(arguments.getString("path")).content.limitOutput()
                }
                "search_project" -> {
                    workspace.search(arguments.getString("query"))
                        .joinToString("\n") { hit -> "${hit.path}:${hit.line}: ${hit.text}" }
                        .limitOutput()
                }
                "search_knowledge" -> {
                    knowledge.search(arguments.getString("query"))
                        .joinToString("\n") { hit -> "${hit.document}/${hit.section}: ${hit.excerpt}" }
                        .limitOutput()
                }
                "research_web" -> {
                    val query = arguments.getString("query")
                    val mode = runCatching {
                        ResearchMode.valueOf(arguments.optString("mode", "BROAD").uppercase())
                    }.getOrDefault(ResearchModeDetector.detect(query))
                    val sources = arguments.optInt("sources", 6).coerceIn(1, 12)
                    val session = research.deepResearch(query, sources, mode) { progress ->
                        lastResearchProgress =
                            "${progress.stage}: ${progress.completed}/${progress.total}; " +
                                "learned ${progress.successful}, failed ${progress.failed}"
                    }
                    val brief = ResearchBriefBuilder.build(session)
                    val header =
                        "Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, " +
                            "${brief.wordCount} words, ${brief.codeExampleCount} code examples.\n" +
                            "Progress: $lastResearchProgress\n"
                    (header + brief.evidence).limitOutput()
                }
                "replace_text" -> {
                    val proposal = mutations.propose(
                        request = "Autonomous model proposed replace_text",
                        operations = listOf(
                            TaskOperation(
                                OperationKind.REPLACE,
                                arguments.getString("path"),
                                arguments.getString("oldText"),
                                arguments.getString("newText")
                            )
                        ),
                        reason = arguments.optString("reason", "Autonomous model proposal")
                    )
                    "PROPOSAL_READY id=${proposal.id} changes=${proposal.changeSet.changes.size} approval_required=2"
                }
                "create_file" -> {
                    val proposal = mutations.propose(
                        request = "Autonomous model proposed create_file",
                        operations = listOf(
                            TaskOperation(
                                OperationKind.CREATE_FILE,
                                arguments.getString("path"),
                                text = arguments.getString("content")
                            )
                        ),
                        reason = arguments.optString("reason", "Autonomous model proposal")
                    )
                    "PROPOSAL_READY id=${proposal.id} changes=${proposal.changeSet.changes.size} approval_required=2"
                }
                "run_command" -> {
                    val entry = terminal.execute(arguments.getString("command"))
                    ("exit=${entry.exitCode} timeout=${entry.timedOut}\n${entry.stdout}\n${entry.stderr}")
                        .limitOutput()
                }
                "verify" -> {
                    val report = workspace.verify()
                    val issues = report.issues.joinToString("\n") { issue ->
                        "${issue.path}:${issue.line}: ${issue.message}"
                    }
                    "passed=${report.passed}\n$issues".limitOutput()
                }
                "approve_change" -> approveChange(arguments)
                "reject_change" -> rejectChange(arguments)
                else -> "ERROR: Unknown tool '$name'"
            }
        } catch (error: Exception) {
            "ERROR: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun approveChange(arguments: JSONObject): String {
        val result = mutations.approve(
            id = arguments.getString("id"),
            ownerVerified = arguments.optBoolean("ownerVerified", false),
            ownerLabel = arguments.optString("ownerLabel", "owner")
        )
        return when (result) {
            is MutationApprovalResult.AwaitingSecond -> {
                "AWAITING_SECOND_APPROVAL id=${result.proposal.id} approvals=${result.proposal.approvalCount}"
            }
            is MutationApprovalResult.Applied -> {
                changeSets += result.changeSet
                "APPLIED id=${result.proposal.id} changes=${result.changeSet.changes.size}"
            }
            is MutationApprovalResult.Rejected -> "ERROR: ${result.reason}"
        }
    }

    private fun rejectChange(arguments: JSONObject): String {
        val id = arguments.getString("id")
        return if (mutations.reject(id)) {
            "REJECTED id=$id"
        } else {
            "ERROR: Change proposal does not exist"
        }
    }

    private fun String.limitOutput(): String = take(config.maxOutputCharacters)

    private fun isDegenerate(text: String): Boolean = DegenerateOutput.isDegenerate(text)

    private fun sanitizeModelText(text: String, report: VerificationReport): String {
        if (!isDegenerate(text)) {
            return text.take(4_000)
        }
        return buildString {
            append("The model produced repetitive garbage instead of a coherent report. ")
            append("Static verification found ")
            append(report.issues.size)
            append(" issue(s)")
            if (report.issues.isEmpty()) {
                append(" (none).")
            } else {
                append(":")
                report.issues.take(20).forEach { issue ->
                    append("\n- ")
                    append(issue.path)
                    append(":")
                    append(issue.line)
                    append(" — ")
                    append(issue.message)
                }
            }
        }
    }

    private fun formatListingSummary(listing: String, report: VerificationReport): String {
        return buildString {
            append("Project files:\n")
            append(listing.trim().ifBlank { "(none)" })
            append("\n\nVerification: ")
            if (report.passed) {
                append("passed")
            } else {
                append("FAILED; ")
                append(report.issues.size)
                append(" issue(s)")
                report.issues.take(20).forEach { issue ->
                    append("\n- ")
                    append(issue.path)
                    append(":")
                    append(issue.line)
                    append(" — ")
                    append(issue.message)
                }
            }
        }
    }

    private fun isListingRequest(request: String): Boolean {
        val t = request.lowercase()
        val listingHints = listOf(
            "list file", "list files", "list the file", "list project", "list the project",
            "show file", "show files", "show the file", "what files", "which files",
            "file list", "files in the project", "project files", "directory listing"
        )
        return listingHints.any { hint -> t.contains(hint) }
    }

    private fun failedTask(
        id: String,
        request: String,
        plan: AgentPlan,
        message: String,
        changes: List<ChangeRecord>
    ): AgentTask {
        return AgentTask(
            id = id,
            request = request,
            status = "failed",
            plan = plan,
            changes = changes,
            verification = VerificationReport(
                passed = false,
                issues = listOf(VerificationIssue("<agent>", 0, message))
            ),
            events = listOf("${Instant.now()}: $message"),
            summary = message
        )
    }

    private fun approvalTask(
        id: String,
        request: String,
        plan: AgentPlan,
        proposal: PendingChangeProposal
    ): AgentTask {
        return AgentTask(
            id = id,
            request = request,
            status = "waiting-approval",
            plan = plan,
            changes = proposal.changeSet.changes,
            verification = proposal.verification,
            events = listOf(
                "${Instant.now()}: proposal ${proposal.id} staged; awaiting two owner approvals"
            ),
            summary = "Review proposal ${proposal.id} and confirm twice before applying any code change"
        )
    }
}
