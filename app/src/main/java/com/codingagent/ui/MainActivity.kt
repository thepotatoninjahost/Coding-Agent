package com.codingagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.codingagent.agent.AgentKnowledge
import com.codingagent.agent.AgentTools
import com.codingagent.agent.AutonomousAgent
import com.codingagent.agent.ChatMessage
import com.codingagent.agent.ChatRole
import com.codingagent.agent.ChatWorkspace
import com.codingagent.workspace.DeepResearchProgress
import com.codingagent.research.DurableDeepResearchProvider
import com.codingagent.workspace.EditorDocument
import com.codingagent.knowledge.KnowledgeBase
import com.codingagent.core.LocalStore
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelDownloadProgress
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelSettings
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.OwnerApprovalToken
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.PendingChangeProposal
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.research.ResearchDisplayState
import com.codingagent.workspace.ResearchHit
import com.codingagent.research.ResearchModeDetector
import com.codingagent.workspace.TerminalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.codingagent.agent.AgentRuntimeResult
import com.codingagent.model.ModelConnectionProbe
import com.codingagent.model.ProbeResult

/**
 * ONE JOB: Host activity and system entry for the coding workbench.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodingAgentApp(filesDir) }
    }
}

/**
 * ONE JOB: Top-level workbench state and navigation between surfaces.
 */
