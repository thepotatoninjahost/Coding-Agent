package com.codingagent.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AgentRuntimeResult
import com.codingagent.agent.AgentTools
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatRole
import com.codingagent.agent.ChatWorkspace
import com.codingagent.core.LocalStore
import com.codingagent.knowledge.KnowledgeBase
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelConnectionProbe
import com.codingagent.model.ModelDownloadProgress
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelSettings
import com.codingagent.model.ProbeResult
import com.codingagent.research.DurableDeepResearchProvider
import com.codingagent.research.ResearchDisplayState
import com.codingagent.workspace.EditorDocument
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
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
    private val progressEpoch = AtomicInteger(0)

    init {
        // Load model settings on the construction path so the first Compose frame
        // already knows whether to open Model settings. Deferring this to a
        // background job raced instrumentation smoke tests and delayed first-run UX.
        val loadedSettings = runCatching { store.loadModelSettings() }.getOrDefault(ModelSettings())
        applyModelSettings(loadedSettings)
        if (!loadedSettings.isRemoteConfigured()) {
            _ui.update {
                it.copy(
                    showModelSettings = true,
                    detail = "Enter base URL, model name, and API key in Model settings."
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
                mountWorkspace(mounted, files, "Restored project · ${files.size} files")
                if (!lastQuery.isNullOrBlank()) {
                    _ui.update { it.copy(researchQuery = lastQuery) }
                }
            }
        }
    }

    fun applyModelSettings(settings: ModelSettings) {
        val normalized = settings.normalized().copy(backend = ModelBackend.REMOTE)
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
                draftBaseUrl = normalized.baseUrl,
                draftExtraHeaders = normalized.extraHeaders,
                draftSystemPrompt = normalized.systemPrompt,
                modelStatus = normalized.statusSummary(),
                modelLoadError = if (gateway == null)
                    normalized.validationErrors().joinToString("; ").ifBlank { "API key required" }
                else null
            )
        }
    }

    fun updateDraftApiKey(value: String) = _ui.update { it.copy(draftApiKey = value) }
    fun updateDraftModelName(value: String) = _ui.update { it.copy(draftModelName = value) }
    fun updateDraftBaseUrl(value: String) = _ui.update { it.copy(draftBaseUrl = value) }
    fun updateDraftExtraHeaders(value: String) = _ui.update { it.copy(draftExtraHeaders = value) }
    fun updateDraftSystemPrompt(value: String) = _ui.update { it.copy(draftSystemPrompt = value) }
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
                onboarded = true
            )
            val result = ModelConnectionProbe.probe(candidate)
            withContext(Dispatchers.Main) {
                _ui.update {
                    it.copy(
                        probeMessage = when (result) {
                            is ProbeResult.Ok -> result.detail
                            is ProbeResult.Failed -> "Probe failed: ${result.reason}"
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
                systemPrompt = s.draftSystemPrompt,
                extraHeaders = s.draftExtraHeaders,
                onboarded = true
            )
        )
        _ui.update {
            it.copy(
                showModelSettings = false,
                detail = if (modelGateway != null) "Remote model gateway ready" else "Remote model settings saved (incomplete)"
            )
        }
    }

    private fun mountWorkspace(ws: ProjectWorkspace, files: List<String>, detail: String) {
        mutationCoordinator = MutationCoordinator(ws)
        tools = AgentTools(ws)
        rebuildAgentIfNeeded()
        rebuildChat()
        _ui.update {
            it.copy(
                workspace = ws,
                fileList = files,
                status = AgentStatus.READY,
                detail = detail,
                terminalHistory = tools?.terminalHistory() ?: emptyList(),
                terminalLiveOutput = ""
            )
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
                        "No project mounted. Import a folder, or restore a previous project, before coding requests."
                    agent == null ->
                        "Project is mounting — try again in a moment."
                    _ui.value.modelLoadError != null ->
                        "Model settings error: ${_ui.value.modelLoadError.orEmpty()}. Local commands still work."
                    else ->
                        "Agent not ready."
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
                    mountWorkspace(mounted, files, "${files.size} files indexed")
                    clearPendingApproval()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.STOPPED,
                            detail = "Import failed: ${err.message.orEmpty()}"
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
                    mountWorkspace(mounted, emptyList(), "New empty project ready")
                    clearPendingApproval()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.STOPPED,
                            detail = "Could not create project: ${err.message.orEmpty()}"
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
                detail = "Project cleared"
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
        _ui.update { it.copy(chatInput = "", status = AgentStatus.WORKING, detail = "Working...") }
        progressEpoch.incrementAndGet()
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val workspace = chat ?: run {
                    rebuildChat()
                    chat!!
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
                                ?: "The agent proposes a transactional code change.",
                            status = when (result) {
                                is AgentRuntimeResult.NeedsApproval -> AgentStatus.APPROVAL
                                is AgentRuntimeResult.Failed -> AgentStatus.FAILED
                                else -> AgentStatus.READY
                            },
                            detail = when (result) {
                                is AgentRuntimeResult.NeedsApproval ->
                                    "Waiting for dual owner approval"
                                is AgentRuntimeResult.Failed ->
                                    result.task?.summary ?: "Request failed"
                                else -> "Ready"
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
        agent?.cancel("Stopped by owner")
        chat?.cancel()
        tools?.cancelTerminal("Stopped by owner")
        _ui.update {
            it.copy(
                status = AgentStatus.STOPPED,
                detail = "Stopped",
                terminalLiveOutput = ""
            )
        }
    }

    fun isBusy(): Boolean = activeJob != null

    fun onChangeApplied(result: MutationApprovalResult.Applied) {
        val paths = result.changeSet.changes.map { it.path }.distinct()
        val verification = result.proposal.verification
        val verificationSummary = buildString {
            append(if (verification.passed) "Static verification passed." else "Static verification failed.")
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
                content = "APPLIED to disk after dual approval.\nFiles:\n" +
                    paths.joinToString("\n") { "- $it" } +
                    "\nRequest: ${result.proposal.request}\n$verificationSummary"
            )
        )
        _ui.update {
            it.copy(
                chatMessages = store.recentChatMessages(Int.MAX_VALUE).asReversed(),
                approvalCount = result.proposal.approvalCount,
                pendingApproval = false,
                pendingProposal = null,
                pendingProposalId = null,
                status = AgentStatus.READY,
                detail = "APPLIED ${paths.size} file(s): ${paths.joinToString()}\n$verificationSummary"
            )
        }
    }

    fun clearPendingApproval() {
        _ui.update {
            it.copy(
                pendingApproval = false,
                pendingProposal = null,
                pendingProposalId = null,
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
                            terminalLiveOutput = "",
                            status = AgentStatus.READY,
                            detail = "Terminal command finished"
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _ui.update {
                        it.copy(
                            status = AgentStatus.FAILED,
                            detail = e.message ?: "Terminal failed",
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
    fun setEditorContent(value: String) = _ui.update { it.copy(editorContent = value) }
    fun setTerminalCommand(value: String) = _ui.update { it.copy(terminalCommand = value) }
    fun setResearchQuery(value: String) = _ui.update { it.copy(researchQuery = value) }
    fun setStatus(status: AgentStatus, detail: String) = _ui.update { it.copy(status = status, detail = detail) }

    private fun mapAgentPhase(phase: String): AgentStatus = when (phase.lowercase()) {
        "working", "thinking", "tool", "tools" -> AgentStatus.WORKING
        "approval" -> AgentStatus.APPROVAL
        "failed", "error" -> AgentStatus.FAILED
        "stopped" -> AgentStatus.STOPPED
        else -> AgentStatus.READY
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
    val researchState: ResearchDisplayState = ResearchDisplayState.Idle,
    val terminalCommand: String = "",
    val terminalHistory: List<TerminalEntry> = emptyList(),
    val terminalLiveOutput: String = "",
    val pendingApproval: Boolean = false,
    val approvalCount: Int = 0,
    val pendingReason: String = "The agent proposes a transactional code change.",
    val pendingProposalId: String? = null,
    val pendingProposal: PendingChangeProposal? = null,
    val modelSettings: ModelSettings = ModelSettings(),
    val modelStatus: String = "Remote · set base URL, model, and API key",
    val modelProgress: ModelDownloadProgress? = null,
    val modelLoadError: String? = null,
    val showModelSettings: Boolean = false,
    val draftApiKey: String = "",
    val draftModelName: String = "",
    val draftBaseUrl: String = "",
    val draftExtraHeaders: String = "",
    val draftSystemPrompt: String = "",
    val probeMessage: String? = null
)
