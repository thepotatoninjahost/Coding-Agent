package com.codingagent.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelSettings
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.PendingChangeProposal
import kotlinx.coroutines.Job

/**
 * ONE JOB: Host activity and system entry for the coding workbench.
 */
class MainActivity : FragmentActivity() {

    private var folderPickerCallback: ((Uri?) -> Unit)? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        folderPickerCallback?.invoke(uri)
        folderPickerCallback = null
    }

    fun launchFolderPicker(onResult: (Uri?) -> Unit) {
        folderPickerCallback = onResult
        folderPickerLauncher.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodingAgentApp() }
    }
}

/**
 * ONE JOB: Top-level workbench UI bound to [WorkbenchViewModel].
 *
 * All durable / agent / approval state lives in the ViewModel so configuration
 * changes and process death do not drop in-flight work.
 */
@Composable
private fun CodingAgentApp(
    vm: WorkbenchViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsStateWithLifecycle()

    val ownerApproval = remember(vm.store) {
        OwnerApprovalController(
            context = context,
            store = vm.store,
            scope = scope,
            coordinatorProvider = { vm.mutationCoordinator() },
            pendingProposalIdProvider = { vm.ui.value.pendingProposalId },
            activityProvider = { context as FragmentActivity }
        )
    }
    ownerApproval.host = object : OwnerApprovalHost {
        override fun isBusy(): Boolean = vm.isBusy()
        override fun setActiveJob(job: Job?) {
            vm.attachApprovalJob(job)
        }
        override fun setStatus(newStatus: AgentStatus, newDetail: String) {
            vm.setStatus(newStatus, newDetail)
        }
        override fun clearPendingProposal(newDetail: String) {
            vm.clearPendingApproval()
            vm.setStatus(AgentStatus.STOPPED, newDetail)
        }
        override fun onAwaitingSecond(proposal: PendingChangeProposal) {
            vm.setStatus(
                AgentStatus.APPROVAL,
                "Yes ${proposal.approvalCount} of 2 is saved. Nothing is written until you say yes a second time."
            )
        }
        override fun onRepairRequired(proposal: PendingChangeProposal, newDetail: String) {
            vm.setStatus(AgentStatus.APPROVAL, newDetail)
        }
        override fun onApplied(result: MutationApprovalResult.Applied) {
            vm.onChangeApplied(result)
        }
        override fun onRejected(reason: String) {
            vm.clearPendingApproval()
            vm.setStatus(AgentStatus.STOPPED, reason)
        }
        override fun onFailed(message: String) {
            vm.setStatus(AgentStatus.FAILED, message)
        }
        override fun onPassphrasesEnrolled(newDetail: String) {
            vm.setStatus(AgentStatus.READY, newDetail)
        }
    }
    DisposableEffect(ownerApproval) {
        onDispose { ownerApproval.disposeRecognizer() }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        ownerApproval.onMicrophonePermissionResult(granted)
    }
    LaunchedEffect(ownerApproval.pendingMicRequest) {
        if (ownerApproval.pendingMicRequest) {
            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Canvas) {
        Scaffold(
            containerColor = Canvas,
            topBar = {
                CompactStatusBar(
                    mounted = ui.workspace != null,
                    status = ui.status,
                    detail = ui.detail,
                    modelStatus = ui.modelStatus,
                    modelProgress = ui.modelProgress,
                    onImport = { (context as? MainActivity)?.launchFolderPicker(vm::handleFolderPickerResult) },
                    onNewProject = { vm.startNewProject() },
                    onModelImport = { vm.setShowModelSettings(true) },
                    onStop = { vm.stopAgent() }
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    SurfaceTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = ui.tab == item,
                            onClick = { vm.setTab(item) },
                            icon = { Text(item.label.take(1), color = if (ui.tab == item) NeonGreen else SoftGreen) },
                            label = { Text(item.label, color = if (ui.tab == item) NeonGreen else SoftGreen, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (ui.tab) {
                    SurfaceTab.CHAT -> ChatSurface(
                        messages = ui.chatMessages,
                        input = ui.chatInput,
                        onInput = vm::setChatInput,
                        onSend = {
                            val text = ui.chatInput.trim()
                            if (text.isNotEmpty()) {
                                vm.setChatInput("")
                                vm.sendChat(text)
                            }
                        },
                        busy = vm.isBusy(),
                        onStop = vm::stopAgent,
                        pendingApproval = ui.pendingApproval,
                        approvalCount = ui.approvalCount,
                        reason = ui.pendingReason,
                        onApprove = { ownerApproval.beginApprovalForPendingProposal() }
                    )
                    SurfaceTab.FILES -> FilesSurface(
                        files = ui.fileList,
                        query = ui.projectQuery,
                        onQuery = vm::setProjectQuery,
                        path = ui.editorPath,
                        onOpen = vm::openEditorFile,
                        content = ui.editorContent,
                        onContent = vm::setEditorContent,
                        document = ui.editorDocument,
                        tools = vm.tools(),
                        mutationCoordinator = vm.mutationCoordinator(),
                        onSave = { vm.saveEditor() }
                    )
                    SurfaceTab.REVIEW -> ReviewSurface(
                        pending = ui.pendingApproval,
                        approvals = ui.approvalCount,
                        reason = ui.pendingReason,
                        onApprove = { ownerApproval.beginApprovalForPendingProposal() },
                        onReject = {
                            ui.pendingProposalId?.let { vm.mutationCoordinator()?.reject(it) }
                            vm.clearPendingApproval()
                            vm.setStatus(AgentStatus.STOPPED, "You said no. Nothing was saved.")
                        }
                    )
                    SurfaceTab.TERMINAL -> TerminalSurface(
                        command = ui.terminalCommand,
                        onCommand = vm::setTerminalCommand,
                        history = ui.terminalHistory,
                        liveOutput = ui.terminalLiveOutput,
                        cwd = ui.workspace?.projectRoot()?.absolutePath ?: "(no project)",
                        shell = "sh",
                        timeoutSeconds = vm.tools()?.terminalTimeoutSeconds() ?: 90L,
                        running = vm.tools()?.isTerminalBusy() == true,
                        enabled = vm.tools() != null,
                        onRun = { command -> vm.runTerminal(command) },
                        onStop = { vm.stopAgent() },
                        onClear = { vm.clearTerminal() }
                    )
                    SurfaceTab.RESEARCH -> ResearchSurface(
                        query = ui.researchQuery,
                        onQuery = vm::setResearchQuery,
                        hits = ui.researchHits,
                        error = ui.researchError,
                        state = ui.researchState,
                        onSearch = { query -> vm.searchResearch(query) }
                    )
                }
            }
        }

        if (ownerApproval.showDialog) {
            OwnerApprovalDialog(
                setupMode = ownerApproval.setupMode,
                typedPhrase = ownerApproval.typedPhrase,
                typedPhraseConfirm = ownerApproval.typedPhraseConfirm,
                spokenPhrase = ownerApproval.spokenPhrase,
                speechChallenge = ownerApproval.speechChallenge,
                speechStatus = ownerApproval.speechStatus,
                speechVerified = ownerApproval.speechVerified,
                error = ownerApproval.error,
                onTypedChange = ownerApproval::onTypedChange,
                onTypedConfirmChange = ownerApproval::onTypedConfirmChange,
                onSpokenChange = ownerApproval::onSpokenChange,
                onSpeak = ownerApproval::requestSpeechCapture,
                onSubmit = ownerApproval::submitDialog,
                onDismiss = ownerApproval::clearDialog
            )
        }

        if (ui.showModelSettings) {
            Dialog(onDismissRequest = { vm.setShowModelSettings(false) }) {
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Connect a code helper", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Writing new code needs a helper on the internet. Paste the web address, the helper's name, and the secret password that company gave you. They stay on this phone.\n\nA free starting point is Groq. Web address: https://api.groq.com/openai/v1. Helper name: llama-3.3-70b-versatile. Secret password: create one at console.groq.com. You can use another company instead. Looking things up on the Research tab does not need this.",
                            color = SoftGreen,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = ui.draftBaseUrl,
                            onValueChange = vm::updateDraftBaseUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Web address", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ui.draftModelName,
                            onValueChange = vm::updateDraftModelName,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Helper name", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ui.draftApiKey,
                            onValueChange = vm::updateDraftApiKey,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Secret password", color = SoftGreen) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = fieldColors()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ui.draftSystemPrompt,
                            onValueChange = vm::updateDraftSystemPrompt,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Extra rules for the helper (optional)", color = SoftGreen) },
                            minLines = 3,
                            maxLines = 8,
                            colors = fieldColors()
                        )
                        Text(
                            "These are added to the built-in rules. The helper still has to ask you before it changes your files.",
                            color = SoftGreen,
                            fontSize = 10.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ui.draftExtraHeaders,
                            onValueChange = vm::updateDraftExtraHeaders,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Extra connection lines (optional)", color = SoftGreen) },
                            minLines = 2,
                            maxLines = 4,
                            colors = fieldColors()
                        )
                        ui.probeMessage?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = SoftGreen, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            TextButton(onClick = { vm.probeModelConnection() }) {
                                Text("Try connection", color = FluoroOrange)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.setShowModelSettings(false) }) {
                                Text("Cancel", color = SoftGreen)
                            }
                            TextButton(onClick = { vm.saveDraftModelSettings() }) {
                                Text("Save", color = NeonGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
