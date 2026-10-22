package com.codingagent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codingagent.agent.AgentRuntimeResult
import com.codingagent.agent.AgentTools
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatRole
import com.codingagent.agent.ChatWorkspace
import com.codingagent.agent.ModelFailure
import com.codingagent.agent.RiskyChange
import com.codingagent.core.LocalStore
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.knowledge.KnowledgeBase
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelConnectionProbe
import com.codingagent.model.ModelDownloadProgress
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelSettings
import com.codingagent.model.ProbeResult
import com.codingagent.research.CompositeWebResearchProvider
import com.codingagent.research.DurableDeepResearchProvider
import com.codingagent.research.ResearchDisplayState
import com.codingagent.workspace.EditorDocument
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.PendingChangeProposal
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.ResearchHit
import com.codingagent.workspace.TerminalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE JOB: Own workbench state and agent lifecycle across configuration changes.
 */
class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context get() = getApplication<Application>()
    private val privateDir: File get() = appContext.filesDir

    val store = LocalStore(appContext)
    private val knowledgeBase = KnowledgeBase(appContext)

    private val _ui = MutableStateFlow(WorkbenchUiState())
    val ui: StateFlow<WorkbenchUiState> = _ui.asStateFlow()

    private var agent: AutonomousAgent? = null
    private var tools: AgentTools? = null
    private var mutationCoordinator: MutationCoordinator? = null
    private var chat: ChatWorkspace? = null
    private var modelGateway: ModelGateway? = null
    private var activeJob: Job? = null
    private var approvalJob: Job? = null
    private var researchJob: Job? = null
    private val progressEpoch = AtomicInteger(0)

    init {
        val loadedSettings = runCatching { store.loadModelSettings() }.getOrDefault(ModelSettings())
        applyModelSettings(loadedSettings)
        if (!loadedSettings.isRemoteConfigured()) {
            _ui.update {
                it.copy(
                    showModelSettings = true,
                    detail = "To write new code, tap Setup and paste the secret password from openrouter.ai/keys. A free helper is already selected."
                )
            }
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { knowledgeBase.ensureBundledExample(appContext) }
            }
            val restored = withContext(Dispatchers.IO) {
                val path = store.loadProjectPath() ?: return@withContext null
                val dir = File(path)
                if (!dir.isDirectory) {
                    store.saveProjectPath(null)
                    return@withContext null
                }
                runCatching {
                    val mounted = ProjectWorkspace(dir)
                    val files = mounted.summary().files.map { it.path }.sorted()
                    store.loadLastResearchQuery() to (mounted to files)
                }.getOrNull()
            }
            restored?.let { (lastQuery, pair) ->
                val (mounted, files) = pair
                mountWorkspace(mounted, files, "Opened your saved project. ${files.size} files.")
                if (!lastQuery.isNullOrBlank()) {
                    _ui.update { it.copy(researchQuery = lastQuery) }
                }
            }
        }
    }

    fun applyModelSettings(settings: ModelSettings) {
        val normalized = settings.normalized().withOpenRouterDefaults().copy(backend = ModelBackend.REMOTE)
        store.saveModelSettings(normalized)
        val gateway = normalized.remoteGateway()
        modelGateway = gateway
        agent?.setGateway(gateway)
        agent?.setSystemPrompt(normalized.effectiveSystemPrompt())
        _ui.update {
            it.copy(
                modelSettings = normalized,
                draftApiKey = normalized.apiKey,
                draftModelName = normalized.modelName,
                draftRotationModels = normalized.rotationModels,
                draftBaseUrl = normalized.baseUrl,
                draftExtraHeaders = normalized.extraHeaders,
                draftSystemPrompt = normalized.systemPrompt,
                draftMemoryRetention = normalized.memoryRetention,
                modelStatus = normalized.statusSummary(),
                modelLoadError = if (gateway == null)
                    normalized.validationErrors().joinToString("; ").ifBlank { "The secret password is missing." }
                else null
            )
        }
    }

    fun updateDraftApiKey(value: String) = _ui.update { it.copy(draftApiKey = value) }
    fun updateDraftModelName(value: String) = _ui.update { it.copy(draftModelName = value) }

    fun chooseFreeHelper(modelId: String) = _ui.update {
        it.copy(
            draftModelName = modelId,
            draftBaseUrl = if (it.draftBaseUrl.isBlank()) ModelSettings.OPENROUTER_BASE_URL else it.draftBaseUrl,
            draftRotationModels = ModelSettings.rotationForChoice(modelId)
        )
    }
    fun updateDraftBaseUrl(value: String) = _ui.update { it.copy(draftBaseUrl = value) }
    fun updateDraftExtraHeaders(value: String) = _ui.update { it.copy(draftExtraHeaders = value) }
    fun updateDraftSystemPrompt(value: String) = _ui.update { it.copy(draftSystemPrompt = value) }
    fun updateDraftMemoryRetention(value: String) = _ui.update { it.copy(draftMemoryRetention = value) }
    fun setShowModelSettings(show: Boolean) = _ui.update { it.copy(showModelSettings = show) }

    fun probeModelConnection() {
        val s = _ui.value
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = ModelSettings(
                backend = ModelBackend.REMOTE,
                baseUrl = s.draftBaseUrl,
                apiKey = s.draftApiKey,
                modelName = s.draftModelName,
                systemPrompt = s.draftSystemPrompt,
                extraHeaders = s.draftExtraHeaders,
                onboarded = true,
                memoryRetention = s.draftMemoryRetention
            )
            val result = ModelConnectionProbe.probe(candidate)
            withContext(Dispatchers.Main) {
                _ui.update {
                    it.copy(
                        probeMessage = when (result) {
                            is ProbeResult.Ok -> result.detail
                            is ProbeResult.Failed -> "Could not connect: ${ModelFailure.humanize(result.reason)}"
                        }
                    )
                }
            }
        }
    }

    fun saveDraftModelSettings() {
        val s = _ui.value
        applyModelSettings(
            ModelSettings(
                backend = ModelBackend.REMOTE,
                baseUrl = s.draftBaseUrl,
                apiKey = s.draftApiKey,
                modelName = s.draftModelName,
                rotationModels = s.draftRotationModels,
                systemPrompt = s.draftSystemPrompt,
                extraHeaders = s.draftExtraHeaders,
                onboarded = true,
                memoryRetention = s.draftMemoryRetention
            )
        )
        _ui.update {
            it.copy(
                showModelSettings = false,
                detail = if (modelGateway != null) "The helper is connected." else "Saved, but the helper is not fully set up yet."
            )
        }
    }

    private fun mountWorkspace(ws: ProjectWorkspace, files: List<String>, detail: String) {
        mutationCoordinator = MutationCoordinator(ws)
        tools = AgentTools(ws)
        // Workspace must be in state before rebuildAgentIfNeeded() reads it.
        _ui.update {
            it.copy(
                workspace = ws,
                fileList = files,
                status = AgentStatus.READY,
                detail = detail,
                terminalHistory = emptyList(),
                terminalLiveOutput = ""
            )
        }
        rebuildAgentIfNeeded()
        rebuildChat()
        _ui.update {
            it.copy(terminalHistory = tools?.terminalHistory() ?: emptyList())
        }
        store.saveProjectPath(ws.projectRoot().absolutePath)
    }

    private fun rebuildAgentIfNeeded() {
        val ws = _ui.value.workspace ?: return
        agent = AutonomousAgent(
            root = ws.projectRoot(),
            knowledge = knowledgeBase,
            gateway = modelGateway,
            research = DurableDeepResearchProvider(ws.projectRoot().resolve(".coding-agent/research")),
            mutations = mutationCoordinator ?: MutationCoordinator(ws)
        ).also {
            it.setSystemPrompt(_ui.value.modelSettings.effectiveSystemPrompt())
        }
    }

    private fun rebuildChat() {
        chat = ChatWorkspace(
            store = store,
            memoryStore = store.userMemoryStore(),
            runtimeProvider = { agent },
            unavailableMessageProvider = {
                when {
                    _ui.value.workspace == null ->
                        "No project is open. Tap New to start one, or Import to open a folder you already have."
                    agent == null ->
                        "The project is still opening. Try again in a moment."
                    _ui.value.modelLoadError != null ->
                        "The helper is not set up yet: ${_ui.value.modelLoadError.orEmpty()} You can still say hello, list files, or ask me to read a file."
                    else ->
                        "I am not ready yet. Try again in a moment."
                }
            },
            progressListener = { phase, detailText ->
                val epoch = progressEpoch.get()
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (epoch != progressEpoch.get()) return@launch
                    _ui.update {
                        it.copy(
                            status = mapAgentPhase(phase),
                            detail = detailText
                        )
                    }
                }
            }
        )
        _ui.update {
            it.copy(chatMessages = store.recentChatMessages(Int.MAX_VALUE).asReversed())
        }
    }

    fun handleFolderPickerResult(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                importProject(appContext, privateDir, uri)
            }.onSuccess { imported ->
                withContext(Dispatchers.Main) {
                    val mounted = ProjectWorkspace(imported)
                    val files = mounted.summary().files.map { it.path }.sorted()
                    mountWorkspace(mounted, files, "Project is ready. ${files.size} files.")
                    clearPendingApproval()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.STOPPED,
                            detail = "Could not open that folder: ${err.message.orEmpty()}"
                        )
                    }
                }
            }
        }
    }

    fun startNewProject(nameHint: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                createEmptyProject(privateDir, nameHint)
            }.onSuccess { created ->
                withContext(Dispatchers.Main) {
                    val mounted = ProjectWorkspace(created)
                    val files = mounted.summary().files.map { it.path }.sorted()
                    mountWorkspace(mounted, files, "Your new project is ready.")
                    clearPendingApproval()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.STOPPED,
                            detail = "Could not start a new project: ${err.message.orEmpty()}"
                        )
                    }
                }
            }
        }
    }

    fun clearProject() {
        stopAgent()
        agent = null
        tools = null
        mutationCoordinator = null
        chat = null
        store.saveProjectPath(null)
        _ui.update {
            it.copy(
                workspace = null,
                fileList = emptyList(),
                editorPath = "",
                editorContent = "",
                editorDocument = null,
                terminalHistory = emptyList(),
                terminalLiveOutput = "",
                status = AgentStatus.READY,
                detail = "Project closed."
            )
        }
        clearPendingApproval()
    }

    fun sendChat(request: String) {
        val trimmed = request.trim()
        if (trimmed.isEmpty()) return
        if (activeJob != null) {
            _ui.update {
                it.copy(messageQueue = it.messageQueue + trimmed, chatInput = "")
            }
            return
        }
        _ui.update { it.copy(chatInput = "", status = AgentStatus.WORKING, detail = "Working on what you asked.", agentBusy = true) }
        progressEpoch.incrementAndGet()
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_ui.value.workspace == null && asksToStartProject(trimmed)) {
                    val created = createEmptyProject(privateDir, null)
                    val mounted = ProjectWorkspace(created)
                    val files = mounted.summary().files.map { it.path }.sorted()
                    withContext(Dispatchers.Main) {
                        mountWorkspace(mounted, files, "Your new project is ready.")
                    }
                }
                val workspace = chat ?: run {
                    rebuildChat()
                    chat ?: error("Chat workspace could not be created")
                }
                val sendResult = workspace.send(trimmed)
                withContext(Dispatchers.Main) {
                    val result = sendResult.result
                    val proposal = when (result) {
                        is AgentRuntimeResult.NeedsApproval ->
                            agent?.pendingProposals()?.firstOrNull { it.id == result.proposalId }
                        else -> null
                    }
                    _ui.update {
                        it.copy(
                            chatMessages = store.recentChatMessages(Int.MAX_VALUE).asReversed(),
                            pendingApproval = proposal != null,
                            pendingProposal = proposal,
                            pendingProposalId = proposal?.id,
                            approvalCount = proposal?.approvalCount ?: 0,
                            pendingReason = proposal?.request
                                ?: "The helper wants to change your project. Read the note, then say yes twice if you agree.",
                            pendingPhoneLock = proposal?.let { pending ->
                                RiskyChange.kinds(pending.changeSet.changes, pending.request).isNotEmpty()
                            } ?: false,
                            status = when (result) {
                                is AgentRuntimeResult.NeedsApproval -> AgentStatus.APPROVAL
                                is AgentRuntimeResult.Failed -> AgentStatus.FAILED
                                else -> AgentStatus.READY
                            },
                            detail = when (result) {
                                is AgentRuntimeResult.NeedsApproval ->
                                    "Please say yes twice before anything is saved."
                                is AgentRuntimeResult.Failed ->
                                    result.task?.summary ?: "That request did not finish."
                                else -> "Ready for your next request."
                            }
                        )
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.FAILED,
                            detail = t.message ?: t.javaClass.simpleName
                        )
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    activeJob = null
                    _ui.update { it.copy(agentBusy = false) }
                    val queue = _ui.value.messageQueue
                    if (queue.isNotEmpty()) {
                        val next = queue.first()
                        _ui.update { it.copy(messageQueue = queue.drop(1)) }
                        sendChat(next)
                    }
                }
            }
        }
    }

    fun stopAgent() {
        progressEpoch.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        approvalJob?.cancel()
        approvalJob = null
        researchJob?.cancel()
        researchJob = null
        agent?.cancel("Stopped by owner")
        chat?.cancel()
        tools?.cancelTerminal("Stopped by owner")
        _ui.update {
            it.copy(
                status = AgentStatus.STOPPED,
                detail = "Stopped",
                terminalLiveOutput = "",
                agentBusy = false
            )
        }
    }

    fun isBusy(): Boolean = _ui.value.agentBusy || activeJob != null || approvalJob?.isActive == true

    fun attachApprovalJob(job: Job?) {
        approvalJob = job
    }

    private fun asksToStartProject(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("new project") ||
            t.contains("start a project") ||
            t.contains("start the project") ||
            t.contains("create a project") ||
            t.contains("create the project") ||
            t.contains("make a project")
    }

    fun onChangeApplied(result: MutationApprovalResult.Applied) {
        val paths = result.changeSet.changes.map { it.path }.distinct()
        val verification = result.proposal.verification
        val verificationSummary = buildString {
            append(
                if (verification.passed) "A simple text check passed. This does not prove the program runs."
                else "A simple text check found problems. This does not by itself prove the program is broken."
            )
            if (verification.issues.isNotEmpty()) {
                append("\nIssues:\n")
                append(verification.issues.joinToString("\n") { "- ${it.path}:${it.line}: ${it.message}" })
            }
            if (verification.warnings.isNotEmpty()) {
                append("\nWarnings:\n")
                append(verification.warnings.joinToString("\n") { "- $it" })
            }
        }
        _ui.value.workspace?.let { ws ->
            _ui.update {
                it.copy(fileList = ws.summary().files.map { f -> f.path }.sorted())
            }
        }
        store.recordChatMessage(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = "Saved after you said yes twice.\nFiles:\n" +
                    paths.joinToString("\n") { "- $it" } +
                    "\nYou asked: ${result.proposal.request}\n$verificationSummary"
            )
        )
        _ui.update {
            it.copy(
                chatMessages = store.recentChatMessages(Int.MAX_VALUE).asReversed(),
                approvalCount = result.proposal.approvalCount,
                pendingApproval = false,
                pendingProposal = null,
                pendingProposalId = null,
                pendingPhoneLock = false,
                status = AgentStatus.READY,
                detail = "Saved ${paths.size} file(s): ${paths.joinToString()}. $verificationSummary"
            )
        }
    }

    fun clearPendingApproval() {
        _ui.update {
            it.copy(
                pendingApproval = false,
                pendingProposal = null,
                pendingProposalId = null,
                pendingPhoneLock = false,
                approvalCount = 0
            )
        }
    }

    fun mutationCoordinator(): MutationCoordinator? = mutationCoordinator
    fun agent(): AutonomousAgent? = agent
    fun tools(): AgentTools? = tools
    fun chatWorkspace(): ChatWorkspace? = chat

    fun runTerminal(command: String) {
        val t = tools ?: return
        if (t.isTerminalBusy()) return
        _ui.update { it.copy(terminalLiveOutput = "") }
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                t.runTerminal(
                    command = command,
                    onStdout = { line ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _ui.update { it.copy(terminalLiveOutput = it.terminalLiveOutput + line + "\n") }
                        }
                    },
                    onStderr = { line ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _ui.update { it.copy(terminalLiveOutput = it.terminalLiveOutput + line + "\n") }
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            terminalHistory = t.terminalHistory(),
                            terminalCommand = "",
                            status = AgentStatus.READY,
                            detail = "The command finished."
                        )
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.FAILED,
                            detail = e.message ?: "The command failed.",
                            terminalHistory = t.terminalHistory()
                        )
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { activeJob = null }
            }
        }
    }

    fun setTab(tab: SurfaceTab) = _ui.update { it.copy(tab = tab) }
    fun setChatInput(value: String) = _ui.update { it.copy(chatInput = value) }
    fun setProjectQuery(value: String) = _ui.update { it.copy(projectQuery = value) }
    fun setEditorPath(value: String) = _ui.update { it.copy(editorPath = value) }
    fun setEditorContent(value: String) = _ui.update {
        val original = it.editorDocument?.content
        it.copy(
            editorContent = value,
            editorDocument = it.editorDocument?.copy(dirty = original != null && value != original)
        )
    }

    fun openEditorFile(path: String) {
        val doc = tools?.let { runCatching { it.read(path) }.getOrNull() }
        if (doc == null) {
            setStatus(AgentStatus.FAILED, "Could not open $path.")
            return
        }
        _ui.update {
            it.copy(editorPath = doc.path, editorContent = doc.content, editorDocument = doc)
        }
    }
    fun setTerminalCommand(value: String) = _ui.update { it.copy(terminalCommand = value) }
    fun setResearchQuery(value: String) = _ui.update { it.copy(researchQuery = value) }
    fun setStatus(status: AgentStatus, detail: String) = _ui.update { it.copy(status = status, detail = detail) }

    fun clearTerminal() {
        tools?.clearTerminalHistory()
        _ui.update { it.copy(terminalHistory = emptyList(), terminalLiveOutput = "") }
    }

    fun searchResearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        researchJob?.cancel()
        _ui.update {
            it.copy(
                researchQuery = trimmed,
                researchError = null,
                researchHits = emptyList(),
                researchState = ResearchDisplayState(phase = "searching", canSend = false)
            )
        }
        researchJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { CompositeWebResearchProvider().search(trimmed, 8) }
            withContext(Dispatchers.Main) {
                result.onSuccess { found ->
                    _ui.update {
                        it.copy(
                            researchHits = found.hits,
                            researchError = found.error,
                            researchState = ResearchDisplayState(
                                phase = if (found.hits.isEmpty()) "empty" else "results",
                                fullSources = found.hits.size,
                                canSend = true
                            )
                        )
                    }
                }.onFailure { err ->
                    _ui.update {
                        it.copy(
                            researchError = err.message ?: err.javaClass.simpleName,
                            researchState = ResearchDisplayState(phase = "failed", canSend = true)
                        )
                    }
                }
            }
        }
    }

    fun saveEditor() {
        val path = _ui.value.editorPath.trim()
        val edited = _ui.value.editorContent
        val coordinator = mutationCoordinator
        val fileTools = tools
        if (coordinator == null || fileTools == null) {
            setStatus(AgentStatus.FAILED, "Open or start a project before saving a file.")
            return
        }
        if (path.isBlank()) {
            setStatus(AgentStatus.FAILED, "Tap a file before saving.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val current = runCatching { fileTools.read(path).content }.getOrNull()
            val operation = when {
                current == null -> TaskOperation(kind = OperationKind.CREATE_FILE, path = path, text = edited)
                current == edited -> null
                current.isEmpty() -> TaskOperation(kind = OperationKind.APPEND, path = path, text = edited)
                else -> TaskOperation(
                    kind = OperationKind.REPLACE,
                    path = path,
                    oldText = current,
                    newText = edited
                )
            }
            if (operation == null) {
                withContext(Dispatchers.Main) {
                    setStatus(AgentStatus.READY, "Nothing new to save in $path.")
                }
                return@launch
            }
            val request = "Save editor changes to $path"
            val result = coordinator.propose(request, listOf(operation), request)
            withContext(Dispatchers.Main) {
                when (result) {
                    is MutationProposeResult.Proposed -> {
                        val proposal = result.proposal
                        _ui.update {
                            it.copy(
                                pendingApproval = true,
                                pendingProposal = proposal,
                                pendingProposalId = proposal.id,
                                approvalCount = proposal.approvalCount,
                                pendingReason = proposal.request,
                                pendingPhoneLock = RiskyChange.kinds(proposal.changeSet.changes, proposal.request).isNotEmpty(),
                                status = AgentStatus.APPROVAL,
                                detail = "Your edits to $path are waiting. Open Review and say yes twice before they are saved."
                            )
                        }
                    }
                    is MutationProposeResult.Rejected ->
                        setStatus(AgentStatus.FAILED, result.reason)
                }
            }
        }
    }
}

