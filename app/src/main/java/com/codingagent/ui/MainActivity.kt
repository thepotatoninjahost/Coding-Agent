package com.codingagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.codingagent.core.AgentJournal
import com.codingagent.core.AgentKnowledge
import com.codingagent.core.AgentTools
import com.codingagent.core.AutonomousAgent
import com.codingagent.core.ChatMessage
import com.codingagent.core.ChatRole
import com.codingagent.core.ChatWorkspace
import com.codingagent.core.CodingAgentRuntime
import com.codingagent.core.CompositeWebResearchProvider
import com.codingagent.core.DeepResearchProgress
import com.codingagent.core.DurableDeepResearchProvider
import com.codingagent.core.EditorDocument
import com.codingagent.core.KnowledgeBase
import com.codingagent.core.LocalStore
import com.codingagent.core.ModelDownloadProgress
import com.codingagent.core.MutationApprovalResult
import com.codingagent.core.MutationCoordinator
import com.codingagent.core.NexaLocalModelGateway
import com.codingagent.core.NexaModelProvisioner
import com.codingagent.core.PendingChangeProposal
import com.codingagent.core.ProjectWorkspace
import com.codingagent.core.ResearchDisplayState
import com.codingagent.core.ResearchHit
import com.codingagent.core.ResearchModeDetector
import com.codingagent.core.TerminalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================
// FALL OUT PIP-BOY THEME
// neon green + fluorescent orange + dark purple
// ============================================================
private val NeonGreen = Color(0xFF39FF14)
private val FluoroOrange = Color(0xFFFF6B00)
private val DarkPurple = Color(0xFF12081F)
private val PanelPurple = Color(0xFF1E1033)
private val RaisedPurple = Color(0xFF2A1848)
private val LinePurple = Color(0xFF4A2F6A)
private val SoftGreen = Color(0xFF7ACC7A)
private val DangerRed = Color(0xFFFF4500)

// aliases used throughout the UI
private val Ink = NeonGreen
private val Muted = SoftGreen
private val Canvas = DarkPurple
private val Panel = PanelPurple
private val PanelRaised = RaisedPurple
private val Line = LinePurple
private val Accent = NeonGreen
private val Blue = FluoroOrange
private val Amber = FluoroOrange
private val Danger = DangerRed

private enum class SurfaceTab(val label: String) { CHAT("Chat"), FILES("Files"), REVIEW("Review"), TERMINAL("Terminal"), RESEARCH("Research") }
private enum class AgentStatus(val label: String, val color: Color) {
    READY("Ready", Accent),
    PLANNING("Planning", Blue),
    RESEARCHING("Researching", Blue),
    WORKING("Working", Blue),
    MODEL("Model", Blue),
    TOOL("Tool", Amber),
    EDITING("Editing", Amber),
    APPROVAL("Waiting for approval", Amber),
    RUNNING("Verifying", Blue),
    FAILED("Failed", Danger),
    STOPPED("Stopped", Danger)
}

/** Map AutonomousAgent phase names to status-bar labels. Never invent a fake phase. */
private fun mapAgentPhase(phase: String): AgentStatus = when (phase.uppercase()) {
    "STARTED", "INTAKE", "PLAN", "PLANNING" -> AgentStatus.PLANNING
    "RESEARCH" -> AgentStatus.RESEARCHING
    "MODEL" -> AgentStatus.MODEL
    "TOOL" -> AgentStatus.TOOL
    "APPROVAL" -> AgentStatus.APPROVAL
    "DONE", "COMPLETED" -> AgentStatus.READY
    "FAILED" -> AgentStatus.FAILED
    else -> AgentStatus.WORKING
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodingAgentApp(filesDir) }
    }
}

