package com.codingagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.codingagent.core.AgentAction
import com.codingagent.core.AgentActionCategory
import com.codingagent.core.AgentConstitution
import com.codingagent.core.AgentKnowledge
import com.codingagent.core.AgentJournal
import com.codingagent.core.AgentTools
import com.codingagent.core.ChatMessage
import com.codingagent.core.ChatRole
import com.codingagent.core.ChatWorkspace
import com.codingagent.core.CodingAgentRuntime
import com.codingagent.core.DuckDuckGoResearchProvider
import com.codingagent.core.DurableDeepResearchProvider
import com.codingagent.core.DeepResearchProgress
import com.codingagent.core.ResearchDisplayState
import com.codingagent.core.ResearchModeDetector
import com.codingagent.core.EditorDocument
import com.codingagent.core.KnowledgeBase
import com.codingagent.core.LocalStore
import com.codingagent.core.ModelDownloadProgress
import com.codingagent.core.NexaModelProvisioner
import com.codingagent.core.NexaLocalModelGateway
import com.codingagent.core.ProjectWorkspace
import com.codingagent.core.ResearchHit
import com.codingagent.core.TerminalEntry
import com.codingagent.core.MutationApprovalResult
import com.codingagent.core.MutationCoordinator
import com.codingagent.core.AutonomousAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.codingagent.core.PendingChangeProposal

private val Ink = Color(0xFFE8EDF2)
private val Muted = Color(0xFF94A0AE)
private val Canvas = Color(0xFF0A0D11)
private val Panel = Color(0xFF131922)
private val PanelRaised = Color(0xFF1A2330)
private val Line = Color(0xFF2B3848)
private val Accent = Color(0xFF8FE36B)
private val Blue = Color(0xFF8CB9FF)
private val Amber = Color(0xFFFFC857)
private val Danger = Color(0xFFFF756D)