@Composable
private fun CodingAgentApp(privateDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalStore(context) }
    val knowledgeBase = remember { KnowledgeBase(context) }
    var workspace by remember { mutableStateOf<ProjectWorkspace?>(null) }
    var tab by remember { mutableStateOf(SurfaceTab.CHAT) }
    var status by remember { mutableStateOf(AgentStatus.READY) }
    var detail by remember { mutableStateOf("Talk freely — or New / Import a project") }
    var chatInput by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf(store.recentChatMessages().asReversed()) }
    var researchQuery by remember { mutableStateOf("") }
    var researchHits by remember { mutableStateOf(emptyList<ResearchHit>()) }
    var researchError by remember { mutableStateOf<String?>(null) }
    var researchState by remember { mutableStateOf(ResearchDisplayState()) }
    var projectQuery by remember { mutableStateOf("") }
    var editorPath by remember { mutableStateOf("") }
    var editorDocument by remember { mutableStateOf<EditorDocument?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var fileList by remember { mutableStateOf(emptyList<String>()) }
    var terminalCommand by remember { mutableStateOf("") }
    var terminalHistory by remember { mutableStateOf(emptyList<TerminalEntry>()) }
    var terminalLiveOutput by remember { mutableStateOf("") }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var pendingApproval by remember { mutableStateOf(false) }
    var approvalCount by remember { mutableStateOf(0) }
    var pendingReason by remember { mutableStateOf("The agent proposes a transactional code change.") }
    var pendingProposalId by remember { mutableStateOf<String?>(null) }
    var pendingProposal by remember { mutableStateOf<PendingChangeProposal?>(null) }
    var approvalPromptOpen by remember { mutableStateOf(false) }
    val mutationCoordinator = remember(workspace) { workspace?.let { MutationCoordinator(it) } }
    var messageQueue by remember { mutableStateOf(emptyList<String>()) }
    var modelSettings by remember { mutableStateOf(store.loadModelSettings()) }
    var modelGateway by remember { mutableStateOf<ModelGateway?>(null) }
    var modelStatus by remember {
        mutableStateOf(
            if (modelSettings.isRemoteConfigured()) modelSettings.statusSummary()
            else "Remote · set base URL, model, and API key"
        )
    }
    var modelProgress by remember { mutableStateOf<ModelDownloadProgress?>(null) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var showModelSettings by remember { mutableStateOf(false) }
    var draftApiKey by remember { mutableStateOf(modelSettings.apiKey) }
    var draftModelName by remember { mutableStateOf(modelSettings.modelName) }
    var draftBaseUrl by remember { mutableStateOf(modelSettings.baseUrl) }
    var draftExtraHeaders by remember { mutableStateOf(modelSettings.extraHeaders) }
    var probeMessage by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    fun applyModelSettings(settings: ModelSettings) {
        val normalized = settings.normalized().copy(backend = ModelBackend.REMOTE)
        store.saveModelSettings(normalized)
        modelSettings = normalized
        draftApiKey = normalized.apiKey
        draftModelName = normalized.modelName
        draftBaseUrl = normalized.baseUrl
        draftExtraHeaders = normalized.extraHeaders
        val gateway = normalized.remoteGateway()
        if (gateway != null) {
            modelGateway = gateway
            modelLoadError = null
            modelStatus = normalized.statusSummary()
        } else {
            modelGateway = null
            modelLoadError = normalized.validationErrors().joinToString("; ").ifBlank { "API key required" }
            modelStatus = normalized.statusSummary()
        }
    }

    LaunchedEffect(Unit) {
        // Model settings first so gateway exists as soon as project mounts (avoids first-message race).
        val loadedSettings = withContext(Dispatchers.IO) { store.loadModelSettings() }
        applyModelSettings(loadedSettings)
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
            workspace = mounted
            fileList = files
            status = AgentStatus.READY
            detail = "Restored project · ${files.size} files"
            if (!lastQuery.isNullOrBlank() && researchQuery.isBlank()) researchQuery = lastQuery
        }
        if (!modelSettings.isRemoteConfigured()) {
            showModelSettings = true
            if (workspace == null) {
                detail = "Enter base URL, model name, and API key in Model settings."
            }
        }
    }

    val tools = remember(workspace) { workspace?.let(::AgentTools) }
    // Single spine: AutonomousAgent only. Model gateway is optional for local lanes and offline edits.
    val agent = remember(workspace, modelGateway) {
        workspace?.let { current ->
            val gateway = modelGateway
            AutonomousAgent(
                root = current.projectRoot(),
                knowledge = object : AgentKnowledge {
                    override fun search(query: String, limit: Int) = knowledgeBase.search(query, limit)
                },
                gateway = gateway,
                research = DurableDeepResearchProvider(current.projectRoot().resolve(".coding-agent/research")),
                mutations = mutationCoordinator ?: MutationCoordinator(current)
            )
        }
    }
    // Tie agent-owned blocking work to the activity composition lifecycle.
    // Coroutine cancellation alone cannot interrupt synchronous gateway/terminal calls.
    DisposableEffect(agent) {
        onDispose { agent?.cancel("Activity composition disposed") }
    }

    val progressEpoch = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val chat = remember(agent, workspace, modelLoadError) {
        ChatWorkspace(
            store = store,
            runtimeProvider = { agent },
            unavailableMessageProvider = {
                when {
                    workspace == null ->
                        "No project mounted. Import a folder, or restore a previous project, before coding requests."
                    agent == null ->
                        "Project is mounting — try again in a moment."
                    modelLoadError != null ->
                        "Model settings error: ${modelLoadError.orEmpty()}. Local commands (hello, list files, status, read) still work."
                    else ->
                        "Agent not ready."
                }
            },
            progressListener = { phase, detailText ->
                val epoch = progressEpoch.get()
                scope.launch(Dispatchers.Main.immediate) {
                    if (epoch != progressEpoch.get()) return@launch
                    status = mapAgentPhase(phase)
                    detail = detailText.take(140)
                }
            }
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, ImportFlags) }
            .onFailure { status = AgentStatus.STOPPED; detail = "Folder permission failed" }
        scope.launch(Dispatchers.IO) {
            runCatching { importProject(context, privateDir, uri) }
                .onSuccess { imported ->
                    withContext(Dispatchers.Main) {
                        // Inline mount: local fun mountProject is declared later in this composable
                        // and is not in scope at folderPicker construction time.
                        val mounted = ProjectWorkspace(imported)
                        workspace = mounted
                        fileList = mounted.summary().files.map { it.path }.sorted()
                        store.saveProjectPath(imported.absolutePath)
                        pendingProposalId = null
                        pendingProposal = null
                        approvalCount = 0
                        pendingApproval = false
                        status = AgentStatus.READY
                        detail = "${fileList.size} files indexed"
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        status = AgentStatus.STOPPED
                        detail = "Import failed: ${it.message.orEmpty()}"
                    }
                }
        }
    }


    fun onChangeApplied(result: MutationApprovalResult.Applied) {
        val paths = result.changeSet.changes.map { it.path }.distinct()
        workspace?.let { ws ->
            fileList = ws.summary().files.map { it.path }.sorted()
        }
        approvalCount = result.proposal.approvalCount
        pendingApproval = false
        pendingProposal = null
        pendingProposalId = null
        status = AgentStatus.READY
        detail = "APPLIED ${paths.size} file(s): ${paths.joinToString().take(100)}"
        store.recordChatMessage(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = "APPLIED to disk after dual approval.\nFiles:\n" +
                    paths.joinToString("\n") { "- $it" } +
                    "\nRequest: ${result.proposal.request.take(200)}"
            )
        )
        chatMessages = store.recentChatMessages().asReversed()
    }

    fun approvePendingProposal() {
        val id = pendingProposalId ?: return
        val coordinator = mutationCoordinator ?: return
        if (activeJob != null || approvalPromptOpen) return
        if (coordinator.get(id) == null) {
            pendingApproval = false
            pendingProposal = null
            pendingProposalId = null
            approvalCount = 0
            status = AgentStatus.STOPPED
            detail = "The proposal is no longer pending."
            return
        }

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val biometricManager = BiometricManager.from(context)
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            status = AgentStatus.STOPPED
            detail = "Owner authentication is unavailable. Configure a strong biometric or device credential."
            return
        }

        approvalPromptOpen = true
        val activity = context as? ComponentActivity ?: run {
            approvalPromptOpen = false
            status = AgentStatus.FAILED
            detail = "Owner authentication is unavailable in this UI context."
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    approvalPromptOpen = false
                    status = AgentStatus.WORKING
                    detail = "Owner authenticated; recording approval off the UI thread…"
                    val token = OwnerApprovalToken.authenticated(id)
                    activeJob = scope.launch(Dispatchers.IO) {
                        val outcome = runCatching { coordinator.approve(id, token) }
                        withContext(Dispatchers.Main.immediate) {
                            outcome.onSuccess { result ->
                                when (result) {
                                    is MutationApprovalResult.AwaitingSecond -> {
                                        approvalCount = result.proposal.approvalCount
                                        pendingProposal = result.proposal
                                        pendingProposalId = result.proposal.id
                                        pendingApproval = true
                                        detail = "Authenticated confirmation ${approvalCount}/2 recorded; transaction remains unapplied"
                                        status = AgentStatus.APPROVAL
                                    }
                                    is MutationApprovalResult.RepairRequired -> {
                                        pendingProposal = result.proposal
                                        pendingProposalId = result.proposal.id
                                        pendingApproval = true
                                        approvalCount = result.proposal.approvalCount
                                        pendingReason = result.proposal.request
                                        status = AgentStatus.APPROVAL
                                        detail = "Original change was rolled back. Review the staged repair and authenticate again."
                                    }
                                    is MutationApprovalResult.Applied -> onChangeApplied(result)
                                    is MutationApprovalResult.Rejected -> {
                                        status = AgentStatus.STOPPED
                                        detail = result.reason
                                    }
                                }
                            }.onFailure {
                                status = AgentStatus.FAILED
                                detail = "Approval failed: " + (it.message.orEmpty().ifBlank { it.javaClass.simpleName })
                            }
                            activeJob = null
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    approvalPromptOpen = false
                    status = AgentStatus.STOPPED
                    detail = "Owner authentication cancelled or failed: $errString"
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm code change")
            .setSubtitle("Authenticate as the device owner to approve this proposal")
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(true)
            .build()
        prompt.authenticate(promptInfo)
    }

    fun stopAgent() {
        chat?.cancel()
        tools?.cancelTerminal()
        activeJob?.cancel()
        activeJob = null
        messageQueue = emptyList()
        progressEpoch.incrementAndGet()
        status = AgentStatus.STOPPED
        detail = "Stopped. Any queued follow-ups were cleared."
    }

    fun mountProject(dir: File, detailText: String) {
        val mounted = ProjectWorkspace(dir)
        workspace = mounted
        fileList = mounted.summary().files.map { it.path }.sorted()
        store.saveProjectPath(dir.absolutePath)
        pendingProposalId = null
        pendingProposal = null
        approvalCount = 0
        pendingApproval = false
        status = AgentStatus.READY
        detail = detailText
    }

    fun startNewProject(nameHint: String? = null) {
        scope.launch(Dispatchers.IO) {
            runCatching { createEmptyProject(privateDir, nameHint) }
                .onSuccess { created ->
                    withContext(Dispatchers.Main) {
                        mountProject(created, "New empty project · ${created.name}")
                        store.recordChatMessage(
                            ChatMessage(
                                role = ChatRole.SYSTEM,
                                content = "Created empty project `${created.name}`. Indexed sources: ${fileList.size}. " +
                                    "Ask me to add files, scaffold a structure, or import more code."
                            )
                        )
                        chatMessages = store.recentChatMessages().asReversed()
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        status = AgentStatus.FAILED
                        detail = "New project failed: ${it.message.orEmpty()}"
                    }
                }
        }
    }

    fun clearProject() {
        workspace = null
        fileList = emptyList()
        store.saveProjectPath(null)
        pendingProposalId = null
        pendingProposal = null
        approvalCount = 0
        pendingApproval = false
        status = AgentStatus.READY
        detail = "No project — New, Import, or say create project"
        store.recordChatMessage(
            ChatMessage(
                role = ChatRole.SYSTEM,
                content = "Project cleared. You can still talk. Say `create project` or tap New for an empty workspace, or Import an existing folder."
            )
        )
        chatMessages = store.recentChatMessages().asReversed()
    }

    /** When no project is mounted, handle conversation + create/restart without requiring the agent root. */
    fun handleNoProjectChat(request: String): String {
        val t = request.lowercase().trim()
        val createHints = listOf(
            "create project", "new project", "start project", "start a project",
            "create a project", "make a project", "empty project", "blank project",
            "restart project", "reset project"
        )
        if (createHints.any { t == it || t.startsWith("$it ") || t.startsWith("$it:") }) {
            val name = Regex("""(?:create|new|start|make|empty|blank|restart|reset)\s+project(?:\s+named)?\s+([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
                .find(request)?.groupValues?.getOrNull(1)
            startNewProject(name)
            return "Creating empty project${if (name != null) " `$name`" else ""}…"
        }
        if (t in listOf("hello", "hi", "hey", "yo", "sup", "ping", "help", "status", "what can you do")) {
            return buildString {
                append("Hello. Coding Agent is ready — no project is mounted yet.\n")
                append("You can still talk to me.\n\n")
                append("• Tap **New** or say `create project` — start an empty workspace\n")
                append("• Tap **Import** — copy an existing folder into the app\n")
                append("• Open **Model** — set base URL, model, API key for autonomous coding\n")
                append("\nOnce a project exists I can list files, read, research, and propose code changes.")
            }
        }
        return buildString {
            append("No project is mounted yet, so I cannot read or edit code.\n\n")
            append("Say `create project` (or tap **New**) for an empty workspace, ")
            append("or **Import** an existing folder. ")
            append("You can also say `hello` / `help` anytime.")
        }
    }

    fun send() {
        val request = chatInput.trim()
        if (request.isBlank()) return
        chatInput = ""

        // Create / new / restart project works with or without a current project.
        val lower = request.lowercase().trim()
        val createHints = listOf(
            "create project", "new project", "start project", "start a project",
            "create a project", "make a project", "empty project", "blank project",
            "restart project", "reset project"
        )
        if (createHints.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it:") }) {
            store.recordChatMessage(ChatMessage(role = ChatRole.USER, content = request))
            val name = Regex(
                """(?:create|new|start|make|empty|blank|restart|reset)\s+project(?:\s+named)?\s+([A-Za-z0-9._-]+)""",
                RegexOption.IGNORE_CASE
            ).find(request)?.groupValues?.getOrNull(1)
            store.recordChatMessage(
                ChatMessage(
                    role = ChatRole.AGENT,
                    content = "Creating empty project${if (name != null) " `$name`" else ""}…"
                )
            )
            chatMessages = store.recentChatMessages().asReversed()
            startNewProject(name)
            return
        }

        // No project: still conversational — help / explain, never a dead end.
        if (workspace == null) {
            store.recordChatMessage(ChatMessage(role = ChatRole.USER, content = request))
            val reply = handleNoProjectChat(request)
            store.recordChatMessage(ChatMessage(role = ChatRole.AGENT, content = reply))
            chatMessages = store.recentChatMessages().asReversed()
            status = AgentStatus.READY
            detail = reply.lineSequence().firstOrNull().orEmpty().take(120)
            return
        }

        val currentChat = chat ?: return
        if (activeJob != null) {
            messageQueue = messageQueue + request
            detail = "Working; queued ${messageQueue.size} follow-up message(s)"
            return
        }
        activeJob = scope.launch {
            progressEpoch.incrementAndGet()
            status = AgentStatus.PLANNING
            detail = "Starting request…"
            val outcome = withContext(Dispatchers.IO) { runCatching { currentChat.send(request) } }
            progressEpoch.incrementAndGet()
            outcome.onSuccess {
                chatMessages = currentChat.history()
                val proposal = it.result?.let { result ->
                    when (result) {
                        is com.codingagent.agent.AgentRuntimeResult.NeedsApproval -> agent?.pendingProposals()?.firstOrNull { pending -> pending.id == result.proposalId }
                        else -> null
                    }
                }
                pendingProposal = proposal
                pendingProposalId = proposal?.id
                pendingApproval = proposal != null
                approvalCount = proposal?.approvalCount ?: 0
                pendingReason = proposal?.request ?: pendingReason
                status = when (it.result) {
                    is com.codingagent.agent.AgentRuntimeResult.NeedsApproval -> AgentStatus.APPROVAL
                    is com.codingagent.agent.AgentRuntimeResult.NeedsInput -> AgentStatus.STOPPED
                    is com.codingagent.agent.AgentRuntimeResult.Failed -> AgentStatus.FAILED
                    else -> AgentStatus.READY
                }
                detail = when (it.result) {
                    is com.codingagent.agent.AgentRuntimeResult.Failed ->
                        "Agent failed: ${it.response.content.lineSequence().firstOrNull().orEmpty().take(140)}"
                    is com.codingagent.agent.AgentRuntimeResult.NeedsApproval ->
                        "Waiting for two owner approvals before any file write"
                    else ->
                        it.response.content.lineSequence().firstOrNull().orEmpty().take(120)
                }
            }.onFailure {
                val message = it.message.orEmpty().ifBlank { it.javaClass.simpleName }
                store.recordChatMessage(ChatMessage(role = ChatRole.SYSTEM, content = "Request failed: $message"))
                chatMessages = currentChat.history()
                status = AgentStatus.FAILED
                detail = "Request failed: ${message.take(140)}"
            }
            activeJob = null
            if (messageQueue.isNotEmpty()) {
                val next = messageQueue.first()
                messageQueue = messageQueue.drop(1)
                chatInput = next
                status = AgentStatus.READY
                detail = "Follow-up ready to send"
            }
        }
    }

    Surface(color = Canvas) {
        Scaffold(
            containerColor = Canvas,
            topBar = {
                CompactStatusBar(status, detail, workspace != null, modelStatus, modelProgress,
                    onImport = { folderPicker.launch(null) },
                    onNewProject = { startNewProject(null) },
                    onModelImport = {
                        draftApiKey = modelSettings.apiKey
                        draftModelName = modelSettings.modelName
                        draftBaseUrl = modelSettings.baseUrl
                        draftExtraHeaders = modelSettings.extraHeaders
                        probeMessage = null
                        showModelSettings = true
                    },
                    onStop = ::stopAgent)
            },
            bottomBar = {
                if (!imeVisible) {
                    NavigationBar(containerColor = Panel, contentColor = Ink) {
                        SurfaceTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Text(item.label.take(1), fontWeight = FontWeight.Bold, color = if (tab == item) NeonGreen else SoftGreen) },
                                label = { Text(item.label, fontSize = 10.sp, color = if (tab == item) NeonGreen else SoftGreen) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when (tab) {
                    SurfaceTab.CHAT -> ChatSurface(
                        messages = chatMessages,
                        input = chatInput,
                        onInput = { chatInput = it },
                        onSend = ::send,
                        busy = activeJob != null,
                        onStop = ::stopAgent,
                        pendingApproval = pendingApproval,
                        approvalCount = approvalCount,
                        reason = pendingReason,
                        onApprove = ::approvePendingProposal
                    )
                    SurfaceTab.FILES -> FilesSurface(fileList, projectQuery, { projectQuery = it }, editorPath, { editorPath = it }, editorContent, { editorContent = it }, editorDocument, tools, mutationCoordinator, onStatus = { status = it.first; detail = it.second })
                    SurfaceTab.REVIEW -> ReviewSurface(pendingApproval, approvalCount, pendingReason, onApprove = ::approvePendingProposal, onReject = { pendingProposalId?.let { mutationCoordinator?.reject(it) }; pendingProposal = null; pendingApproval = false; pendingProposalId = null; approvalCount = 0; status = AgentStatus.STOPPED; detail = "Proposed changes rejected" })
                    SurfaceTab.TERMINAL -> TerminalSurface(
                        command = terminalCommand,
                        onCommand = { terminalCommand = it },
                        history = terminalHistory,
                        liveOutput = terminalLiveOutput,
                        cwd = tools?.terminalWorkingDirectory()?.absolutePath ?: "—",
                        shell = tools?.terminalShellPath ?: "—",
                        timeoutSeconds = tools?.terminalTimeoutSeconds() ?: 90L,
                        running = tools?.isTerminalBusy() == true,
                        enabled = tools != null,
                        onRun = { command ->
                            terminalLiveOutput = ""
                            activeJob = scope.launch(Dispatchers.IO) {
                                status = AgentStatus.RUNNING
                                runCatching {
                                    tools?.terminal(
                                        command.trim().split(Regex("\\s+")).filter(String::isNotBlank),
                                        onStdout = { chunk -> scope.launch(Dispatchers.Main.immediate) { terminalLiveOutput += chunk } },
                                        onStderr = { chunk -> scope.launch(Dispatchers.Main.immediate) { terminalLiveOutput += chunk } }
                                    )
                                }.onSuccess { entry ->
                                    if (entry != null) {
                                        terminalHistory = (terminalHistory + entry).takeLast(40)
                                    }
                                }.onFailure { failure ->
                                    terminalLiveOutput += "\\n" + failure.message.orEmpty()
                                }
                                status = AgentStatus.READY
                                detail = "Terminal finished"
                            }
                        },
                        onStop = ::stopAgent,
                        onClear = {
                            tools?.clearTerminalHistory()
                            terminalHistory = emptyList()
                            terminalLiveOutput = ""
                        }
                    )
                    SurfaceTab.RESEARCH -> ResearchSurface(researchQuery, { researchQuery = it }, researchHits, researchError, researchState, onSearch = { query ->
                        store.saveLastResearchQuery(query)
                        activeJob = scope.launch(Dispatchers.IO) {
                            status = AgentStatus.RESEARCHING
                            detail = "Reading distinct full sources"
                            val researchRoot = workspace?.projectRoot()?.resolve(".coding-agent/research") ?: privateDir.resolve(".coding-agent/research")
                            val provider = DurableDeepResearchProvider(researchRoot)
                            val result = runCatching { provider.deepResearch(query, 12, ResearchModeDetector.detect(query)) { progress -> researchState = progress.toDisplayState() } }
                            result.onSuccess { session ->
                                researchHits = session.sources.map { source -> ResearchHit(source.title, source.url, source.content.take(600)) }
                                researchState = researchState.copy(phase = "learned", completed = session.sources.size, total = session.sources.size, fullSources = session.sources.size, laneCount = session.sources.map { it.lane }.distinct().size, wordCount = session.sources.sumOf { it.wordCount }, codeExamples = session.sources.sumOf { it.codeExamples.size }, canSend = true)
                                status = AgentStatus.READY
                                detail = "Learned ${session.sources.size} distinct full sources"
                            }.onFailure { error ->
                                val message = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                                researchError = message
                                researchState = researchState.copy(phase = "blocked", canSend = true)
                                status = AgentStatus.STOPPED
                                detail = "Research failed: ${message.take(140)}"
                            }
                            activeJob = null
                        }
                    })
                }
            }
        }

        if (showModelSettings) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showModelSettings = false }) {
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Remote model API", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Remote API endpoint. Enter the base URL, model name, and API key for the provider you are using.",
                            color = SoftGreen,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = draftBaseUrl,
                            onValueChange = { draftBaseUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Base URL", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draftModelName,
                            onValueChange = { draftModelName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Model", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draftApiKey,
                            onValueChange = { draftApiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API key", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = draftExtraHeaders,
                            onValueChange = { draftExtraHeaders = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Extra headers (optional)", color = SoftGreen) },
                            placeholder = { Text("HTTP-Referer: https://example.com\nX-Title: Coding-Agent", color = SoftGreen.copy(alpha = 0.5f)) },
                            minLines = 2,
                            maxLines = 5,
                            colors = fieldColors()
                        )
                        Text(
                            "Any provider. One header per line as Name: value. OpenRouter fills Referer/Title if left blank.",
                            color = SoftGreen,
                            fontSize = 10.sp
                        )
                        if (probeMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(probeMessage.orEmpty(), color = SoftGreen, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showModelSettings = false }) {
                                Text("Cancel", color = SoftGreen)
                            }
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val candidate = ModelSettings(
                                        backend = ModelBackend.REMOTE,
                                        baseUrl = draftBaseUrl,
                                        apiKey = draftApiKey,
                                        modelName = draftModelName,
                                        extraHeaders = draftExtraHeaders,
                                        onboarded = true
                                    )
                                    val result = com.codingagent.model.ModelConnectionProbe.probe(candidate)
                                    withContext(Dispatchers.Main) {
                                        probeMessage = when (result) {
                                            is com.codingagent.model.ProbeResult.Ok -> result.detail
                                            is com.codingagent.model.ProbeResult.Failed -> "Probe failed: ${result.reason}"
                                        }
                                    }
                                }
                            }) {
                                Text("Test", color = FluoroOrange)
                            }
                            TextButton(onClick = {
                                applyModelSettings(
                                    ModelSettings(
                                        backend = ModelBackend.REMOTE,
                                        baseUrl = draftBaseUrl,
                                        apiKey = draftApiKey,
                                        modelName = draftModelName,
                                        extraHeaders = draftExtraHeaders,
                                        onboarded = true
                                    )
                                )
                                showModelSettings = false
                                detail = if (modelGateway != null) "Remote model gateway ready" else "Remote model settings saved (incomplete)"
                            }) {
                                Text("Save", color = NeonGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
