package com.codingagent.agent

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.codingagent.intake.CodeSynthesisEngine
import com.codingagent.intake.OperationKind
import com.codingagent.intake.SynthesisResult
import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.intake.TaskIntent
import com.codingagent.intake.TaskOperation
import com.codingagent.model.AgentModelProtocol
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.JsonModelResponseParser
import com.codingagent.research.DeepResearchProvider
import com.codingagent.research.DurableDeepResearchProvider
import com.codingagent.research.ResearchBriefBuilder
import com.codingagent.research.ResearchMode
import com.codingagent.research.ResearchModeDetector
import com.codingagent.workspace.ChangeRecord
import com.codingagent.workspace.ChangeSet
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.PendingChangeProposal
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession
import com.codingagent.workspace.VerificationIssue
import com.codingagent.workspace.VerificationReport
import com.codingagent.workspace.AgentTask

/**
 * ONE JOB: Single agent execution spine — evidence-driven tool loop.
 */
class AutonomousAgent(
    private val root: java.io.File,
    private val knowledge: AgentKnowledge,
    /** Null = local-only mode: greeting, list, status, explicit read, offline explicit edits still work. */
    private val gateway: ModelGateway? = null,
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
    private val lanes = AgentDirectLanes(workspace, files, mutations)
    private val tools = AgentToolDispatch(
        files = files,
        workspace = workspace,
        knowledge = knowledge,
        research = research,
        mutations = mutations,
        terminal = terminal,
        maxOutputCharacters = config.maxOutputCharacters,
        onResearchProgress = { lastResearchProgress = it },
        onApplied = { changeSets += it }
    )
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
        // Strip chat-history wrapper so intent matches the current user line only.
        val focus = currentRequestFocus(normalized)
        val intake = TaskIntakeParser(root).parse(focus)
        val plan = AgentPlanner(workspace).plan(intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.steps.joinToString(" → ") { it.phase }))

        // Direct lanes: do not force the full tool loop for social / status / explicit read.
        lanes.respond(taskId, focus, intake, plan)?.let { task ->
            journal.record(task)
            emit(AutonomousAgentEvent.Completed(task))
            return events
        }

        // Offline edits before executionReady so create/replace with a clear target still stages without a model.
        AgentOfflineStager.stage(taskId, focus, intake, plan, workspace, knowledge, mutations, gateway)?.let { offline ->
            when (offline) {
                is AgentOfflineMutation.Approval -> {
                    journal.record(offline.task)
                    emit(AutonomousAgentEvent.ApprovalRequired(offline.task, offline.proposal))
                    return events
                }
                is AgentOfflineMutation.NeedsInput -> {
                    journal.record(offline.task)
                    emit(AutonomousAgentEvent.Completed(offline.task))
                    return events
                }
                is AgentOfflineMutation.Failed -> {
                    journal.record(offline.task)
                    emit(AutonomousAgentEvent.Failed(offline.task, offline.task.summary))
                    return events
                }
            }
        }

        if (!intake.executionReady) {
            val question = intake.clarificationQuestion ?: "Clarify the requested operation"
            val task = AgentTask(
                taskId, focus, "needs-input", plan, emptyList(),
                VerificationReport(true, emptyList()),
                listOf("${java.time.Instant.now()}: needs input from user"),
                question
            )
            // Completed (not Failed): clarification is a valid agent outcome, not a crash.
            emit(AutonomousAgentEvent.Completed(task))
            journal.record(task)
            return events
        }

        // Model required only past direct lanes and offline explicit ops.
        val activeGateway = gateway
        if (activeGateway == null) {
            val msg =
                "Model is not configured. Local commands still work: hello, list files, status, read <path>, " +
                    "and explicit replace/create when the operation is fully specified. " +
                    "Open Model settings (base URL, model name, API key) for autonomous coding and research."
            val task = AgentTask(
                taskId, focus, "needs-input", plan, emptyList(),
                VerificationReport(true, emptyList()),
                listOf("${Instant.now()}: model gateway missing — local lanes only"),
                msg
            )
            emit(AutonomousAgentEvent.Completed(task))
            journal.record(task)
            return events
        }

        var researchEvidence = ""
        val wantsResearch = shouldResearch(focus, intake)
        if (wantsResearch) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Looking up external sources"))
            val mode = ResearchModeDetector.detect(focus)
            val session = runCatching {
                research.deepResearch(focus, 8, mode) { progress ->
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
                researchEvidence =
                    "\n\nResearch ran but returned no usable sources. Do not invent external docs or APIs. " +
                        "Prefer local project evidence, or call research_web with a tighter technical query."
                emit(AutonomousAgentEvent.Phase("RESEARCH", "No usable sources — model must not invent external facts"))
            }
        } else {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Skipped — local project is enough for this request"))
        }

        val transcript = mutableListOf<com.codingagent.model.ModelMessage>()
        var lastEvidence = buildString {
            append(repoMapSummary())
            append("\nCall list_files or search_project before read_file. Do not invent paths.")
            append(researchEvidence)
        }
        var consecutiveFailures = 0
        var lastToolSignature: String? = null
        var identicalRepeats = 0
        var repeatResetCount = 0
        val readPaths = linkedSetOf<String>()
        var searchedProject = false
        var evidenceRefusals = 0
        var successfulGathers = 0
        var writeNowRefusals = 0
        var writeNowAnnounced = false
        val reviewJob = isWholeProjectReview(focus)

        for (turn in 0 until config.maxTurns) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("MODEL", "Decision turn ${turn + 1}/${config.maxTurns}"))
            val decision = LoopControl.decide(
                turn = turn,
                maxTurns = config.maxTurns,
                usefulGathers = successfulGathers,
                writeRefusals = writeNowRefusals,
                intent = intake.intent,
                wholeProjectReview = reviewJob
            )
            val writeNow = decision.demandWrite
            val changeWork = intake.intent == TaskIntent.CHANGE ||
                intake.intent == TaskIntent.CREATE ||
                intake.intent == TaskIntent.REFACTOR ||
                intake.intent == TaskIntent.DEBUG
            val toolsThisTurn = when {
                decision.toolsOpen -> AgentModelProtocol.toolsForIntent(intake.intent)
                changeWork && writeNow -> AgentModelProtocol.tools().filter { tool ->
                    tool.name in setOf("replace_text", "create_file", "verify")
                }
                else -> emptyList()
            }
            if (writeNow && !writeNowAnnounced) {
                writeNowAnnounced = true
                transcript += com.codingagent.model.ModelMessage(
                    "user",
                    if (changeWork) {
                        "SYSTEM: Gathering is done. Stage replace_text or create_file now. " +
                            "Do not list or search again. Do not write a review instead of the change."
                    } else {
                        "SYSTEM: You already have project evidence. Do NOT call gather tools. " +
                            "Write the answer now from evidence already gathered."
                    }
                )
            }
            var response = activeGateway.complete(
                ModelRequest(
                    AgentModelProtocol.SYSTEM,
                    buildPrompt(focus, intake, lastEvidence),
                    toolsThisTurn,
                    transcript.toList(),
                    researchRequired = false
                )
            )
            // One automatic wait+retry on provider rate limit (TPM) or empty response.
            // Retry must keep the same tool policy as this turn — do not re-open tools after writeNow.
            if (response is ModelResponse.Failure && (ModelFailure.isRateLimit(response.message) || ModelFailure.isEmpty(response.message))) {
                if (ModelFailure.isRateLimit(response.message)) {
                    val waitSec = ModelFailure.waitSeconds(response.message).coerceIn(1, 45)
                    emit(AutonomousAgentEvent.Phase("MODEL", "Rate limited — waiting ${waitSec}s then retrying once"))
                    try {
                        Thread.sleep(waitSec * 1000L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } else {
                    emit(AutonomousAgentEvent.Phase("MODEL", "Empty model response — retrying once"))
                }
                if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
                response = activeGateway.complete(
                    ModelRequest(
                        AgentModelProtocol.SYSTEM,
                        buildPrompt(focus, intake, lastEvidence),
                        toolsThisTurn,
                        transcript.toList(),
                        researchRequired = false
                    )
                )
            }
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            when (response) {
                is ModelResponse.Failure -> {
                    val friendly = ModelFailure.humanize(response.message)
                    val named = extractInspectTarget(normalized) ?: extractExplicitReadPath(normalized)
                    val localExtra = named?.let { buildLocalFileReport(it) }
                        ?.asUserText(includePolicy = true, includeStructure = true)
                    val summary = if (localExtra != null) {
                        "$friendly\n\nLocal evidence gathered before model failure:\n$localExtra"
                    } else {
                        friendly
                    }
                    val task = failedTask(taskId, normalized, plan, summary, changeSets.flatMap { it.changes })
                    emit(AutonomousAgentEvent.Failed(task, summary))
                    journal.record(task)
                    return events
                }
                is ModelResponse.Text -> {
                    // Last-ditch: XML tool markup that slipped past the parser is not a review.
                    val recovered = JsonModelResponseParser().parse(response.content)
                    if (recovered is ModelResponse.ToolCall) {
                        response = recovered
                        // Fall through by re-entering the when via continue after rewriting is messy;
                        // handle like a tool call below by jumping to the tool path with a labeled block.
                    }
                    if (response is ModelResponse.Text && looksLikeRawToolMarkup(response.content)) {
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content.take(800))
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM: That was a raw tool dump, not an answer. " +
                                "Write the review in plain English from evidence already gathered. No XML. No tool tags."
                        )
                        continue
                    }
                    if (response !is ModelResponse.Text) {
                        // recovered tool call: run the existing tool-call arm by looping with it
                        // Store on a local and fall through — handled after this if by checking type again.
                    }
                    if (response is ModelResponse.ToolCall) {
                        // Reuse tool path: set and don't complete as text.
                        // Jump by assigning and using a goto-equivalent: copy is too large.
                        // Instead enqueue as next model result by handling here minimally:
                        val recoveredCall = response
                        emit(AutonomousAgentEvent.ToolStarted(recoveredCall.name, recoveredCall.arguments))
                        val toolResult = executeTool(recoveredCall.name, recoveredCall.arguments)
                        if (toolResult.isNotBlank() && toolResult != "(no files)") {
                            lastEvidence = toolResult
                        }
                        val success = !toolResult.startsWith("ERROR:")
                        emit(AutonomousAgentEvent.ToolFinished(recoveredCall.name, toolResult, success))
                        transcript += com.codingagent.model.ModelMessage(
                            "assistant",
                            recoveredCall.thought.ifBlank { "Calling ${recoveredCall.name}" },
                            recoveredCall.callId,
                            recoveredCall.name,
                            recoveredCall.arguments
                        )
                        transcript += com.codingagent.model.ModelMessage(
                            "tool",
                            "${recoveredCall.name}: $toolResult",
                            recoveredCall.callId
                        )
                        if (success && recoveredCall.name in setOf("read_file", "search_project")) {
                            successfulGathers++
                            if (recoveredCall.name == "read_file") {
                                val path = runCatching { JSONObject(recoveredCall.arguments).getString("path") }.getOrNull()
                                if (!path.isNullOrBlank()) readPaths += path.trim().trimStart('/')
                            } else {
                                searchedProject = true
                            }
                        }
                        continue
                    }
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
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content.take(1_200))
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM CONSTRAINT: $missing You must call the read_file tool on the target path before any final report. Do not invent file contents."
                        )
                        lastEvidence = missing + "\n\n" + lastEvidence.take(config.maxOutputCharacters / 2)
                        continue
                    }
                    val report = workspace.verify()
                    // Listing requests: prefer real tool evidence over model prose (which is often spam).
                    val useListingEvidence = isListingRequest(currentRequestFocus(normalized)) &&
                        searchedProject &&
                        lastEvidence.isNotBlank() &&
                        !lastEvidence.startsWith("ERROR:")
                    val summary = if (useListingEvidence) {
                        formatListingSummary(lastEvidence, report, isSourceFileListRequest(currentRequestFocus(normalized)))
                    } else {
                        sanitizeModelText(response.content, report)
                    }
                    val status = when {
                        useListingEvidence -> "completed"
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
                    val eventNote = if (useListingEvidence) {
                        "${Instant.now()}: answered listing from tool evidence (model text ignored); evidence=$evidenceLabel"
                    } else {
                        "${Instant.now()}: model reply (${response.content.length} chars); evidence=$evidenceLabel; verify_issues=${report.issues.size}"
                    }
                    val task = AgentTask(
                        taskId, normalized, status, plan,
                        changeSets.flatMap { it.changes }, report,
                        listOf(eventNote),
                        summary
                    )
                    journal.record(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return events
                }
                is ModelResponse.ToolCall -> {
                    if (writeNow) {
                        val mutationTool = response.name == "replace_text" ||
                            response.name == "create_file" ||
                            response.name == "verify"
                        if (!(changeWork && mutationTool)) {
                            writeNowRefusals++
                            if (changeWork) {
                                transcript += com.codingagent.model.ModelMessage(
                                    "user",
                                    "SYSTEM: This is a change task. Call replace_text or create_file. " +
                                        "Do not call ${response.name}. A written review is not the work."
                                )
                                continue
                            }
                            if (decision.synthesizeFromEvidence || writeNowRefusals >= 2) {
                                val report = workspace.verify()
                                val summary = synthesizeFromEvidence(normalized, lastEvidence, report)
                                val task = AgentTask(
                                    taskId, normalized, "completed-with-warning", plan,
                                    changeSets.flatMap { it.changes }, report,
                                    listOf("${Instant.now()}: model kept requesting tools after close; finished from evidence"),
                                    summary
                                )
                                journal.record(task)
                                emit(AutonomousAgentEvent.Completed(task))
                                return events
                            }
                            transcript += com.codingagent.model.ModelMessage(
                                "user",
                                "SYSTEM: Tools are closed. Your last reply was a tool call (${response.name}). " +
                                    "Write the review in plain text now."
                            )
                            continue
                        }
                    }
                    val signature = "${response.name}|${response.arguments.trim()}"
                    if (signature == lastToolSignature) {
                        identicalRepeats++
                        if (identicalRepeats >= config.maxIdenticalToolRepeats) {
                            if ((response.name == "list_files" || response.name == "search_project") &&
                                lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:") &&
                                isListingRequest(currentRequestFocus(normalized))
                            ) {
                                val report = workspace.verify()
                                val summary = formatListingSummary(lastEvidence, report, isSourceFileListRequest(currentRequestFocus(normalized)))
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
                            repeatResetCount++
                            if (repeatResetCount >= 2) {
                                // Already nudged once and told the model to stop; it repeated the
                                // identical call again anyway. Do not keep burning turns on a nudge
                                // that has already proven ineffective — abort now, clearly.
                                val report = workspace.verify()
                                val msg = "Aborted: ${response.name} was called identically and repeatedly " +
                                    "even after being told to stop. The model is not responding to correction, " +
                                    "so continuing would only waste the remaining turn budget."
                                val task = AgentTask(
                                    taskId, normalized, "failed", plan,
                                    changeSets.flatMap { it.changes }, report,
                                    listOf("${Instant.now()}: aborted after repeated identical ${response.name} calls survived a prior warning"),
                                    msg
                                )
                                journal.record(task)
                                emit(AutonomousAgentEvent.Failed(task, msg))
                                return events
                            }
                            // Any tool with usable evidence: force a final answer. Do not abort the task.
                            if (lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:")) {
                                transcript += com.codingagent.model.ModelMessage(
                                    "user",
                                    "SYSTEM: Tool ${response.name} was called identically $identicalRepeats times. " +
                                        "Do NOT call any tool again. Write a clear final answer using only the evidence already returned."
                                )
                                lastEvidence = lastEvidence.take(config.maxOutputCharacters)
                                lastToolSignature = ""
                                identicalRepeats = 0
                                continue
                            }
                            // No usable evidence: nudge a different tool instead of hard-aborting.
                            transcript += com.codingagent.model.ModelMessage(
                                "user",
                                "SYSTEM: ${response.name} repeated with the same arguments and returned nothing useful. " +
                                    "Call a different tool (list_files, search_project, or read_file on a different path) " +
                                    "or give a final answer stating what is missing. Do not repeat the same call."
                            )
                            lastToolSignature = ""
                            identicalRepeats = 0
                            continue
                        }
                    } else {
                        lastToolSignature = signature
                        identicalRepeats = 1
                    }
                    emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments))
                    if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
                    val toolResult = executeTool(response.name, response.arguments)
                    // Never wipe gathered context with an empty tool body.
                    if (toolResult.isNotBlank() && toolResult != "(no files)") {
                        lastEvidence = toolResult
                    }
                    transcript += com.codingagent.model.ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, response.callId, response.name, response.arguments)
                    transcript += com.codingagent.model.ModelMessage("tool", "${response.name}: $toolResult", response.callId)
                    val success = !toolResult.startsWith("ERROR:")
                    if (success) {
                        when (response.name) {
                            "read_file" -> {
                                val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                                if (!path.isNullOrBlank()) readPaths += path.trim().trimStart('/')
                            }
                            "search_project", "list_files" -> searchedProject = true
                        }
                        // Only substantive local evidence closes the gather window.
                        // list_files alone must not lock out research_web.
                        // research_web / search_knowledge stay available until last turns.
                        val usefulBody = toolResult.isNotBlank() &&
                            toolResult != "(no files)" &&
                            !toolResult.equals("(no matches)", ignoreCase = true)
                        if (usefulBody && response.name in setOf("read_file", "search_project")) {
                            successfulGathers++
                        }
                    }
                    emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult, success))
                    if (success) {
                        consecutiveFailures = 0
                        if (response.name == "read_file") {
                            val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                            val focus = currentRequestFocus(normalized)
                            if (!path.isNullOrBlank() && (
                                    extractExplicitReadPath(focus) != null ||
                                        focus.lowercase().contains("read") ||
                                        focus.lowercase().contains("show") ||
                                        focus.lowercase().contains("open")
                                    )
                            ) {
                                val report = workspace.verify()
                                val summary = buildString {
                                    append("File: ${path.trim().trimStart('/')}\n───\n")
                                    append(toolResult.take(12_000))
                                    if (toolResult.length > 12_000) append("\n… (truncated)")
                                }
                                val task = AgentTask(
                                    taskId, normalized, "completed", plan,
                                    changeSets.flatMap { it.changes }, report,
                                    listOf("${Instant.now()}: answered read via read_file"),
                                    summary
                                )
                                journal.record(task)
                                emit(AutonomousAgentEvent.Completed(task))
                                return events
                            }
                        }
                        if (response.name == "list_files" || response.name == "search_project") {
                            if (isListingRequest(currentRequestFocus(normalized))) {
                                val report = workspace.verify()
                                val summary = formatListingSummary(toolResult, report, isSourceFileListRequest(currentRequestFocus(normalized)))
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
                            transcript += com.codingagent.model.ModelMessage(
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
        val report = workspace.verify()
        val usableEvidence = lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:")
        val summary = if (usableEvidence) {
            synthesizeFromEvidence(normalized, lastEvidence, report)
        } else {
            "The model used ${config.maxTurns} turns without producing a final answer or usable tool evidence. " +
                "Retry with a narrower request (one file or one question), or switch model in Model settings."
        }
        val task = AgentTask(
            taskId, normalized, if (usableEvidence) "completed-with-warning" else "failed", plan,
            changeSets.flatMap { it.changes }, report,
            listOf("${Instant.now()}: turn budget exhausted; evidence=${if (usableEvidence) "yes" else "no"}"),
            summary
        )
        journal.record(task)
        if (usableEvidence) {
            emit(AutonomousAgentEvent.Completed(task))
        } else {
            emit(AutonomousAgentEvent.Failed(task, summary))
        }
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

    /**
     * Research is how the agent gets smarter on things not in the project.
     * Run when the user asks for it, or when the task needs external knowledge.
     */
    private fun shouldResearch(request: String, intake: TaskIntake): Boolean {
        val lower = request.lowercase()
        // Current user line only. Do not scan chat history / agent copy.
        if (Regex("""\b(research|look up|search the web|google|web search|docs online)\b""")
                .containsMatchIn(lower)
        ) {
            return true
        }
        if (intake.intent == TaskIntent.EXPLAIN &&
            Regex("""\b(look into|investigate|documentation)\b""").containsMatchIn(lower)
        ) {
            return true
        }
        return false
    }

    /**
     * Compact repo map for the model (Aider-style). Paths only — not full file bodies.
     * Extension-filtered index; honest about that limit.
     */
    private fun repoMapSummary(maxPaths: Int = 100): String {
        val paths = files.listSourceFilePaths()
        val summary = workspace.summary()
        return buildString {
            append("Repo map — indexed sources: ${paths.size}")
            append(" (extension whitelist; not every file on disk).")
            if (summary.languages.isNotEmpty()) {
                append(" Languages: ")
                append(summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
            }
            append('\n')
            if (paths.isEmpty()) {
                append("(no indexed source files)\n")
            } else {
                paths.take(maxPaths).forEach { path ->
                    append(path)
                    append('\n')
                }
                if (paths.size > maxPaths) {
                    append("… and ${paths.size - maxPaths} more (use list_files / search_project)\n")
                }
            }
        }
    }

    private fun buildPrompt(request: String, intake: TaskIntake, evidence: String): String = buildString {
        appendLine("You are the Coding-Agent on this device. You extend the model with tools and real evidence — never invent paths or file contents.")
        appendLine()
        appendLine("Request:")
        appendLine(request)
        appendLine()
        val targets = intake.contract.targetPaths.joinToString().ifBlank { "none yet" }
        appendLine("Intent: ${intake.intent}")
        appendLine("Target paths: $targets")
        appendLine()
        appendLine("Operating rules for this turn:")
        appendLine("1. Gather real evidence with tools. Never invent file contents or paths.")
        appendLine("2. If the user names a file, call read_file on it before analysis or final answer.")
        appendLine("3. Exactly one tool call this turn. Observe the full result before the next step.")
        appendLine("4. Code changes only stage a proposal. Dual owner approval is required.")
        appendLine("5. Call verify after changes or when hunting bugs. Never report a fake pass.")
        appendLine("6. Use research_web when you lack current docs, APIs, errors, or practices not in the project.")
        appendLine("7. Persist until the goal is met. Only stop early for a specific missing user input.")
        appendLine("8. After real file reads or project search hits, WRITE THE ANSWER. Do not keep listing.")
        appendLine("9. Prefer research_web over guessing external APIs. Prefer project files over inventing local paths.")
        if (isWholeProjectReview(request)) {
            appendLine("10. This is a whole-project review. After real evidence, write concrete improvements.")
        }
        appendLine()
        appendLine("Evidence so far:")
        append(evidence.take(config.maxOutputCharacters.coerceAtMost(6_000)))
    }

    private fun executeTool(name: String, rawArguments: String): String {
        return tools.execute(name, rawArguments)
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

    private fun looksLikeRawToolMarkup(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("<tool_call") || t.contains("<function=") || t.contains("</tool_call>")
    }

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

    private fun formatListingSummary(listing: String, report: VerificationReport, namesOnly: Boolean = true): String {
        return buildString {
            if (namesOnly) {
                append("Indexed source files (extension whitelist — not a full disk listing):\n")
            } else {
                append("Directory listing:\n")
            }
            append(listing.trim().ifBlank { "(none)" })
            append("\n\nVerification: ")
            if (report.passed) {
                append("passed (static unfinished-work marker scan)")
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

    /**
     * Whole-project review/improve — still uses the model loop.
     * After two successful gathers, tools are removed so the model must write.
     */
    private fun isWholeProjectReview(request: String): Boolean {
        val t = request.lowercase()
        if (extractInspectTarget(request) != null) return false
        if (extractExplicitReadPath(request) != null) return false
        val review = Regex("""\b(review|analy[sz]e|audit|critique|improv)""")
        val scope = Regex("""\b(project|codebase|repo|repository|codebase|app)\b""")
        return review.containsMatchIn(t) && (scope.containsMatchIn(t) || t.length <= 90)
    }

    /**
     * Fallback only after the model had tools, then refused to write.
     * Uses gathered tool evidence — not a substitute for a model review when the model writes.
     */
    private fun synthesizeFromEvidence(request: String, evidence: String, report: VerificationReport): String {
        return buildString {
            append("Review from gathered evidence (model did not write a final after tools were closed).\n\n")
            append("Request: ").append(request.trim()).append("\n\n")
            append(evidence.take(config.maxOutputCharacters))
            append("\n\nVerification: ")
            if (report.passed) {
                append("passed (static unfinished-work marker scan)")
            } else {
                append("FAILED (").append(report.issues.size).append(" issue(s))")
                report.issues.take(20).forEach { issue ->
                    append("\n- ").append(issue.path).append(":").append(issue.line).append(" — ").append(issue.message)
                }
            }
            append("\n\nIf this is thinner than you wanted, retry once. The next run starts with this evidence already in context.")
        }
    }

    private fun isListingRequest(request: String): Boolean {
        val t = request.lowercase().trim()
        // Review / analyze / summarize the project must reach the model. Do not steal those as a dump.
        if (Regex("\\b(review|analy[sz]e|summarize|explain|inspect|describe|audit|critique|compare)\\b")
                .containsMatchIn(t)
        ) {
            return false
        }
        val listingHints = listOf(
            "list file", "list files", "list the file", "list project", "list the project",
            "show files", "show the files", "what files", "which files",
            "file list", "directory listing", "list source", "ls files"
        )
        if (listingHints.any { hint -> t.contains(hint) }) return true
        if (t == "list" || t == "ls" || t == "files") return true
        return false
    }

    /** Names only (AutonomousAgent.kt) vs relative paths when a directory/path was requested. */
    private fun isSourceFileListRequest(request: String): Boolean {
        val t = request.lowercase()
        if (t.contains("source file") || t.contains("source files") || t.contains("project source")) return true
        if (Regex("""\b(in|under|path|directory|folder)\b""").containsMatchIn(t)) return false
        if (t.contains("/") || t.contains("app/") || t.contains("src/")) return false
        return true
    }

    private fun currentRequestFocus(request: String): String {
        val marker = "Current request:"
        val idx = request.lastIndexOf(marker, ignoreCase = true)
        return if (idx >= 0) request.substring(idx + marker.length).trim().ifBlank { request } else request
    }

    private sealed class OfflineMutation {
        data class Approval(val task: AgentTask, val proposal: PendingChangeProposal) : OfflineMutation()
        data class NeedsInput(val task: AgentTask) : OfflineMutation()
        data class Failed(val task: AgentTask) : OfflineMutation()
    }

    /**
     * Stage dual-approval proposals for explicit replace/create (and offline synthesis)
     * without calling the model. Returns null when this path does not apply.
     */
    private fun stageOfflineMutation(
        taskId: String,
        request: String,
        intake: TaskIntake,
        plan: AgentPlan
    ): OfflineMutation? {
        val hasExplicit = intake.operation.kind != OperationKind.NONE
        val wantsEdit = hasExplicit ||
            intake.intent == TaskIntent.CHANGE ||
            intake.intent == TaskIntent.CREATE ||
            intake.intent == TaskIntent.REFACTOR
        if (!wantsEdit) return null
        // With a model configured, leave non-explicit edit intent to the tool loop.
        if (!hasExplicit && gateway != null) return null

        val staged: Pair<List<TaskOperation>, String> = if (hasExplicit) {
            listOf(intake.operation) to "Offline explicit ${intake.operation.kind.name.lowercase()} from request"
        } else {
            when (val synthesis = CodeSynthesisEngine(workspace.projectRoot(), knowledge).synthesize(intake)) {
                is SynthesisResult.Ready ->
                    synthesis.proposal.operations to "Offline synthesis: ${synthesis.proposal.rationale}"
                is SynthesisResult.NeedsInput -> {
                    val question = synthesis.question +
                        " Load a coding model, or specify an exact replace/create/append/remove operation."
                    val task = AgentTask(
                        taskId, request, "needs-input", plan, emptyList(),
                        VerificationReport(true, emptyList()),
                        listOf("${Instant.now()}: offline staging needs input"),
                        question
                    )
                    return OfflineMutation.NeedsInput(task)
                }
            }
        }
        val operations = staged.first
        val reason = staged.second

        return try {
            val proposal = mutations.propose(request, operations, reason)
            val task = AgentTask(
                taskId, request, "needs-approval", plan, proposal.changeSet.changes,
                VerificationReport(true, emptyList()),
                listOf("${Instant.now()}: offline proposal ${proposal.id} staged; awaiting two owner approvals"),
                "Review proposal ${proposal.id} and confirm twice before applying any code change."
            )
            OfflineMutation.Approval(task, proposal)
        } catch (error: Exception) {
            val message = "Offline mutation staging failed: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}"
            val task = AgentTask(
                taskId, request, "failed", plan, emptyList(),
                VerificationReport(false, emptyList()),
                listOf("${Instant.now()}: $message"),
                message
            )
            OfflineMutation.Failed(task)
        }
    }

    /**
     * Fast paths that must work without the model: greeting, status, indexed source listing,
     * explicit single-file read, named-file inspect. These give correct local evidence and
     * do not discard model capability — they avoid a model round-trip when tools alone suffice.
     */
    private fun directLaneResponse(
        taskId: String,
        request: String,
        intake: TaskIntake,
        plan: AgentPlan
    ): AgentTask? {
        val t = request.lowercase().trim()

        // Questions about the agent itself (why abort, why repeat tools) — answer from runtime behavior, not project verify.
        if (isAgentMetaQuestion(t)) {
            val text = agentMetaAnswer(t)
            return AgentTask(
                taskId, request, "completed", plan, emptyList(),
                VerificationReport(true, emptyList()),
                listOf("${Instant.now()}: agent-meta answer (no project verify)"),
                text
            )
        }

        val report = workspace.verify()

        if (isGreeting(t)) {
            val summary = workspace.summary()
            val text = buildString {
                append("Hello. Coding Agent is ready.\n")
                append("Project files indexed: ${summary.files.size} (source extensions only — not every file on disk).\n")
                append("Languages: ")
                append(
                    if (summary.languages.isEmpty()) "none detected"
                    else summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
                )
                append(".\n")
                append("Verification: ${if (report.passed) "passed (static marker scan)" else "FAILED (${report.issues.size} issue(s))"}.\n")
                append("Ask me to list files, read a path, analyze something, research a topic, or propose a change.")
            }
            return AgentTask(
                taskId, request, "completed", plan, emptyList(), report,
                listOf("${Instant.now()}: greeting / readiness"),
                text
            )
        }

        // Bare "list files" / "list source files": local indexed listing — no model round-trip.
        // Gives the model correct evidence when tools are used later; does not discard model output for this case.
        if (isListingRequest(t) && isSourceFileListRequest(t)) {
            val paths = files.listSourceFilePaths()
            val text = buildString {
                append("Indexed source files: ${paths.size}\n")
                append("(Extension whitelist only — not a full disk listing.)\n")
                if (paths.isEmpty()) {
                    append("(none)\n")
                } else {
                    paths.forEach { path ->
                        append(path)
                        append('\n')
                    }
                }
                append("\nVerification: ")
                append(if (report.passed) "passed (static unfinished-work marker scan)" else "FAILED (${report.issues.size} issue(s))")
            }
            return AgentTask(
                taskId, request, "completed", plan, emptyList(), report,
                listOf("${Instant.now()}: direct indexed source listing (${paths.size} files)"),
                text
            )
        }

        if (isStatusRequest(t)) {
            val summary = workspace.summary()
            val text = buildString {
                append("Status report\n")
                append("- Indexed files: ${summary.files.size}\n")
                append("- Symbols: ${summary.symbols}, imports: ${summary.imports}\n")
                append("- Languages: ")
                append(
                    if (summary.languages.isEmpty()) "none"
                    else summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
                )
                append("\n- Static verification: ")
                append(if (report.passed) "passed (0 unfinished-work markers)" else "FAILED (${report.issues.size} issue(s))")
                if (report.issues.isNotEmpty()) {
                    report.issues.take(15).forEach { issue ->
                        append("\n  - ${issue.path}:${issue.line} — ${issue.message}")
                    }
                }
                append("\n- Pending change proposals: ${mutations.pending().size}")
                append("\n- Intent classified as: ${intake.intent.name}")
            }
            return AgentTask(
                taskId, request, "completed", plan, emptyList(), report,
                listOf("${Instant.now()}: status report"),
                text
            )
        }

        // Explicit "read X" / "show contents of X" when a single path is named.
        val readPath = extractExplicitReadPath(request)
        if (readPath != null) {
            val resolved = resolveProjectPath(readPath) ?: readPath
            val content = runCatching { files.read(resolved).content }.getOrElse {
                return AgentTask(
                    taskId, request, "failed", plan, emptyList(),
                    VerificationReport(false, listOf(VerificationIssue(resolved, 0, it.message ?: "read failed"))),
                    listOf("${Instant.now()}: read failed for $resolved"),
                    "Could not read `$resolved`: ${it.message ?: it.javaClass.simpleName}"
                )
            }
            val text = buildString {
                append("File: $resolved\n")
                append("───\n")
                append(content.take(12_000))
                if (content.length > 12_000) append("\n… (truncated)")
            }
            return AgentTask(
                taskId, request, "completed", plan, emptyList(), report,
                listOf("${Instant.now()}: direct read_file $resolved"),
                text
            )
        }

        // Named-file inspect/analyze: local evidence first. Markers = policy, not errors.
        val inspectTarget = extractInspectTarget(request)
        if (inspectTarget != null) {
            val local = buildLocalFileReport(inspectTarget)
            if (local == null) {
                return AgentTask(
                    taskId, request, "failed", plan, emptyList(),
                    VerificationReport(false, listOf(VerificationIssue(inspectTarget, 0, "file not found in project index"))),
                    listOf("${Instant.now()}: inspect target not found: $inspectTarget"),
                    "Could not find `$inspectTarget` in the project. Try `list project source files`, then use the exact name."
                )
            }
            if (isMarkerOnlyRequest(request)) {
                return AgentTask(
                    taskId, request, "completed", plan, emptyList(),
                    local.report,
                    listOf("${Instant.now()}: local policy scan ${local.path}"),
                    local.asUserText(includePolicy = true, includeStructure = false)
                )
            }
            val text = buildString {
                append(local.asUserText(includePolicy = true, includeStructure = true))
                append("\n\n")
                append("Scope note: local evidence only.\n")
                append("- Policy markers (TODO/FIXME/stub) are rule violations, not compile errors.\n")
                append("- Structure notes are heuristics, not a compiler.\n")
                append("- For logic/API/compile diagnosis, retry when the model is not rate-limited, or switch provider in Model settings.")
            }
            return AgentTask(
                taskId, request, "completed", plan, emptyList(),
                local.report,
                listOf("${Instant.now()}: local evidence package ${local.path}"),
                text
            )
        }

        return null
    }

    /** Follow-ups about the agent runtime (abort, tool loops), not about project source. */
    private fun isAgentMetaQuestion(t: String): Boolean {
        if (t.length > 120) return false
        val aboutAgent = Regex("""\b(why|what)\b.*\b(abort|aborted|stop|stopped|fail|failed|repeat|repeated|loop|tool)\b""")
        val shortWhy = Regex("""^why\s+(would|did|does|is|was)\b""")
        return aboutAgent.containsMatchIn(t) || (shortWhy.containsMatchIn(t) && t.length < 60)
    }

    private fun agentMetaAnswer(t: String): String {
        val aboutRepeat = Regex("""\b(repeat|repeated|identically|loop|same\s+tool|read_file)\b""").containsMatchIn(t)
        return if (aboutRepeat || t.contains("abort") || t.contains("stop")) {
            "The agent stopped a tool loop: the model called the same tool with the same arguments " +
                "several times in a row. That used to hard-abort the task. It now forces a final answer " +
                "from evidence already gathered (or nudges a different tool) instead of aborting. " +
                "Retry the original request if you still need the review."
        } else {
            "That question is about the agent runtime, not your project source. " +
                "Say what you wanted done (review, list files, read a path, fix a bug) and I will run that."
        }
    }

    private fun isGreeting(t: String): Boolean {
        if (t.length > 40) return false
        val greetings = listOf(
            "hi", "hello", "hey", "yo", "sup", "hi there", "hello there",
            "good morning", "good afternoon", "good evening", "ping", "you there",
            "are you there", "are you working", "can you hear me"
        )
        return greetings.any { t == it || t.startsWith("$it ") || t.startsWith("$it?") || t.startsWith("$it,") }
    }

    private fun isStatusRequest(t: String): Boolean {
        // Word-boundary matches only — "Helper" must not match the hint "help".
        val exact = setOf("status", "help", "capabilities")
        if (t in exact) return true
        val phrases = listOf(
            "status report", "give me a status", "system status",
            "are you ready", "what can you do", "project status",
            "how many files", "summary of the project"
        )
        if (phrases.any { t == it || t.contains(it) }) return true
        // Standalone "status" / "help" as whole words only.
        if (Regex("""status""").containsMatchIn(t) && t.length <= 48) return true
        if (Regex("""help""").containsMatchIn(t) && !Regex("""(helper|helpers)""").containsMatchIn(t) && t.length <= 32) {
            return true
        }
        return false
    }

    private fun extractExplicitReadPath(request: String): String? {
        val patterns = listOf(
            Regex("""(?is)^\s*read(?:\s+file)?\s+[`'"]?([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]+)[`'"]?\s*$"""),
            Regex("""(?is)^\s*show(?:\s+me)?(?:\s+the)?(?:\s+contents?(?:\s+of)?)?\s+[`'"]?([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]+)[`'"]?\s*$"""),
            Regex("""(?is)^\s*open\s+[`'"]?([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]+)[`'"]?\s*$""")
        )
        for (re in patterns) {
            val m = re.matchEntire(request.trim()) ?: continue
            val path = m.groupValues[1].trim().trimStart('/')
            if (path.isNotBlank()) return path
        }
        return null
    }


    private fun extractInspectTarget(request: String): String? {
        val patterns = listOf(
            Regex("""(?is)\b(?:analyze|inspect|check|review)\s+(?:the\s+)?[`'"]?([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]+)[`'"]?"""),
            Regex("""(?is)\b(?:errors?|bugs?|issues?)\s+in\s+[`'"]?([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]+)[`'"]?"""),
            Regex("""(?is)[`'"]?([A-Za-z0-9_./\\-]+\.[A-Za-z0-9]+)[`'"]?\s+for\s+errors?""")
        )
        for (re in patterns) {
            val m = re.find(request.trim()) ?: continue
            val path = m.groupValues[1].trim().trimStart('/')
            if (path.isNotBlank()) return path
        }
        return null
    }

    private fun isMarkerOnlyRequest(request: String): Boolean {
        val lower = request.lowercase()
        val markerWords = listOf("todo", "fixme", "stub", "placeholder", "unfinished", "marker")
        val errorWords = listOf("error", "bug", "crash", "exception", "compile", "analyze", "logic")
        return markerWords.any { it in lower } && errorWords.none { it in lower }
    }

    private fun resolveProjectPath(nameOrPath: String): String? {
        val normalized = nameOrPath.trim().trimStart('/').replace('\\', '/')
        if (normalized.isBlank()) return null
        runCatching {
            files.read(normalized)
            return normalized
        }
        val base = java.io.File(normalized).name
        val hits = workspace.summary().files.filter {
            java.io.File(it.path).name.equals(base, ignoreCase = true)
        }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0].path
            else -> hits.minByOrNull { it.path.length }?.path
        }
    }

    private data class LocalFileReport(
        val path: String,
        val content: String,
        val policyIssues: List<VerificationIssue>,
        val structureNotes: List<String>,
        val report: VerificationReport
    ) {
        fun asUserText(includePolicy: Boolean, includeStructure: Boolean): String = buildString {
            append("File: $path\n")
            append("Size: ${content.length} chars, ${content.lines().size} lines\n")
            if (includePolicy) {
                append("\nPolicy scan (unfinished-work markers — not compiler errors):\n")
                if (policyIssues.isEmpty()) {
                    append("- No TODO / FIXME / stub markers in this file.\n")
                } else {
                    policyIssues.forEach { issue ->
                        append("- line ${issue.line}: ${issue.message}\n")
                    }
                }
            }
            if (includeStructure) {
                append("\nStructure heuristics (not a compiler):\n")
                if (structureNotes.isEmpty()) {
                    append("- No obvious brace/paren imbalance detected.\n")
                } else {
                    structureNotes.forEach { append("- $it\n") }
                }
            }
        }
    }

    private fun buildLocalFileReport(nameOrPath: String): LocalFileReport? {
        val resolved = resolveProjectPath(nameOrPath) ?: return null
        val content = runCatching { files.read(resolved).content }.getOrNull() ?: return null
        val all = workspace.verify().issues
        val policy = all.filter { issue ->
            val ip = issue.path.replace('\\', '/')
            ip == resolved || ip.endsWith("/$resolved") ||
                java.io.File(ip).name.equals(java.io.File(resolved).name, ignoreCase = true)
        }
        val notes = structureHeuristics(content)
        return LocalFileReport(
            path = resolved,
            content = content,
            policyIssues = policy,
            structureNotes = notes,
            report = VerificationReport(policy.isEmpty(), policy)
        )
    }

    /** Cheap structural signals only — never reported as hard compiler errors. */
    private fun structureHeuristics(content: String): List<String> {
        val notes = mutableListOf<String>()
        fun balance(open: Char, close: Char, label: String) {
            var n = 0
            var inString = false
            var inChar = false
            var escape = false
            var i = 0
            while (i < content.length) {
                val c = content[i]
                if (escape) { escape = false; i++; continue }
                if (c == '\\' && (inString || inChar)) { escape = true; i++; continue }
                if (!inChar && c == '"') { inString = !inString; i++; continue }
                if (!inString && c == '\'') { inChar = !inChar; i++; continue }
                if (inString || inChar) { i++; continue }
                if (c == open) n++
                if (c == close) n--
                i++
            }
            if (n != 0) {
                notes += if (n > 0) "$label imbalance: extra $open ($n)" else "$label imbalance: extra $close (${-n})"
            }
        }
        balance('{', '}', "Brace")
        balance('(', ')', "Paren")
        balance('[', ']', "Bracket")
        val last = content.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (last.endsWith("=") || last.endsWith(".")) {
            notes += "File ends mid-expression (line ends with '${last.last()}')"
        }
        return notes
    }

    /**
     * Classify provider failures into one actionable line instead of raw HTTP/JSON walls.
     */

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
            verification = VerificationReport(true, emptyList()),
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