private enum class SurfaceTab(val label: String) { CHAT("Chat"), FILES("Files"), REVIEW("Review"), TERMINAL("Terminal"), RESEARCH("Research") }
private enum class AgentStatus(val label: String, val color: Color) { READY("Ready", Accent), RESEARCHING("Researching", Blue), PLANNING("Planning", Blue), EDITING("Editing", Amber), APPROVAL("Waiting for approval", Amber), RUNNING("Verifying", Blue), STOPPED("Stopped", Danger) }

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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
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
                        modelLoadError = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                        modelStatus = "Model load failed: ${modelLoadError.orEmpty().take(300)}"
                    }
            }.onFailure {
                modelLoadError = it.message.orEmpty().ifBlank { it.javaClass.simpleName }
                modelStatus = "Model setup failed: ${modelLoadError.orEmpty().take(300)}"
            }
        }
    }

    val tools = remember(workspace) { workspace?.let(::AgentTools) }
    val agent = remember(workspace, localModel) {
        workspace?.let { current ->
            val runtime = CodingAgentRuntime(current, object : AgentKnowledge {
                override fun search(query: String, limit: Int) = knowledgeBase.search(query, limit)
            }, AgentJournal(current.projectRoot()), research = DuckDuckGoResearchProvider(), deepResearch = DurableDeepResearchProvider(current.projectRoot().resolve(".coding-agent/research")), modelGateway = localModel, mutationCoordinator = mutationCoordinator ?: MutationCoordinator(current))
            localModel?.let { gateway ->
                AutonomousAgent(current.projectRoot(), runtime, object : AgentKnowledge {
                    override fun search(query: String, limit: Int) = knowledgeBase.search(query, limit)
                }, gateway, research = DurableDeepResearchProvider(current.projectRoot().resolve(".coding-agent/research")), mutations = mutationCoordinator ?: MutationCoordinator(current))
            }
        }
    }
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
            status = AgentStatus.RESEARCHING
            detail = "Starting: research queue → full-source reads → project context"
            val outcome = withContext(Dispatchers.IO) { runCatching { currentChat.send(request) } }
            outcome.onSuccess {
                chatMessages = currentChat.history()
                val needsInput = it.result is com.codingagent.core.AgentRuntimeResult.NeedsInput
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
                    else -> AgentStatus.READY
                }
                detail = if (it.result is com.codingagent.core.AgentRuntimeResult.Failed) "Agent failed: ${it.response.content.lineSequence().firstOrNull().orEmpty().take(140)}" else it.response.content.lineSequence().firstOrNull().orEmpty().take(120)
            }                .onFailure {
                val message = it.message.orEmpty().ifBlank { it.javaClass.simpleName }
                store.recordChatMessage(ChatMessage(role = ChatRole.SYSTEM, content = "Request failed: $message"))
                chatMessages = currentChat.history()
                status = AgentStatus.STOPPED
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
            topBar = { StatusBar(status, detail, workspace != null, modelStatus, modelProgress, onImport = { folderPicker.launch(null) }, onModelImport = { modelPicker.launch(null) }, onStop = ::stopAgent) },
            bottomBar = {
                androidx.compose.material3.NavigationBar(containerColor = Panel, contentColor = Ink) {
                    SurfaceTab.entries.forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Text(item.label.take(1), fontWeight = FontWeight.Bold) }, label = { Text(item.label, fontSize = 10.sp) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when (tab) {
                    SurfaceTab.CHAT -> ChatSurface(chatMessages, chatInput, { chatInput = it }, ::send, activeJob != null, ::stopAgent, pendingApproval, approvalCount, pendingReason, onApprove = {
                        val id = pendingProposalId ?: return@ChatSurface
                        val coordinator = mutationCoordinator ?: return@ChatSurface
                        when (val result = coordinator.approve(id, ownerVerified = true, ownerLabel = "owner")) {
                            is MutationApprovalResult.AwaitingSecond -> { approvalCount = result.proposal.approvalCount; detail = "Confirmation ${approvalCount}/2 recorded; transaction remains unapplied" }
                            is MutationApprovalResult.Applied -> { approvalCount = result.proposal.approvalCount; pendingApproval = false; pendingProposal = null; pendingProposalId = null; status = AgentStatus.RUNNING; detail = "Approved transaction applied; verification required" }
                            is MutationApprovalResult.Rejected -> { status = AgentStatus.STOPPED; detail = result.reason }
                        }
                    })
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
                activeJob = scope.launch(Dispatchers.IO) {
                    status = AgentStatus.RESEARCHING
                    detail = "Reading distinct full sources"
                    val provider = DurableDeepResearchProvider(workspace?.projectRoot()?.resolve(".coding-agent/research") ?: return@launch)
                    val result = runCatching { provider.deepResearch(query, 50, ResearchModeDetector.detect(query)) { progress -> researchState = progress.toDisplayState() } }
                    result.onSuccess { session ->
                        researchHits = session.sources.map { source -> com.codingagent.core.ResearchHit(source.title, source.url, source.content.take(600)) }
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
private fun StatusBar(status: AgentStatus, detail: String, mounted: Boolean, modelStatus: String, modelProgress: ModelDownloadProgress?, onImport: () -> Unit, onModelImport: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CODING AGENT", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(if (mounted) "Galaxy S25 // project mounted" else "Galaxy S25 // no project", color = Muted, fontSize = 12.sp)
                Text("Model: $modelStatus", color = Muted, fontSize = 11.sp, maxLines = 1)
            }
            if (status != AgentStatus.READY && status != AgentStatus.STOPPED) TextButton(onClick = onStop) { Text("Stop", color = Danger) }
            TextButton(onClick = onModelImport) { Text("Model", color = Blue, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onImport) { Text(if (mounted) "Switch" else "Import", color = Accent, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(status.color, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Text(status.label, color = status.color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(detail, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        if (status == AgentStatus.RESEARCHING || status == AgentStatus.RUNNING || modelProgress?.phase == "downloading") {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { ((modelProgress?.percent ?: 0) / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = status.color, trackColor = Line)
        }
    }
}

@Composable
private fun ChatSurface(messages: List<ChatMessage>, input: String, onInput: (String) -> Unit, onSend: () -> Unit, busy: Boolean, onStop: () -> Unit, pendingApproval: Boolean, approvalCount: Int, reason: String, onApprove: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (messages.isEmpty()) item { EmptyState("Start with a real request", "The agent researches first, inspects the project, then proposes safe changes.") }
            items(messages, key = { it.id }) { ChatBubble(it) }
        }
        if (pendingApproval) ApprovalCard(approvalCount, reason, onApprove)
        if (busy) Text("Agent is working. Send remains available; queued follow-ups: ${if (input.isBlank()) 0 else 1}.", color = Blue, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().background(Panel).padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, onInput, Modifier.weight(1f), placeholder = { Text("Ask for a fix, feature, refactor, or experiment…", color = Muted) }, minLines = 2, maxLines = 5, colors = fieldColors())
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSend, enabled = input.isNotBlank(), modifier = Modifier.height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)) { Text("Send", fontWeight = FontWeight.Bold) }
            if (busy) TextButton(onClick = onStop) { Text("Stop", color = Danger) }
        }
    }
}

@Composable
private fun ApprovalCard(approvalCount: Int, reason: String, onApprove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF322B19)), border = androidx.compose.foundation.BorderStroke(1.dp, Amber)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CODE CHANGE REVIEW", color = Amber, fontWeight = FontWeight.Bold)
            Text(reason, color = Ink, fontSize = 13.sp)
            Text("Two explicit approvals are required before a code transaction can proceed.", color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${approvalCount}/2", color = Muted, modifier = Modifier.weight(1f))
                Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Canvas)) { Text(if (approvalCount == 0) "Confirm" else "Confirm again") }
            }
        }
    }
}