@Composable
private fun CodingAgentApp(privateDir: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalStore(context) }
    val knowledgeBase = remember { KnowledgeBase(context) }
    var workspace by remember { mutableStateOf<ProjectWorkspace?>(null) }
    var tab by remember { mutableStateOf(SurfaceTab.CHAT) }
    var status by remember { mutableStateOf(AgentStatus.READY) }
    var detail by remember { mutableStateOf("Import a project to begin") }
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
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var pendingApproval by remember { mutableStateOf(false) }
    var approvalCount by remember { mutableStateOf(0) }
    var pendingReason by remember { mutableStateOf("The agent proposes a transactional code change.") }
    var pendingProposalId by remember { mutableStateOf<String?>(null) }
    var pendingProposal by remember { mutableStateOf<PendingChangeProposal?>(null) }
    val mutationCoordinator = remember(workspace) { workspace?.let { MutationCoordinator(it) } }
    var messageQueue by remember { mutableStateOf(emptyList<String>()) }
    var modelStatus by remember { mutableStateOf("Preparing Qwen3-4B NPU (mobile) model") }
    var modelProgress by remember { mutableStateOf<ModelDownloadProgress?>(null) }
    var localModel by remember { mutableStateOf<NexaLocalModelGateway?>(null) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            store.loadProjectPath()?.let { path ->
                val dir = File(path)
                if (dir.isDirectory) {
                    runCatching {
                        val mounted = ProjectWorkspace(dir)
                        workspace = mounted
                        fileList = mounted.summary().files.map { it.path }.sorted()
                        status = AgentStatus.READY
                        detail = "Restored project · ${fileList.size} files"
                    }
                } else {
                    store.saveProjectPath(null)
                }
            }
            store.loadLastResearchQuery()?.let { q -> if (researchQuery.isBlank()) researchQuery = q }

            runCatching {
                NexaModelProvisioner({ name -> context.assets.open(name) }, privateDir).ensure { progress ->
                    modelProgress = progress
                    modelStatus = "${progress.phase}: ${progress.percent}% (${progress.currentFile})"
                }
            }.onSuccess {
                modelStatus = "Qwen3-4B NPU (mobile) files verified; loading NPU runtime"
                runCatching { NexaLocalModelGateway(context, it.directory) }
                    .onSuccess { gateway ->
                        localModel = gateway
                        modelLoadError = null
                        modelStatus = "Qwen3-4B NPU (mobile) active"
                    }
                    .onFailure { error ->
                        modelLoadError = listOf(error.message, error.cause?.message, error.javaClass.name).mapNotNull { it?.takeIf(String::isNotBlank) }.joinToString(" | ").ifBlank { error.stackTraceToString().take(500) }
                        modelStatus = "Model load failed: ${modelLoadError.orEmpty().take(500)}"
                    }
            }.onFailure {
                modelLoadError = listOf(it.message, it.cause?.message, it.javaClass.name).mapNotNull { m -> m?.takeIf(String::isNotBlank) }.joinToString(" | ").ifBlank { it.stackTraceToString().take(500) }
                modelStatus = "Model setup failed: ${modelLoadError.orEmpty().take(500)}"
            }
        }
    }

    val tools = remember(workspace) { workspace?.let(::AgentTools) }
    val agent = remember(workspace, localModel) {
        workspace?.let { current ->
            val runtime = CodingAgentRuntime(current, object : AgentKnowledge {
                override fun search(query: String, limit: Int) = knowledgeBase.search(query, limit)
            }, AgentJournal(current.projectRoot()), research = CompositeWebResearchProvider(), deepResearch = DurableDeepResearchProvider(current.projectRoot().resolve(".coding-agent/research")), modelGateway = localModel, mutationCoordinator = mutationCoordinator ?: MutationCoordinator(current))
            localModel?.let { gateway ->
                AutonomousAgent(current.projectRoot(), runtime, object : AgentKnowledge {
                    override fun search(query: String, limit: Int) = knowledgeBase.search(query, limit)
                }, gateway, research = DurableDeepResearchProvider(current.projectRoot().resolve(".coding-agent/research")), mutations = mutationCoordinator ?: MutationCoordinator(current))
            }
        }
    }
    // Epoch invalidates in-flight progress updates when a turn ends or is stopped,
    // so late Main posts cannot clobber READY / FAILED / STOPPED.
    val progressEpoch = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    val chat = remember(agent, workspace, modelLoadError) {
        ChatWorkspace(
            store = store,
            runtimeProvider = { agent },
            unavailableMessageProvider = {
                when {
                    workspace == null -> "Choose a project folder before sending coding requests."
                    modelLoadError != null -> "Model unavailable. ${modelLoadError.orEmpty()}"
                    else -> "Model is still loading. Wait for the model status to show active before sending coding requests."
                }
            },
            progressListener = { phase, detailText ->
                // Capture epoch at post time; apply on Main only if this turn is still current.
                val epoch = progressEpoch.get()
                scope.launch(Dispatchers.Main.immediate) {
                    if (epoch != progressEpoch.get()) return@launch
                    status = mapAgentPhase(phase)
                    detail = detailText.take(140)
                }
            }
        )
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching { importModelPackage(context, privateDir, uri) }
                .onSuccess { summary -> modelStatus = summary; status = AgentStatus.READY; detail = "Model package staged; restart model loading if needed" }
                .onFailure { modelStatus = "Import failed"; status = AgentStatus.STOPPED; detail = it.message.orEmpty() }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, ImportFlags) }
            .onFailure { status = AgentStatus.STOPPED; detail = "Folder permission failed" }
        scope.launch(Dispatchers.IO) {
            runCatching { importProject(context, privateDir, uri) }
                .onSuccess { imported ->
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
                .onFailure { status = AgentStatus.STOPPED; detail = "Import failed: ${it.message.orEmpty()}" }
        }
    }

    fun stopAgent() {
        activeJob?.cancel()
        activeJob = null
        messageQueue = emptyList()
        progressEpoch.incrementAndGet()
        status = AgentStatus.STOPPED
        detail = "Stopped. Any queued follow-ups were cleared."
    }

    fun send() {
        val request = chatInput.trim()
        val currentChat = chat ?: return
        if (request.isBlank()) return
        chatInput = ""
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
            // Invalidate any progress posts still queued from this turn before applying terminal status.
            progressEpoch.incrementAndGet()
            outcome.onSuccess {
                chatMessages = currentChat.history()
                val proposal = it.result?.let { result ->
                    when (result) {
                        is com.codingagent.core.AgentRuntimeResult.NeedsApproval -> agent?.pendingProposals()?.firstOrNull { pending -> pending.id == result.proposalId }
                        else -> null
                    }
                }
                pendingProposal = proposal
                pendingProposalId = proposal?.id
                pendingApproval = proposal != null
                approvalCount = proposal?.approvalCount ?: 0
                pendingReason = proposal?.request ?: pendingReason
                status = when (it.result) {
                    is com.codingagent.core.AgentRuntimeResult.NeedsApproval -> AgentStatus.APPROVAL
                    is com.codingagent.core.AgentRuntimeResult.NeedsInput -> AgentStatus.STOPPED
                    is com.codingagent.core.AgentRuntimeResult.Failed -> AgentStatus.FAILED
                    else -> AgentStatus.READY
                }
                detail = when (it.result) {
                    is com.codingagent.core.AgentRuntimeResult.Failed ->
                        "Agent failed: ${it.response.content.lineSequence().firstOrNull().orEmpty().take(140)}"
                    is com.codingagent.core.AgentRuntimeResult.NeedsApproval ->
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
                    onModelImport = { modelPicker.launch(null) },
                    onStop = ::stopAgent)
            },
            // Hide bottom nav when keyboard is open so it does not steal space
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
                        onApprove = {
                            val id = pendingProposalId ?: return@ChatSurface
                            val coordinator = mutationCoordinator ?: return@ChatSurface
                            when (val result = coordinator.approve(id, ownerVerified = true, ownerLabel = "owner")) {
                                is MutationApprovalResult.AwaitingSecond -> { approvalCount = result.proposal.approvalCount; detail = "Confirmation ${approvalCount}/2 recorded; transaction remains unapplied" }
                                is MutationApprovalResult.Applied -> { approvalCount = result.proposal.approvalCount; pendingApproval = false; pendingProposal = null; pendingProposalId = null; status = AgentStatus.RUNNING; detail = "Approved transaction applied; verification required" }
                                is MutationApprovalResult.Rejected -> { status = AgentStatus.STOPPED; detail = result.reason }
                            }
                        }
                    )
                    SurfaceTab.FILES -> FilesSurface(fileList, projectQuery, { projectQuery = it }, editorPath, { editorPath = it }, editorContent, { editorContent = it }, editorDocument, tools, mutationCoordinator, onStatus = { status = it.first; detail = it.second })
                    SurfaceTab.REVIEW -> ReviewSurface(pendingApproval, approvalCount, pendingReason, onApprove = {
                        val id = pendingProposalId ?: return@ReviewSurface
                        val coordinator = mutationCoordinator ?: return@ReviewSurface
                        when (val result = coordinator.approve(id, ownerVerified = true, ownerLabel = "owner")) {
                            is MutationApprovalResult.AwaitingSecond -> { approvalCount = result.proposal.approvalCount; detail = "Confirmation ${approvalCount}/2 recorded" }
                            is MutationApprovalResult.Applied -> { approvalCount = result.proposal.approvalCount; pendingApproval = false; pendingProposal = null; pendingProposalId = null; status = AgentStatus.RUNNING; detail = "Approved transaction applied" }
                            is MutationApprovalResult.Rejected -> { status = AgentStatus.STOPPED; detail = result.reason }
                        }
                    }, onReject = { pendingProposalId?.let { mutationCoordinator?.reject(it) }; pendingProposal = null; pendingApproval = false; pendingProposalId = null; approvalCount = 0; status = AgentStatus.STOPPED; detail = "Proposed changes rejected" })
                    SurfaceTab.TERMINAL -> TerminalSurface(terminalCommand, { terminalCommand = it }, terminalHistory, tools != null, onRun = { command -> activeJob = scope.launch(Dispatchers.IO) { status = AgentStatus.RUNNING; tools?.terminal(command.trim().split(Regex("\\s+")).filter(String::isNotBlank))?.let { terminalHistory = (terminalHistory + it).takeLast(40) }; status = AgentStatus.READY; detail = "Terminal finished" } }, onStop = ::stopAgent)
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
    }
}

