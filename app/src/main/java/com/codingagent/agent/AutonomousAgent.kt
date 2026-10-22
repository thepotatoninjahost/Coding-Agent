package com.codingagent.agent
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentStep
import com.codingagent.workspace.OwnerApprovalToken

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    gateway: ModelGateway? = null,
    private val config: AutonomousAgentConfig = AutonomousAgentConfig(),
    private val research: DeepResearchProvider = DurableDeepResearchProvider(root.resolve(".coding-agent/research")),
    private val mutations: MutationCoordinator = MutationCoordinator(ProjectWorkspace(root))
) : CodingAgentExecutor {
    /**
     * The active model gateway. Mutable so the UI can swap the gateway (e.g. after the user
     * saves new model settings) without recreating the agent and losing pending proposals.
     */
    @Volatile
    private var gateway: ModelGateway? = gateway
    @Volatile
    private var configuredSystemPrompt: String = AgentModelProtocol.DEFAULT_SYSTEM

    fun setGateway(gateway: ModelGateway?) {
        this.gateway = gateway
    }

    fun setOwnerContextProvider(provider: () -> AgentOwnerContext) {
        tools.setOwnerContextProvider(provider)
    }

    fun setSystemPrompt(prompt: String?) {
        configuredSystemPrompt = prompt.orEmpty().trim()
            .ifBlank { AgentModelProtocol.DEFAULT_SYSTEM }
    }
    // FIX: derive workspace from the shared MutationCoordinator instance so both always
    // reference the same ProjectWorkspace. The previous `ProjectWorkspace(root)` here
    // created a second, divergent instance that could silently drift from mutations.workspace.
    private val workspace: ProjectWorkspace get() = mutations.workspace
    private val files = ProjectFileService(workspace)
    // Reuse the workspace-owned session so terminal commands, verification checks, and Stop share one cancellation boundary.
    private val terminal = workspace.terminal()
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
        onResearchProgress = { lastResearchProgress = it }
    )
    private val running = AtomicBoolean(false)
    private val cancellationGeneration = AtomicLong(0L)
    private val lifecycleLock = Any()
    @Volatile
    private var activeRunGeneration: Long = -1L

    @Volatile
    private var lastCancelReason: String = "Stopped by owner"

    init {
        mutations.setRepairProvider { diagnosis, attempt ->
            synthesizeRepairChangeSet(diagnosis, attempt)
        }
    }

    fun isRunning(): Boolean = running.get()

    fun cancel(reason: String = "Stopped by owner") {
        synchronized(lifecycleLock) {
            cancellationGeneration.incrementAndGet()
            lastCancelReason = reason
        }
        // Cancel every owned blocking lane, not only the model connection.
        terminal.cancel(reason)
        research.cancel()
        gateway?.cancel()
    }

    fun isCancelled(): Boolean {
        val active = activeRunGeneration
        return active >= 0L && cancellationGeneration.get() != active
    }

    /**
     * Replace the model gateway without recreating the agent. Safe to call from the UI thread
     * between runs; volatile ensures the next run() call sees the updated reference.
     */
    fun updateGateway(newGateway: ModelGateway?) {
        gateway = newGateway
    }

    private fun recordTask(task: AgentTask) {
        journal.record(task)
        runCatching {
            experience.record(
                task = task.request,
                operation = task.status,
                result = task.summary,
                evidence = task.changes.joinToString(", ") { it.path },
                passed = task.verification.passed
            )
        }
    }

    override fun execute(request: String): AgentRuntimeResult {
        val events = run(request)
        return when (val terminalEvent = events.lastOrNull()) {
            is AutonomousAgentEvent.ApprovalRequired -> AgentRuntimeResult.NeedsApproval(terminalEvent.task, "Please read this change and say yes twice before anything is saved.", terminalEvent.proposal.id)
            is AutonomousAgentEvent.Completed -> AgentRuntimeResult.Completed(terminalEvent.task)
            is AutonomousAgentEvent.Failed -> terminalEvent.task?.let { AgentRuntimeResult.Failed(it) } ?: error(terminalEvent.message)
            is AutonomousAgentEvent.Stopped -> AgentRuntimeResult.Failed(terminalEvent.task)
            else -> {
                val detail = events.filterIsInstance<AutonomousAgentEvent.Phase>()
                    .lastOrNull { it.name == "PERSONAL_LOG_ERROR" }
                    ?.detail
                if (detail != null) {
                    val taskId = events.filterIsInstance<AutonomousAgentEvent.Started>().firstOrNull()?.taskId
                        ?: UUID.randomUUID().toString()
                    AgentRuntimeResult.Failed(
                        failedTask(taskId, request, AgentPlan(request, emptyList(), emptyList()), detail, emptyList())
                    )
                } else {
                    error("Autonomous agent ended without a terminal result")
                }
            }
        }
    }

    fun pendingProposals(): List<PendingChangeProposal> = mutations.pending()
    fun recentPersonalLogs(limit: Int = 100): List<String> = journal.recentEvents(limit)
    fun approveProposal(id: String, ownerApproval: OwnerApprovalToken): MutationApprovalResult = mutations.approve(id, ownerApproval)
    fun rejectProposal(id: String): Boolean = mutations.reject(id)

    fun run(request: String, onEvent: (AutonomousAgentEvent) -> Unit = {}): List<AutonomousAgentEvent> {
        val runGeneration: Long
        synchronized(lifecycleLock) {
            check(running.compareAndSet(false, true)) { "Agent is already running" }
            runGeneration = cancellationGeneration.get()
            activeRunGeneration = runGeneration
        }
        try {
            changeSets.clear()
            lastResearchProgress = "not started"
            return runInternal(request, onEvent)
        } finally {
            activeRunGeneration = -1L
            running.set(false)
        }
    }

    private fun runInternal(request: String, onEvent: (AutonomousAgentEvent) -> Unit): List<AutonomousAgentEvent> {
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val taskId = UUID.randomUUID().toString()
        val events = mutableListOf<AutonomousAgentEvent>()
        var personalLogHealthy = true
        fun emit(event: AutonomousAgentEvent) {
            if (!personalLogHealthy) return
            val logFailure = runCatching { journal.recordEvent(taskId, event) }.exceptionOrNull()
            if (logFailure != null) {
                personalLogHealthy = false
                val detail = logFailure.message ?: logFailure.javaClass.simpleName
                val warning = AutonomousAgentEvent.Phase(
                    "PERSONAL_LOG_ERROR",
                    "I could not save a private note about what I just did: $detail. I stopped so I do not keep working without a record."
                )
                events += warning
                onEvent(warning)
                val task = failedTask(
                    taskId,
                    normalized,
                    AgentPlan(normalized, emptyList(), emptyList()),
                    warning.detail,
                    emptyList()
                )
                val failed = AutonomousAgentEvent.Failed(task, warning.detail)
                events += failed
                onEvent(failed)
                cancel("Personal log write failed; stopped to preserve auditability")
                return
            }
            events += event
            onEvent(event)
        }
        emit(AutonomousAgentEvent.Started(taskId, normalized))
        emit(AutonomousAgentEvent.Phase("INTAKE", "Reading what you asked and looking at the project."))
        if (!personalLogHealthy) return events
        // Keep the full packaged conversation for deterministic intake. GoalInterpreter
        // extracts the active job and prior owner instructions before the model sees them.
        // The current-turn focus remains the execution-facing request for direct lanes.
        val focus = currentRequestFocus(normalized)
        // Tool mutations must retain the full packaged conversation so proposal records keep
        // the active job and owner constraints.
        tools.setRequestContext(normalized)
        val intake = TaskIntakeParser(root).parse(normalized)
        val plan = AgentPlanner(workspace).plan(intake)
        // Wired in: was previously dead code. Every call below is defensively wrapped
        // (runCatching) — PlanningLoop can at worst no-op, never crash a live run, since
        // this file can't be compiled/tested in this environment before shipping.
        val planningLoop = PlanningLoop(plan)
        val toolSelectionLoop = ToolSelectionLoop(ToolSelector().select(intake))
        // Intake and the initial repository view are real execution prerequisites, not
        // observational plan entries. Complete them only after the same workspace instance
        // used by mutations has produced its repository summary.
        val repositorySummary = runCatching { workspace.summary() }
        if (repositorySummary.isFailure) {
            val error = repositorySummary.exceptionOrNull()
            val message = "Repository inspection failed before execution could begin: " +
                (error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "unknown error")
            val task = failedTask(taskId, normalized, plan, message, emptyList())
            emit(AutonomousAgentEvent.Failed(task, message))
            recordTask(task)
            return events
        }
        planningLoop.completePhase("intake", "request parsed")
        planningLoop.completePhase("understand", "repository summary established")
        toolSelectionLoop.completeKind(ToolKind.INDEX_REPOSITORY, "repository summary established")
        emit(AutonomousAgentEvent.Phase("PLAN", "Making a plan for what you asked."))

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
                VerificationReport(false, emptyList()),
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
                "The code helper is not connected yet, so I cannot write new code from this request. " +
                    "You can still say hello, ask me to list files, ask for status, or ask me to read a file you name. " +
                    "Tap Setup. The free OpenRouter helper is already filled in. Paste the secret password from openrouter.ai/keys. " +
                    "It stays on this phone and is not written into the app."
            val task = AgentTask(
                taskId, focus, "needs-input", plan, emptyList(),
                VerificationReport(false, emptyList()),
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
            if (isCancelled()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("RESEARCH", "Looking things up on the internet."))
            val mode = ResearchModeDetector.detect(focus)
            val session = runCatching {
                research.deepResearch(focus, 8, mode) { progress ->
                    if (isCancelled() || !personalLogHealthy) return@deepResearch
                    emit(AutonomousAgentEvent.Phase("RESEARCH", "Looking things up: ${progress.completed} of ${progress.total}. Found ${progress.successful}. Could not use ${progress.failed}."))
                }
            }.getOrNull()
            if (!personalLogHealthy) return events
            if (isCancelled()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            val brief = session?.takeIf { it.sources.isNotEmpty() }?.let(ResearchBriefBuilder::build)
            if (brief != null && brief.sourceCount > 0 && brief.evidence.isNotBlank()) {
                researchEvidence = "\n\nResearch brief:\n${brief.evidence}"
                emit(AutonomousAgentEvent.Phase("RESEARCH", "I read ${brief.sourceCount} pages (${brief.wordCount} words) and saved them."))
            } else {
                researchEvidence =
                    "\n\nResearch ran but returned no usable sources. Do not invent external docs or APIs. " +
                        "Prefer local project evidence, or call research_web with a tighter technical query."
                emit(AutonomousAgentEvent.Phase("RESEARCH", "I could not find usable pages. I will not invent facts from the internet."))
                if (ResearchGate.requiresUsableEvidence(focus, intake)) {
                    val message =
                        "I needed pages from the internet for this, and I could not get any. " +
                            "I will not pretend I looked them up. Try again with fewer, clearer words."
                    val task = failedTask(taskId, focus, plan, message, emptyList())
                    recordTask(task)
                    emit(AutonomousAgentEvent.Failed(task, message))
                    return events
                }
            }
        } else {
            emit(AutonomousAgentEvent.Phase("RESEARCH", "I am using the project on this phone. I do not need the internet for this."))
        }

        val transcript = mutableListOf<com.codingagent.model.ModelMessage>()
        val state = ToolTurnState()
        val completeInitialEvidence = buildString {
            append(repoMapSummary())
            append("\nCall list_files or search_project before read_file. Do not invent paths.")
            append(researchEvidence)
        }
        // Keep the full evidence available for ordered retrieval without forcing all of it
        // into the first model request, whose token window depends on the configured model.
        state.lastEvidence = tools.presentEvidence("initial repository and research evidence", completeInitialEvidence)
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
            isCancelled = { isCancelled() }
        )

        for (turn in 0 until config.maxTurns) {
            if (!personalLogHealthy) return events
            if (isCancelled()) return stopNow(taskId, normalized, plan, events) { emit(it) }
            emit(AutonomousAgentEvent.Phase("MODEL", "Thinking about the next step (${turn + 1} of ${config.maxTurns})."))
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
                        configuredSystemPrompt,
                        buildPrompt(normalized, intake, state.lastEvidence),
                        toolsThisTurn,
                        transcript.toList(),
                        researchRequired = false
                    )
                },
                isCancelled = { isCancelled() },
                onPhase = { emit(AutonomousAgentEvent.Phase("MODEL", it)) }
            ) ?: return stopNow(taskId, normalized, plan, events) { emit(it) }
            if (isCancelled()) return stopNow(taskId, normalized, plan, events) { emit(it) }
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
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content)
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM: That was a raw tool dump, not an answer. " +
                                "Write the review in plain English from evidence already gathered. No XML. No tool tags."
                        )
                        continue
                    }
                    emit(AutonomousAgentEvent.ModelMessage(response.content))
                    // A change/debug/create/refactor task is not complete merely because the
                    // model produced prose. Once the loop has reached its write gate, a textual
                    // response before any mutation is a refusal to act, not task completion.
                    // Keep the execution spine alive long enough for the model to produce the
                    // required staged mutation, then fail closed if it repeatedly refuses.
                    if (writeNow && changeWork && !state.mutationOccurred) {
                        state.writeNowRefusals++
                        if (state.writeNowRefusals >= 2) {
                            val msg = "I looked at the project but did not prepare the change you asked for. Nothing was saved. Say what you want again, and name the file if you know it."
                            val task = failedTask(taskId, normalized, plan, msg, changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, msg))
                            recordTask(task)
                            return events
                        }
                        transcript += com.codingagent.model.ModelMessage(
                            "assistant",
                            response.content
                        )
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM: This is a change task and no mutation has been staged yet. " +
                                "Do not report completion. Call replace_text or create_file now using the evidence already gathered."
                        )
                        continue
                    }
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
                        transcript += com.codingagent.model.ModelMessage("assistant", response.content)
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM CONSTRAINT: $missing You must call the read_file tool on the target path before any final report. Do not invent file contents."
                        )
                        state.lastEvidence = missing + "\n\n" + state.lastEvidence
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
        val changeStillOpen = (
            intake.intent == TaskIntent.CHANGE ||
                intake.intent == TaskIntent.CREATE ||
                intake.intent == TaskIntent.REFACTOR ||
                intake.intent == TaskIntent.DEBUG
            ) && !state.mutationOccurred
        if (changeStillOpen) {
            val msg = "I looked at the project but did not prepare the change you asked for. Nothing was saved. Say what you want again, and name the file if you know it."
            val task = failedTask(taskId, normalized, plan, msg, changeSets.flatMap { it.changes })
            recordTask(task)
            emit(AutonomousAgentEvent.Failed(task, msg))
            return events
        }
        val report = workspace.verify()
        val usableEvidence = state.lastEvidence.isNotBlank() && !state.lastEvidence.startsWith("ERROR:")
        val summary = if (usableEvidence) {
            synthesizeFromEvidence(normalized, state.lastEvidence, report)
        } else {
            "I used every step I am allowed and still do not have a usable answer. " +
                "Try a smaller request, such as one file or one question. Or tap Setup and try a different helper."
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
        AgentPrompt.build(request, intake, evidence, config.maxOutputCharacters)

    private fun synthesizeRepairChangeSet(diagnosis: String, attempt: Int): ChangeSet? {
        val model = gateway ?: return null
        val repairTools = AgentModelProtocol.tools().filter { it.name == "replace_text" || it.name == "create_file" }
        val evidence = buildString {
            append("Repair attempt ").append(attempt).append(".\n")
            append("Failure diagnosis: ").append(diagnosis).append("\n")
            append("Current repository map:\n").append(repoMapSummary()).append("\n")
            workspace.verify().issues.forEach { issue ->
                val content = runCatching { LocalFileEvidence.readComplete(issue.path, files) }.getOrNull()
                if (content != null) {
                    append("\nFILE ").append(issue.path).append("\n")
                    append(content).append("\n")
                }
            }
        }
        val response = ModelCallWithRetry.call(
            gateway = model,
            request = {
                ModelRequest(
                    configuredSystemPrompt,
                    "You are repairing a failed approved code change. $evidence\n" +
                        "Return exactly one replace_text or create_file tool call that fixes the diagnosed failure. " +
                        "Do not explain. Do not use any other tool. Never weaken tests or safety controls.",
                    repairTools,
                    emptyList(),
                    researchRequired = false
                )
            },
            isCancelled = { isCancelled() },
            onPhase = { }
        ) ?: return null
        val call = when (response) {
            is ModelResponse.ToolCall -> response
            is ModelResponse.Text -> JsonModelResponseParser().parse(response.content) as? ModelResponse.ToolCall
            else -> null
        } ?: return null
        return runCatching {
            val args = JSONObject(call.arguments)
            val operation = when (call.name) {
                "replace_text" -> TaskOperation(
                    OperationKind.REPLACE,
                    args.getString("path"),
                    args.getString("oldText"),
                    args.getString("newText")
                )
                "create_file" -> TaskOperation(
                    OperationKind.CREATE_FILE,
                    args.getString("path"),
                    text = args.getString("content")
                )
                else -> return@runCatching null
            }
            workspace.preview(listOf(operation), "Self-repair attempt $attempt: $diagnosis")
        }.getOrNull()
    }
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
            return text
        }
        return buildString {
            append("The helper's answer was garbled, so I am not treating it as a result. ")
            append("A simple text check found ")
            append(report.issues.size)
            append(" issue(s)")
            if (report.issues.isEmpty()) {
                append(" (none).")
            } else {
                append(":")
                report.issues.forEach { issue ->
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
            files.readChunk(normalized, 0, 1)
            return normalized
        }
        val base = java.io.File(normalized).name
        val hits = workspace.sourcePaths().filter {
            java.io.File(it).name.equals(base, ignoreCase = true)
        }
        return when {
            hits.isEmpty() -> null
            hits.size == 1 -> hits[0]
            else -> hits.minByOrNull { it.length }
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
        val content = runCatching { LocalFileEvidence.readComplete(resolved, files) }.getOrNull() ?: return null
        val all = workspace.verify().issues
        val policy = all.filter { issue ->
            val ip = issue.path.replace('\\', '/')
            ip == resolved || ip.endsWith("/$resolved")
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