@Composable
private fun FilesSurface(files: List<String>, query: String, onQuery: (String) -> Unit, path: String, onPath: (String) -> Unit, content: String, onContent: (String) -> Unit, document: EditorDocument?, tools: AgentTools?, mutationCoordinator: MutationCoordinator?, onStatus: (Pair<AgentStatus, String>) -> Unit) {
    var showEditor by remember { mutableStateOf(false) }
    val filtered = files.filter { it.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PROJECT FILES", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), placeholder = { Text("Filter files", color = Muted) }, singleLine = true, colors = fieldColors())
        if (showEditor) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(path, color = Blue, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace); TextButton(onClick = { showEditor = false }) { Text("Close", color = Muted) } }
            CodeEditor(content, onContent, path)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onContent(document?.content.orEmpty()) }, colors = ButtonDefaults.buttonColors(containerColor = PanelRaised)) { Text("Revert", color = Ink) }
                Button(onClick = { runCatching {
                    val coordinator = mutationCoordinator ?: error("Project mutation coordinator is unavailable")
                    val proposal = tools?.proposeSave(path, content, coordinator) ?: error("Project tools are unavailable")
                    onStatus(AgentStatus.APPROVAL to "Save proposed; confirm twice in Review (${proposal.id.take(8)})")
                }.onFailure { onStatus(AgentStatus.STOPPED to (it.message ?: "Save proposal failed")) } }, enabled = document != null && document.content != content && mutationCoordinator != null, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)) { Text("Propose save") }
            }
        } else {
            if (filtered.isEmpty()) EmptyState("No files", "Import a project or change the filter.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it }) { file ->
                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).clickable { runCatching { val loaded = tools?.read(file) ?: return@runCatching; onPath(file); onContent(loaded.content); showEditor = true; onStatus(AgentStatus.READY to "Opened $file") }.onFailure { onStatus(AgentStatus.STOPPED to "Open failed") } }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("{ }", color = Blue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Text(file, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeEditor(content: String, onContent: (String) -> Unit, path: String) {
    val highlighted = remember(content, path) { highlightCode(content) }
    Box(Modifier.fillMaxWidth().height(360.dp).background(Color(0xFF0F1319), RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp)) {
        BasicTextField(value = content, onValueChange = onContent, modifier = Modifier.fillMaxSize(), textStyle = TextStyle(color = Color.Transparent, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), decorationBox = { inner ->
            Box(Modifier.fillMaxSize()) {
                Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
                inner()
            }
        })
    }
}

private fun highlightCode(content: String): AnnotatedString = buildAnnotatedString {
    val keywords = Regex("\\b(fun|class|object|interface|val|var|if|else|when|return|import|package|public|private|suspend|async|await|def|from|const|let|function|export|type)\\b")
    var cursor = 0
    keywords.findAll(content).forEach { match ->
        append(content.substring(cursor, match.range.first))
        withStyle(SpanStyle(color = Blue, fontWeight = FontWeight.Bold)) { append(match.value) }
        cursor = match.range.last + 1
    }
    append(content.substring(cursor))
}

@Composable
private fun ReviewSurface(pending: Boolean, approvals: Int, reason: String, onApprove: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CHANGE REVIEW", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (!pending) {
            EmptyState("No pending changes", "Agent proposals appear here before any file transaction.")
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), border = androidx.compose.foundation.BorderStroke(1.dp, Amber)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pending transactional proposal", color = Amber, fontWeight = FontWeight.Bold)
                    Text(reason, color = Ink)
                    Text("Review changed files before confirming. Transactional writes remain checksum-guarded.", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReject, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF442126))) { Text("Reject", color = Ink) }
                        Button(onClick = onApprove, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Canvas)) { Text("Confirm ${approvals + 1}/2") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSurface(command: String, onCommand: (String) -> Unit, history: List<TerminalEntry>, enabled: Boolean, onRun: (String) -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("TERMINAL", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = onStop) { Text("Stop", color = Danger) } }
        OutlinedTextField(command, onCommand, Modifier.fillMaxWidth(), placeholder = { Text("./gradlew testDebugUnitTest", color = Muted) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), colors = fieldColors())
        Button(onClick = { onRun(command) }, enabled = enabled && command.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)) { Text("Run") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { "${it.command}-${it.stdout.hashCode()}" }) { entry ->
                Text("$ ${entry.command}\n${entry.stdout}${entry.stderr}\nexit=${entry.exitCode}${if (entry.timedOut) " timeout" else ""}", Modifier.fillMaxWidth().background(Color(0xFF0F1319), RoundedCornerShape(10.dp)).padding(12.dp), color = if (entry.exitCode == 0) Accent else Danger, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

private fun DeepResearchProgress.toDisplayState() = ResearchDisplayState(stage, completed, total, successful, failed, canSend = false)

@Composable
private fun ResearchSurface(query: String, onQuery: (String) -> Unit, hits: List<ResearchHit>, error: String?, state: ResearchDisplayState, onSearch: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("WEB RESEARCH", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Research is not complete until distinct full sources are read and learned.", color = Muted, fontSize = 13.sp)
        Text("${state.phase.uppercase()}  ${state.completed}/${state.total} sources  •  full=${state.fullSources}  failed=${state.failedSources}  lanes=${state.laneCount}  words=${state.wordCount}  code=${state.codeExamples}", color = if (state.phase == "blocked") Danger else Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(if (state.phase == "blocked") "No coding context was sent to the model. Fix the network/source problem and run research again." else "The model is not called until the full source target is reached.", color = Muted, fontSize = 11.sp)
        Text("Sources are deduplicated by canonical URL; each successful source is fetched, extracted, chunked, and persisted before the model sees the brief.", color = Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), placeholder = { Text("Kotlin, Android, RFC, GitHub…", color = Muted) }, singleLine = true, colors = fieldColors())
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSearch(query) }, enabled = query.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Canvas)) { Text("Search") }
        }
        error?.let { Text(it, color = Danger, fontSize = 12.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits, key = { it.url }) { hit ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(12.dp)) { Text(hit.title, color = Ink, fontWeight = FontWeight.Bold); Text(hit.excerpt, color = Muted, fontSize = 13.sp); Text(hit.url, color = Blue, fontSize = 11.sp, maxLines = 2) } }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val user = message.role == ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.88f).background(if (user) Color(0xFF263B29) else Panel, RoundedCornerShape(14.dp)).padding(12.dp)) {
            Text(if (user) "YOU" else if (message.role == ChatRole.AGENT) "AGENT" else "SYSTEM", color = if (user) Accent else Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(message.content, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp); Spacer(Modifier.height(6.dp)); Text(body, color = Muted, fontSize = 13.sp) }
}

@Composable
private fun fieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Accent, unfocusedBorderColor = Line, cursorColor = Accent)

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
            context.contentResolver.openInputStream(child.uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } } ?: error("Could not read ${child.uri}")
        }
    }
}

private val ImportFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