@Composable
private fun CompactStatusBar(
    status: AgentStatus,
    detail: String,
    mounted: Boolean,
    modelStatus: String,
    modelProgress: ModelDownloadProgress?,
    onImport: () -> Unit,
    onModelImport: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .border(1.dp, NeonGreen.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CODING AGENT", color = NeonGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (mounted) "project mounted · $modelStatus" else "no project · $modelStatus",
                    color = SoftGreen,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            if (status != AgentStatus.READY && status != AgentStatus.STOPPED) {
                TextButton(onClick = onStop) { Text("Stop", color = Danger, fontSize = 12.sp) }
            }
            TextButton(onClick = onModelImport) { Text("Model", color = FluoroOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            TextButton(onClick = onImport) { Text(if (mounted) "Switch" else "Import", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(status.color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(status.label, color = status.color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(detail, color = SoftGreen, fontSize = 11.sp, maxLines = 1)
        }
        if (status == AgentStatus.RESEARCHING || status == AgentStatus.PLANNING || status == AgentStatus.WORKING || status == AgentStatus.MODEL || status == AgentStatus.TOOL || status == AgentStatus.RUNNING || modelProgress?.phase == "downloading") {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { ((modelProgress?.percent ?: 0) / 100f).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth(),
                color = status.color,
                trackColor = Line
            )
        }
    }
}

@Composable
private fun ChatSurface(
    messages: List<ChatMessage>,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    onStop: () -> Unit,
    pendingApproval: Boolean,
    approvalCount: Int,
    reason: String,
    onApprove: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Type a request in plain English about your project.\nThe agent will look at your files and reply here.",
                        color = SoftGreen,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(messages, key = { it.id }) { ChatBubble(it) }
        }

        if (pendingApproval) ApprovalCard(approvalCount, reason, onApprove)
        if (busy) {
            Text(
                "Agent is working…",
                color = FluoroOrange,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // LARGE multi-line input — stays above keyboard, you can see the whole sentence
        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .border(1.dp, NeonGreen.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Describe what you want fixed or added…", color = SoftGreen) },
                minLines = 4,
                maxLines = 8,
                textStyle = TextStyle(color = NeonGreen, fontSize = 16.sp, lineHeight = 22.sp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    TextButton(onClick = onStop) { Text("Stop", color = Danger) }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
                ) { Text("Send", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approvalCount: Int, reason: String, onApprove: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = RaisedPurple),
        border = androidx.compose.foundation.BorderStroke(1.dp, FluoroOrange)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CODE CHANGE REVIEW", color = FluoroOrange, fontWeight = FontWeight.Bold)
            Text(reason, color = NeonGreen, fontSize = 13.sp)
            Text("Two explicit approvals are required before a code transaction can proceed.", color = SoftGreen, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${approvalCount}/2", color = SoftGreen, modifier = Modifier.weight(1f))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = FluoroOrange, contentColor = DarkPurple)
                ) { Text(if (approvalCount == 0) "Confirm" else "Confirm again") }
            }
        }
    }
}

@Composable
private fun FilesSurface(
    files: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    path: String,
    onPath: (String) -> Unit,
    content: String,
    onContent: (String) -> Unit,
    document: EditorDocument?,
    tools: AgentTools?,
    mutationCoordinator: MutationCoordinator?,
    onStatus: (Pair<AgentStatus, String>) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val filtered = files.filter { it.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PROJECT FILES", color = NeonGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("Filter files", color = SoftGreen) }, singleLine = true, colors = fieldColors())
        if (showEditor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(path, color = FluoroOrange, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(onClick = { showEditor = false }) { Text("Close", color = SoftGreen) }
            }
            CodeEditor(content, onContent, path)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onContent(document?.content.orEmpty()) }, colors = ButtonDefaults.buttonColors(containerColor = RaisedPurple)) { Text("Revert", color = NeonGreen) }
                Button(
                    onClick = {
                        runCatching {
                            val coordinator = mutationCoordinator ?: error("Project mutation coordinator is unavailable")
                            val proposal = tools?.proposeSave(path, content, coordinator) ?: error("Project tools are unavailable")
                            onStatus(AgentStatus.APPROVAL to "Save proposed; confirm twice in Review (${proposal.id.take(8)})")
                        }.onFailure { onStatus(AgentStatus.STOPPED to (it.message ?: "Save proposal failed")) }
                    },
                    enabled = document != null && document.content != content && mutationCoordinator != null,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
                ) { Text("Propose save") }
            }
        } else {
            if (filtered.isEmpty()) EmptyState("No files", "Import a project or change the filter.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it }) { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Panel, RoundedCornerShape(12.dp))
                            .border(1.dp, LinePurple, RoundedCornerShape(12.dp))
                            .clickable {
                                runCatching {
                                    val loaded = tools?.read(file) ?: return@runCatching
                                    onPath(file)
                                    onContent(loaded.content)
                                    showEditor = true
                                    onStatus(AgentStatus.READY to "Opened $file")
                                }.onFailure { onStatus(AgentStatus.STOPPED to "Open failed") }
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("{ }", color = FluoroOrange, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(file, color = NeonGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeEditor(content: String, onContent: (String) -> Unit, path: String) {
    val highlighted = remember(content, path) { highlightCode(content) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(DarkPurple, RoundedCornerShape(12.dp))
            .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        BasicTextField(
            value = content,
            onValueChange = onContent,
            modifier = Modifier.fillMaxSize(),
            textStyle = TextStyle(color = Color.Transparent, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize()) {
                    Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
                    inner()
                }
            }
        )
    }
}

private fun highlightCode(content: String): AnnotatedString = buildAnnotatedString {
    val keywords = Regex("\\b(fun|class|object|interface|val|var|if|else|when|return|import|package|public|private|suspend|async|await|def|from|const|let|function|export|type)\\b")
    var cursor = 0
    keywords.findAll(content).forEach { match ->
        append(content.substring(cursor, match.range.first))
        withStyle(SpanStyle(color = FluoroOrange, fontWeight = FontWeight.Bold)) { append(match.value) }
        cursor = match.range.last + 1
    }
    append(content.substring(cursor))
}

@Composable
private fun ReviewSurface(pending: Boolean, approvals: Int, reason: String, onApprove: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CHANGE REVIEW", color = NeonGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (!pending) {
            EmptyState("No pending changes", "Agent proposals appear here before any file transaction.")
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), border = androidx.compose.foundation.BorderStroke(1.dp, FluoroOrange)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pending transactional proposal", color = FluoroOrange, fontWeight = FontWeight.Bold)
                    Text(reason, color = NeonGreen)
                    Text("Review changed files before confirming. Transactional writes remain checksum-guarded.", color = SoftGreen, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReject, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2030))) { Text("Reject", color = NeonGreen) }
                        Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = FluoroOrange, contentColor = DarkPurple)) { Text("Confirm ${approvals + 1}/2") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSurface(
    command: String,
    onCommand: (String) -> Unit,
    history: List<TerminalEntry>,
    enabled: Boolean,
    onRun: (String) -> Unit,
    onStop: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TERMINAL", color = NeonGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text("Stop", color = Danger) }
        }
        OutlinedTextField(
            command,
            onCommand,
            Modifier.fillMaxWidth(),
            placeholder = { Text("./gradlew testDebugUnitTest", color = SoftGreen) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            colors = fieldColors()
        )
        Button(
            onClick = { onRun(command) },
            enabled = enabled && command.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
        ) { Text("Run") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { "${it.command}-${it.stdout.hashCode()}" }) { entry ->
                Text(
                    "$ ${entry.command}\n${entry.stdout}${entry.stderr}\nexit=${entry.exitCode}${if (entry.timedOut) " timeout" else ""}",
                    Modifier.fillMaxWidth().background(DarkPurple, RoundedCornerShape(10.dp)).border(1.dp, LinePurple, RoundedCornerShape(10.dp)).padding(12.dp),
                    color = if (entry.exitCode == 0) NeonGreen else Danger,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun DeepResearchProgress.toDisplayState() =
    ResearchDisplayState(stage, completed, total, successful, failed, canSend = false)

@Composable
private fun ResearchSurface(
    query: String,
    onQuery: (String) -> Unit,
    hits: List<ResearchHit>,
    error: String?,
    state: ResearchDisplayState,
    onSearch: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("WEB RESEARCH", color = NeonGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Type a full sentence in plain English. You can see the whole text while typing.", color = SoftGreen, fontSize = 13.sp)
        Text(
            "${state.phase.uppercase()}  ${state.completed}/${state.total} sources  •  full=${state.fullSources}  failed=${state.failedSources}",
            color = if (state.phase == "blocked") Danger else FluoroOrange,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Describe what you want to research in plain English…", color = SoftGreen) },
            minLines = 5,
            maxLines = 10,
            textStyle = TextStyle(color = NeonGreen, fontSize = 16.sp, lineHeight = 22.sp),
            colors = fieldColors()
        )
        Button(
            onClick = { onSearch(query) },
            enabled = query.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DarkPurple)
        ) { Text("Search", fontWeight = FontWeight.Bold, fontSize = 16.sp) }

        error?.let { Text(it, color = Danger, fontSize = 12.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits, key = { it.url }) { hit ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LinePurple)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(hit.title, color = NeonGreen, fontWeight = FontWeight.Bold)
                        Text(hit.excerpt, color = SoftGreen, fontSize = 13.sp)
                        Text(hit.url, color = FluoroOrange, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val user = message.role == ChatRole.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(if (user) Color(0xFF0F2A14) else Panel, RoundedCornerShape(14.dp))
                .border(1.dp, if (user) NeonGreen.copy(alpha = 0.5f) else LinePurple, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                if (user) "YOU" else if (message.role == ChatRole.AGENT) "AGENT" else "SYSTEM",
                color = if (user) NeonGreen else FluoroOrange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(message.content, color = NeonGreen, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp))
        Text(body, color = SoftGreen, fontSize = 13.sp)
    }
}

@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = NeonGreen,
    unfocusedTextColor = NeonGreen,
    focusedBorderColor = NeonGreen,
    unfocusedBorderColor = LinePurple,
    cursorColor = NeonGreen,
    focusedContainerColor = DarkPurple,
    unfocusedContainerColor = DarkPurple
)

private fun importModelPackage(context: Context, privateDir: File, uri: Uri): String {
    val source = DocumentFile.fromTreeUri(context, uri) ?: error("Model folder is unavailable")
    val destination = privateDir.resolve("model-import-${System.currentTimeMillis()}")
    require(destination.mkdirs()) { "Could not create model staging directory" }
    copyDocumentTree(context, source, destination)
    val manifest = destination.resolve("nexa.manifest")
    require(manifest.isFile) { "Missing nexa.manifest; choose the folder containing the manifest and every shard" }
    val files = destination.listFiles()?.filter { it.isFile }.orEmpty()
    val shards = files.filter { it.name.endsWith(".nexa") && it.name != "nexa.manifest" }
    require(shards.isNotEmpty()) { "No .nexa model shards found" }
    require(shards.none { it.length() == 0L }) { "A model shard is empty; download it again" }
    return "Nexa package staged: ${shards.size} shards, ${files.sumOf { it.length() } / (1024 * 1024)} MB"
}

private fun importProject(context: Context, privateDir: File, uri: Uri): File {
    val source = DocumentFile.fromTreeUri(context, uri) ?: error("Folder is unavailable")
    val destination = privateDir.resolve("projects").resolve("project-${System.currentTimeMillis()}")
    require(destination.mkdirs()) { "Could not create project storage" }
    copyDocumentTree(context, source, destination)
    return destination
}

private fun copyDocumentTree(context: Context, source: DocumentFile, destination: File) {
    for (child in source.listFiles()) {
        val name = child.name ?: continue
        val target = destination.resolve(name)
        if (child.isDirectory) {
            require(target.mkdirs()) { "Could not create ${target.path}" }
            copyDocumentTree(context, child, target)
        } else if (child.isFile) {
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Could not read ${child.uri}")
        }
    }
}

private val ImportFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
