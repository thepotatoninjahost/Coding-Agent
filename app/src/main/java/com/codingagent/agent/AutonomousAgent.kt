package com.codingagent.agent

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.model.AgentModelProtocol
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.JsonModelResponseParser
import com.codingagent.research.DeepResearchProvider
import com.codingagent.research.DurableDeepResearchProvider
import com.codingagent.research.ResearchBriefBuilder
import com.codingagent.research.ResearchModeDetector
import com.codingagent.workspace.ChangeSet
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.PendingChangeProposal
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession
import com.codingagent.workspace.VerificationReport
import com.codingagent.workspace.AgentTask

/**
 * ONE JOB: Sequence one agent run. Classification, tools, evidence, and answers live in single-job modules.
 */
class AutonomousAgent(
    private val root: java.io.File,
    private val knowledge: AgentKnowledge,
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
    private val cancelled = AtomicBoolean(false)
    private val tools = AgentToolDispatch(
        workspace, files, knowledge, research, mutations, terminal, config.maxOutputCharacters,
        onChangeApplied = { changeSets += it }
    )

    @Volatile private var lastCancelReason: String = "Stopped by owner"

    fun cancel(reason: String = "Stopped by owner") { cancelled.set(true); lastCancelReason = reason }
    fun isCancelled(): Boolean = cancelled.get()
    fun pendingProposals(): List<PendingChangeProposal> = mutations.pending()
    fun approveProposal(id: String, ownerVerified: Boolean, ownerLabel: String, channel: ApprovalChannel): MutationApprovalResult =
        mutations.approve(id, ownerVerified, ownerLabel, channel)
    fun rejectProposal(id: String): Boolean = mutations.reject(id)

    override fun execute(request: String): AgentRuntimeResult {
        val events = run(request)
        return when (val t = events.lastOrNull()) {
            is AutonomousAgentEvent.ApprovalRequired ->
                AgentRuntimeResult.NeedsApproval(t.task, "Review proposal ${t.proposal.id}. Fingerprint + spoken password required before applying.", t.proposal.id)
            is AutonomousAgentEvent.Completed -> AgentRuntimeResult.Completed(t.task)
            is AutonomousAgentEvent.Failed -> t.task?.let { AgentRuntimeResult.Failed(it) } ?: error(t.message)
            is AutonomousAgentEvent.Stopped -> AgentRuntimeResult.Failed(t.task)
            else -> error("Autonomous agent ended without a terminal result")
        }
    }

    fun run(request: String, onEvent: (AutonomousAgentEvent) -> Unit = {}): List<AutonomousAgentEvent> {
        cancelled.set(false)
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val taskId = UUID.randomUUID().toString()
        val events = mutableListOf<AutonomousAgentEvent>(AutonomousAgentEvent.Started(taskId, normalized))
        fun emit(e: AutonomousAgentEvent) { events += e; onEvent(e) }

        emit(AutonomousAgentEvent.Phase("INTAKE", "Inspecting the request and repository"))
        val focus = AgentRequestFocus.current(normalized)
        val intake = TaskIntakeParser(root).parse(focus)
        val plan = AgentPlanner(workspace).plan(intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.steps.joinToString(" → ") { it.phase }))

        AgentDirectLanes.respond(taskId, focus, intake, plan, workspace, files, mutations)?.let {
            journal.record(it); emit(AutonomousAgentEvent.Completed(it)); return events
        }

        AgentOfflineStager.stage(taskId, focus, intake, plan, workspace, knowledge, mutations, gateway)?.let { offline ->
            when (offline) {
                is AgentOfflineMutation.Approval -> {
                    journal.record(offline.task); emit(AutonomousAgentEvent.ApprovalRequired(offline.task, offline.proposal)); return events
                }
                is AgentOfflineMutation.NeedsInput -> {
                    journal.record(offline.task); emit(AutonomousAgentEvent.Completed(offline.task)); return events
                }
                is AgentOfflineMutation.Failed -> {
                    journal.record(offline.task); emit(AutonomousAgentEvent.Failed(offline.task, offline.task.summary)); return events
                }
            }
        }

        if (!intake.executionReady) {
            val q = intake.clarificationQuestion ?: "Clarify the requested operation"
            val task = AgentTask(taskId, focus, "needs-input", plan, emptyList(), VerificationReport(true, emptyList()), listOf("${Instant.now()}: needs input from user"), q)
            emit(AutonomousAgentEvent.Completed(task)); journal.record(task); return events
        }

        val activeGateway = gateway
        if (activeGateway == null) {
            val msg = "Model is not configured. Local commands still work: hello, list files, status, read <path>, and explicit replace/create when fully specified. Open Model settings for autonomous coding."
            val task = AgentTask(taskId, focus, "needs-input", plan, emptyList(), VerificationReport(true, emptyList()), listOf("${Instant.now()}: model gateway missing"), msg)
            emit(AutonomousAgentEvent.Completed(task)); journal.record(task); return events
        }

        var researchEvidence = ""
        if (ResearchGate.shouldAutoResearch(normalized, intake)) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events, ::emit)
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Looking up external sources"))
            val mode = ResearchModeDetector.detect(normalized)
            val session = runCatching {
                research.deepResearch(normalized, 8, mode) { progress ->
                    if (cancelled.get()) return@deepResearch
                    emit(AutonomousAgentEvent.Phase("RESEARCH", "${progress.stage}: ${progress.completed}/${progress.total}; learned ${progress.successful}, failed ${progress.failed}"))
                }
            }.getOrNull()
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events, ::emit)
            if (session != null && session.sources.isNotEmpty()) {
                val brief = ResearchBriefBuilder.build(session)
                researchEvidence = "\n\nResearch brief:\n${brief.evidence}"
                emit(AutonomousAgentEvent.Phase("RESEARCH", "Learned ${brief.sourceCount} sources (${brief.wordCount} words)"))
            } else {
                researchEvidence = "\n\nRESEARCH RESULT: no usable sources were retrieved from the network. " +
                    "Do not invent APIs, versions, docs, or facts. State clearly that research returned nothing usable."
                emit(AutonomousAgentEvent.Phase("RESEARCH", "No usable sources — honesty requires saying so, not inventing"))
            }
        } else {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Skipped — local project is enough"))
        }

        val transcript = mutableListOf<com.codingagent.model.ModelMessage>()
        var lastEvidence = AgentPrompt.repoMap(files, workspace) + "\nCall list_files or search_project before read_file. Do not invent paths." + researchEvidence
        var consecutiveFailures = 0
        var lastToolSignature: String? = null
        var identicalRepeats = 0
        val readPaths = linkedSetOf<String>()
        var searchedProject = false
        var evidenceRefusals = 0
        var successfulGathers = 0
        var writeNowRefusals = 0
        var writeNowAnnounced = false
        val reviewJob = AgentRequestKind.isWholeProjectReview(focus)

        for (turn in 0 until config.maxTurns) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events, ::emit)
            emit(AutonomousAgentEvent.Phase("MODEL", "Decision turn ${turn + 1}/${config.maxTurns}"))
            val decision = LoopControl.decide(turn, config.maxTurns, successfulGathers, writeNowRefusals, intake.intent, reviewJob)
            val writeNow = decision.demandWrite
            val toolsThisTurn = if (decision.toolsOpen) AgentModelProtocol.tools() else emptyList()
            if (writeNow && !writeNowAnnounced) {
                writeNowAnnounced = true
                transcript += com.codingagent.model.ModelMessage("user", "SYSTEM: You already have project evidence. Do NOT call any tool. Stage replace_text/create_file if changing, else write the answer from evidence.")
            }
            var response = activeGateway.complete(ModelRequest(AgentModelProtocol.SYSTEM, AgentPrompt.build(normalized, intake, lastEvidence, config.maxOutputCharacters), toolsThisTurn, transcript.toList(), researchRequired = false))
            if (response is ModelResponse.Failure && (ModelFailure.isRateLimit(response.message) || ModelFailure.isEmpty(response.message))) {
                if (ModelFailure.isRateLimit(response.message)) {
                    val waitSec = ModelFailure.waitSeconds(response.message).coerceIn(1, 45)
                    emit(AutonomousAgentEvent.Phase("MODEL", "Rate limited — waiting ${waitSec}s then retrying once"))
                    try { Thread.sleep(waitSec * 1000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                } else emit(AutonomousAgentEvent.Phase("MODEL", "Empty model response — retrying once"))
                if (cancelled.get()) return stopNow(taskId, normalized, plan, events, ::emit)
                response = activeGateway.complete(ModelRequest(AgentModelProtocol.SYSTEM, AgentPrompt.build(normalized, intake, lastEvidence, config.maxOutputCharacters), toolsThisTurn, transcript.toList(), researchRequired = false))
            }
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events, ::emit)
            when (response) {
                is ModelResponse.Failure -> {
                    val friendly = ModelFailure.humanize(response.message)
                    val named = AgentRequestKind.inspectTarget(normalized) ?: AgentRequestKind.explicitReadPath(normalized)
                    val localExtra = named?.let { LocalFileEvidence.report(it, files, workspace) }?.asUserText(true, true)
                    val summary = if (localExtra != null) "$friendly\n\nLocal evidence:\n$localExtra" else friendly
                    val task = failedTask(taskId, normalized, plan, summary)
                    emit(AutonomousAgentEvent.Failed(task, summary)); journal.record(task); return events
                }
                is ModelResponse.Text -> {
                    val recovered = JsonModelResponseParser().parse(response.content)
                    if (recovered is ModelResponse.ToolCall) response = recovered
                    if (response is ModelResponse.Text && looksLikeRawToolMarkup(response.content)) {
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content.take(800))
                        transcript += com.codingagent.model.ModelMessage("user", "SYSTEM: That was a raw tool dump. Write the answer in plain English from evidence. No XML.")
                        continue
                    }
                    if (response is ModelResponse.ToolCall) {
                        emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments))
                        val toolResult = tools.execute(response.name, response.arguments)
                        if (toolResult.isNotBlank() && toolResult != "(no files)") lastEvidence = toolResult
                        val success = !toolResult.startsWith("ERROR:")
                        emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult, success))
                        transcript += com.codingagent.model.ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, response.callId, response.name, response.arguments)
                        transcript += com.codingagent.model.ModelMessage("tool", "${response.name}: $toolResult", response.callId)
                        val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                        if (success && response.name == "read_file" && !path.isNullOrBlank()) readPaths += path.trim().trimStart('/')
                        if (success && response.name in setOf("search_project", "list_files")) searchedProject = true
                        if (success && response.name in setOf("read_file", "search_project")) successfulGathers++
                        continue
                    }
                    emit(AutonomousAgentEvent.ModelMessage(response.content))
                    val missing = EvidenceRequirement.missingMessage(intake, readPaths, searchedProject, workspace)
                    if (missing != null) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.Phase("EVIDENCE", missing))
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val msg = "Refused to complete without reading the target file(s). $missing"
                            val task = failedTask(taskId, normalized, plan, msg)
                            emit(AutonomousAgentEvent.Failed(task, msg)); journal.record(task); return events
                        }
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content.take(1200))
                        transcript += com.codingagent.model.ModelMessage("user", "SYSTEM CONSTRAINT: $missing Call read_file on the target path before any final report.")
                        continue
                    }
                    val report = workspace.verify()
                    val focusLine = AgentRequestFocus.current(normalized)
                    val useListing = AgentRequestKind.isListing(focusLine) && searchedProject && lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:")
                    val summary = if (useListing) AnswerFromEvidence.formatListing(lastEvidence, report, AgentRequestKind.isSourceFileList(focusLine))
                    else AnswerFromEvidence.sanitizeModelText(response.content, report)
                    val status = when {
                        useListing -> "completed"
                        DegenerateOutput.isDegenerate(response.content) -> "completed-with-warning"
                        !report.passed -> "completed-with-issues"
                        else -> "completed"
                    }
                    val task = AgentTask(taskId, normalized, status, plan, changeSets.flatMap { it.changes }, report, listOf("${Instant.now()}: model reply"), summary)
                    journal.record(task); emit(AutonomousAgentEvent.Completed(task)); return events
                }
                is ModelResponse.ToolCall -> {
                    if (writeNow) {
                        writeNowRefusals++
                        if (decision.synthesizeFromEvidence || writeNowRefusals >= 2) {
                            val report = workspace.verify()
                            val summary = AnswerFromEvidence.synthesize(normalized, lastEvidence, report, config.maxOutputCharacters)
                            val task = AgentTask(taskId, normalized, "completed-with-warning", plan, changeSets.flatMap { it.changes }, report, listOf("${Instant.now()}: finished from evidence"), summary)
                            journal.record(task); emit(AutonomousAgentEvent.Completed(task)); return events
                        }
                        transcript += com.codingagent.model.ModelMessage("user", "SYSTEM: Tools are closed. Write the review in plain text now.")
                        continue
                    }
                    val signature = "${response.name}|${response.arguments.trim()}"
                    if (signature == lastToolSignature) {
                        identicalRepeats++
                        if (identicalRepeats >= config.maxIdenticalToolRepeats) {
                            if (lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:") && AgentRequestKind.isListing(AgentRequestFocus.current(normalized)) && response.name in setOf("list_files", "search_project")) {
                                val report = workspace.verify()
                                val summary = AnswerFromEvidence.formatListing(lastEvidence, report, AgentRequestKind.isSourceFileList(AgentRequestFocus.current(normalized)))
                                val task = AgentTask(taskId, normalized, "completed", plan, changeSets.flatMap { it.changes }, report, listOf("${Instant.now()}: stopped identical loop"), summary)
                                journal.record(task); emit(AutonomousAgentEvent.Completed(task)); return events
                            }
                            if (lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:")) {
                                transcript += com.codingagent.model.ModelMessage("user", "SYSTEM: Tool ${response.name} repeated. Do NOT call tools again. Write a final answer from evidence.")
                                lastToolSignature = ""; identicalRepeats = 0; continue
                            }
                            transcript += com.codingagent.model.ModelMessage("user", "SYSTEM: ${response.name} repeated with nothing useful. Call a different tool or give a final answer.")
                            lastToolSignature = ""; identicalRepeats = 0; continue
                        }
                    } else { lastToolSignature = signature; identicalRepeats = 1 }

                    emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments))
                    if (cancelled.get()) return stopNow(taskId, normalized, plan, events, ::emit)
                    val toolResult = tools.execute(response.name, response.arguments)
                    if (toolResult.isNotBlank() && toolResult != "(no files)") lastEvidence = toolResult
                    transcript += com.codingagent.model.ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, response.callId, response.name, response.arguments)
                    transcript += com.codingagent.model.ModelMessage("tool", "${response.name}: $toolResult", response.callId)
                    val success = !toolResult.startsWith("ERROR:")
                    emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult, success))
                    if (success) {
                        consecutiveFailures = 0
                        when (response.name) {
                            "read_file" -> {
                                val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                                if (!path.isNullOrBlank()) readPaths += path.trim().trimStart('/')
                            }
                            "search_project", "list_files" -> searchedProject = true
                        }
                        val useful = toolResult.isNotBlank() && toolResult != "(no files)" && !toolResult.equals("(no matches)", true)
                        if (useful && response.name in setOf("read_file", "search_project")) successfulGathers++
                        if (response.name == "list_files" || response.name == "search_project") {
                            if (AgentRequestKind.isListing(AgentRequestFocus.current(normalized))) {
                                val report = workspace.verify()
                                val summary = AnswerFromEvidence.formatListing(toolResult, report, AgentRequestKind.isSourceFileList(AgentRequestFocus.current(normalized)))
                                val task = AgentTask(taskId, normalized, "completed", plan, changeSets.flatMap { it.changes }, report, listOf("${Instant.now()}: listing via ${response.name}"), summary)
                                journal.record(task); emit(AutonomousAgentEvent.Completed(task)); return events
                            }
                        }
                        if (response.name == "read_file") {
                            val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                            val line = AgentRequestFocus.current(normalized)
                            if (!path.isNullOrBlank() && (AgentRequestKind.explicitReadPath(line) != null || listOf("read","show","open").any { line.lowercase().contains(it) })) {
                                val report = workspace.verify()
                                val summary = "File: ${path.trim().trimStart('/')}\n───\n${toolResult.take(12000)}"
                                val task = AgentTask(taskId, normalized, "completed", plan, changeSets.flatMap { it.changes }, report, listOf("${Instant.now()}: read_file"), summary)
                                journal.record(task); emit(AutonomousAgentEvent.Completed(task)); return events
                            }
                        }
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= config.maxConsecutiveToolFailures) {
                            val msg = "Aborted after $consecutiveFailures consecutive tool failures (last: ${response.name})"
                            val task = failedTask(taskId, normalized, plan, msg)
                            emit(AutonomousAgentEvent.Failed(task, msg)); journal.record(task); return events
                        }
                    }
                    val proposalId = toolResult.substringAfter("PROPOSAL_READY id=", "").substringBefore(' ').takeIf { it.isNotBlank() }
                    if (proposalId != null) {
                        val proposal = mutations.get(proposalId)
                        if (proposal == null) {
                            val task = failedTask(taskId, normalized, plan, "Proposal disappeared: $proposalId")
                            emit(AutonomousAgentEvent.Failed(task, task.summary)); journal.record(task)
                        } else {
                            val task = approvalTask(taskId, normalized, plan, proposal)
                            emit(AutonomousAgentEvent.ApprovalRequired(task, proposal)); journal.record(task)
                        }
                        return events
                    }
                }
            }
        }
        val report = workspace.verify()
        val usable = lastEvidence.isNotBlank() && !lastEvidence.startsWith("ERROR:")
        val summary = if (usable) AnswerFromEvidence.synthesize(normalized, lastEvidence, report, config.maxOutputCharacters)
        else "The model used ${config.maxTurns} turns without a final answer or usable evidence. Retry narrower, or switch model."
        val task = AgentTask(taskId, normalized, if (usable) "completed-with-warning" else "failed", plan, changeSets.flatMap { it.changes }, report, listOf("${Instant.now()}: turn budget exhausted"), summary)
        journal.record(task)
        if (usable) emit(AutonomousAgentEvent.Completed(task)) else emit(AutonomousAgentEvent.Failed(task, summary))
        return events
    }

    private fun stopNow(taskId: String, request: String, plan: AgentPlan, events: MutableList<AutonomousAgentEvent>, emit: (AutonomousAgentEvent) -> Unit): List<AutonomousAgentEvent> {
        val message = lastCancelReason
        val task = AgentTask(taskId, request, "stopped", plan, changeSets.flatMap { it.changes }, VerificationReport(false, emptyList()), listOf("${Instant.now()}: $message"), message)
        journal.record(task); emit(AutonomousAgentEvent.Stopped(task, message)); return events
    }

    private fun looksLikeRawToolMarkup(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("<tool_call") || t.contains("<function=") || t.contains("</tool_call>")
    }

    private fun failedTask(id: String, request: String, plan: AgentPlan, message: String): AgentTask =
        AgentTask(id, request, "failed", plan, changeSets.flatMap { it.changes }, VerificationReport(true, emptyList()), listOf("${Instant.now()}: $message"), message)

    private fun approvalTask(id: String, request: String, plan: AgentPlan, proposal: PendingChangeProposal): AgentTask =
        AgentTask(id, request, "waiting-approval", plan, proposal.changeSet.changes, proposal.verification, listOf("${Instant.now()}: proposal ${proposal.id} staged"), "Review proposal ${proposal.id}. Fingerprint + spoken password required before applying")
}
