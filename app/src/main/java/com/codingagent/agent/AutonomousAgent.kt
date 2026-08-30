package com.codingagent.agent
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentStep

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.codingagent.intake.OperationKind
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
    // Wired in: was previously dead code. Records every task outcome to
    // .coding-agent/experience.tsv via recordTask() below (defensive: never crashes a run).
    private val experience = ExperienceRecorder(root)
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

    private fun recordTask(task: AgentTask) {
        journal.record(task)
        runCatching {
            experience.record(
                task = task.request,
                operation = task.status,
                result = task.summary.take(2_000),
                evidence = task.changes.joinToString(", ") { it.path },
                passed = task.verification.passed
            )
        }
    }

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
        // Wired in: was previously dead code. Every call below is defensively wrapped
        // (runCatching) — PlanningLoop can at worst no-op, never crash a live run, since
        // this file can't be compiled/tested in this environment before shipping.
        val planningLoop = PlanningLoop(plan)
        // Wired in: was previously dead code. Tracks the model's actual tool calls against
        // an ideal fixed tool sequence for observability/journaling only — it never drives
        // or gates the real turn loop below, which stays fully model-directed.
        val toolSelectionLoop = ToolSelectionLoop(ToolSelector().select(intake))
        emit(AutonomousAgentEvent.Phase("PLAN", plan.steps.joinToString(" → ") { it.phase }))

        // Direct lanes: do not force the full tool loop for social / status / explicit read.
        lanes.respond(taskId, focus, intake, plan)?.let { task ->
            recordTask(task)
            emit(AutonomousAgentEvent.Completed(task))
            return events
        }

        // Offline edits before executionReady so create/replace with a clear target still stages without a model.
        AgentOfflineStager.stage(taskId, focus, intake, plan, workspace, knowledge, mutations, gateway)?.let { offline ->
            when (offline) {
                is AgentOfflineMutation.Approval -> {
                    recordTask(offline.task)
                    emit(AutonomousAgentEvent.ApprovalRequired(offline.task, offline.proposal))
                    return events
                }
                is AgentOfflineMutation.NeedsInput -> {
                    recordTask(offline.task)
                    emit(AutonomousAgentEvent.Completed(offline.task))
                    return events
                }
                is AgentOfflineMutation.Failed -> {
                    recordTask(offline.task)
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
            recordTask(task)
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
            recordTask(task)
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
        val state = ToolTurnState()
        state.lastEvidence = buildString {
            append(repoMapSummary())
            append("\nCall list_files or search_project before read_file. Do not invent paths.")
            append(researchEvidence)
        }
        var evidenceRefusals = 0
        var writeNowAnnounced = false
        val reviewJob = isWholeProjectReview(focus)
        val toolCallHandler = ToolCallOutcomeHandler(
            config = config,
            workspace = workspace,
            mutations = mutations,
            planningLoop = planningLoop,
            toolSelectionLoop = toolSelectionLoop,
            executeTool = ::executeTool,
            isListingRequest = ::isListingRequest,
            isSourceFileListRequest = ::isSourceFileListRequest,
            currentRequestFocus = ::currentRequestFocus,
            extractExplicitReadPath = ::extractExplicitReadPath,
            formatListingSummary = ::formatListingSummary,
            synthesizeFromEvidence = ::synthesizeFromEvidence,
            recordTask = ::recordTask,
            emit = { emit(it) },
            isCancelled = { cancelled.get() }
        )

        for (turn in 0 until config.maxTurns) {
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("MODEL", "Decision turn ${turn + 1}/${config.maxTurns}"))
            val decision = LoopControl.decide(
                turn = turn,
                maxTurns = config.maxTurns,
                usefulGathers = state.successfulGathers,
                writeRefusals = state.writeNowRefusals,
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
            var response: ModelResponse = ModelCallWithRetry.call(
                gateway = activeGateway,
                request = {
                    ModelRequest(
                        AgentModelProtocol.SYSTEM,
                        buildPrompt(focus, intake, state.lastEvidence),
                        toolsThisTurn,
                        transcript.toList(),
                        researchRequired = false
                    )
                },
                isCancelled = { cancelled.get() },
                onPhase = { emit(AutonomousAgentEvent.Phase("MODEL", it)) }
            ) ?: return stopNow(taskId, normalized, plan, events) { emit(it) }
            if (cancelled.get()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            // Some providers (NVIDIA NIM and others) never populate structured tool_calls and
            // instead emit the tool call as XML inside the text body. Recover that BEFORE the
            // dispatch below so it goes through the exact same ToolCall handling (proposal
            // surfacing, loop/failure limits, early-completion shortcuts) as a native tool call,
            // instead of a second, partial copy of that logic.
            if (response is ModelResponse.Text) {
                val recovered = JsonModelResponseParser().parse(response.content)
                if (recovered is ModelResponse.ToolCall) {
                    response = recovered
                }
            }
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
                    recordTask(task)
                    return events
                }
                is ModelResponse.Text -> {
                    // Raw tool markup that isn't parseable into a real call (bad XML, unknown
                    // tag shape, etc.) is a dump, not an answer — nudge instead of accepting it.
                    if (looksLikeRawToolMarkup(response.content)) {
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content.take(800))
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM: That was a raw tool dump, not an answer. " +
                                "Write the review in plain English from evidence already gathered. No XML. No tool tags."
                        )
                        continue
                    }
                    emit(AutonomousAgentEvent.ModelMessage(response.content))
                    val missing = missingEvidenceMessage(intake, state.readPaths, state.searchedProject)
                    if (missing != null) {
                        evidenceRefusals++
                        emit(AutonomousAgentEvent.Phase("EVIDENCE", missing))
                        if (evidenceRefusals > config.maxEvidenceRefusals) {
                            val msg = "Refused to complete without reading the target file(s). $missing"
                            val task = failedTask(taskId, normalized, plan, msg, changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, msg))
                            recordTask(task)
                            return events
                        }
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content.take(1_200))
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM CONSTRAINT: $missing You must call the read_file tool on the target path before any final report. Do not invent file contents."
                        )
                        state.lastEvidence = missing + "\n\n" + state.lastEvidence.take(config.maxOutputCharacters / 2)
                        continue
                    }
                    val report = workspace.verify()
                    // Listing requests: prefer real tool evidence over model prose (which is often spam).
                    val useListingEvidence = isListingRequest(currentRequestFocus(normalized)) &&
                        state.searchedProject &&
                        state.lastEvidence.isNotBlank() &&
                        !state.lastEvidence.startsWith("ERROR:")
                    val summary = if (useListingEvidence) {
                        formatListingSummary(state.lastEvidence, report, isSourceFileListRequest(currentRequestFocus(normalized)))
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
                        if (state.readPaths.isNotEmpty()) append("read=").append(state.readPaths.joinToString())
                        if (state.searchedProject) {
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
                    recordTask(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return events
                }
                is ModelResponse.ToolCall -> {
                    val outcome = toolCallHandler.handle(
                        response = response,
                        state = state,
                        writeNow = writeNow,
                        changeWork = changeWork,
                        decision = decision,
                        taskId = taskId,
                        normalized = normalized,
                        plan = plan,
                        transcript = transcript,
                        changes = { changeSets.flatMap { it.changes } }
                    )
                    when (outcome) {
                        ToolTurnOutcome.Continue -> continue
                        ToolTurnOutcome.Stop -> return events
                    }
                }
            }
        }
        val report = workspace.verify()
        val usableEvidence = state.lastEvidence.isNotBlank() && !state.lastEvidence.startsWith("ERROR:")
        val summary = if (usableEvidence) {
            synthesizeFromEvidence(normalized, state.lastEvidence, report)
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
        recordTask(task)
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
        val task = AgentTaskBuilders.stopped(taskId, request, plan, changeSets.flatMap { it.changes }, message)
        recordTask(task)
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
    // Delegates to ResearchGate (previously unwired — this is a strictly larger trigger set:
    // it also catches Android/Kotlin/Gradle "latest/current/docs/deprecated" questions and
    // DEBUG-intent error patterns that this method used to miss).
    private fun shouldResearch(request: String, intake: TaskIntake): Boolean =
        ResearchGate.shouldAutoResearch(request, intake)

    /**
     * Compact repo map for the model (Aider-style). Paths only — not full file bodies.
     * Extension-filtered index; honest about that limit.
     */
    // Delegates to AgentPrompt (single source of truth) — verified byte-identical to the
    // previous private body before switching, so this is a no-behavior-change fix for the
    // same "shared object exists but never gets called" drift risk found in ModelFailure.
    private fun repoMapSummary(maxPaths: Int = 100): String = AgentPrompt.repoMap(files, workspace, maxPaths)

    // Delegates to AgentPrompt (verified byte-identical before switching) for the same
    // duplication-drift reason as repoMapSummary/ModelFailure above.
    private fun buildPrompt(request: String, intake: TaskIntake, evidence: String): String =
        AgentPrompt.build(
            request, intake, evidence, config.maxOutputCharacters,
            LessonSynthesizer.synthesize(experience.all())
        )

    private fun executeTool(name: String, rawArguments: String): String {
        return tools.execute(name, rawArguments)
    }

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
            append("The model produced garbled or incoherent output instead of a coherent report. ")
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

    private fun formatListingSummary(listing: String, report: VerificationReport, namesOnly: Boolean = true): String =
        AgentPrompt.listingSummary(listing, report, namesOnly)

    private fun isWholeProjectReview(request: String): Boolean = AgentRequestKind.isWholeProjectReview(request)

    private fun synthesizeFromEvidence(request: String, evidence: String, report: VerificationReport): String =
        AgentPrompt.synthesizeFromEvidence(request, evidence, report, config.maxOutputCharacters)

    private fun isListingRequest(request: String): Boolean = AgentRequestKind.isListing(request)

    private fun isSourceFileListRequest(request: String): Boolean = AgentRequestKind.isSourceFileList(request)

    private fun currentRequestFocus(request: String): String {
        val marker = "Current request:"
        val idx = request.lastIndexOf(marker, ignoreCase = true)
        return if (idx >= 0) request.substring(idx + marker.length).trim().ifBlank { request } else request
    }

    private fun extractExplicitReadPath(request: String): String? = AgentRequestKind.explicitReadPath(request)

    private fun extractInspectTarget(request: String): String? = AgentRequestKind.inspectTarget(request)

    private fun isMarkerOnlyRequest(request: String): Boolean = AgentRequestKind.isMarkerOnly(request)

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

    // Provider-failure classification lives in ModelFailure (single source of truth) —
    // see the call sites above. Do not re-add private copies here; that duplication is
    // exactly what let the two versions drift last time (dead branch, missed phrases).

    private fun failedTask(
        id: String,
        request: String,
        plan: AgentPlan,
        message: String,
        changes: List<ChangeRecord>
    ): AgentTask = AgentTaskBuilders.failed(id, request, plan, message, changes)

    private fun approvalTask(
        id: String,
        request: String,
        plan: AgentPlan,
        proposal: PendingChangeProposal
    ): AgentTask = AgentTaskBuilders.approval(id, request, plan, proposal)
}