data class WorkbenchUiState(
    val tab: SurfaceTab = SurfaceTab.CHAT,
    val status: AgentStatus = AgentStatus.READY,
    val detail: String = "Ready",
    val workspace: ProjectWorkspace? = null,
    val fileList: List<String> = emptyList(),
    val projectQuery: String = "",
    val editorPath: String = "",
    val editorContent: String = "",
    val editorDocument: EditorDocument? = null,
    val chatMessages: List<ChatMessage> = emptyList(),
    val chatInput: String = "",
    val messageQueue: List<String> = emptyList(),
    val researchQuery: String = "",
    val researchHits: List<ResearchHit> = emptyList(),
    val researchError: String? = null,
    val researchState: ResearchDisplayState = ResearchDisplayState(),
    val terminalCommand: String = "",
    val terminalHistory: List<TerminalEntry> = emptyList(),
    val terminalLiveOutput: String = "",
    val pendingApproval: Boolean = false,
    val approvalCount: Int = 0,
    val pendingReason: String = "The helper wants to change your project. Read the note, then say yes twice if you agree.",
    val pendingPhoneLock: Boolean = false,
    val pendingProposalId: String? = null,
    val pendingProposal: PendingChangeProposal? = null,
    val modelSettings: ModelSettings = ModelSettings(),
    val modelStatus: String = "Helper not connected yet",
    val modelProgress: ModelDownloadProgress? = null,
    val modelLoadError: String? = null,
    val showModelSettings: Boolean = false,
    val draftApiKey: String = "",
    val draftModelName: String = ModelSettings.OPENROUTER_FREE_MODEL,
    val draftRotationModels: String = ModelSettings.OPENROUTER_FREE_FALLBACKS,
    val draftBaseUrl: String = ModelSettings.OPENROUTER_BASE_URL,
    val draftExtraHeaders: String = "",
    val draftSystemPrompt: String = "",
    val draftMemoryRetention: String = ModelSettings.MEMORY_KEEP_CAPPED,
    val probeMessage: String? = null,
    val agentBusy: Boolean = false
)
