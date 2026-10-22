package com.codingagent.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        override fun onGitHubApproved(fingerprint: String) {
            vm.pushApprovedGitHub(fingerprint)
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
                        phoneLock = ui.pendingPhoneLock,
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
                        phoneLock = ui.pendingPhoneLock,
                        canSendToGitHub = ui.lastSavedSummary.isNotBlank(),
                        onApprove = { ownerApproval.beginApprovalForPendingProposal() },
                        onSendToGitHub = {
                            val fingerprint = vm.prepareGitHubPush()
                            if (fingerprint != null) ownerApproval.beginGitHubPush(fingerprint)
                        },
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
                phoneLock = ownerApproval.phoneLock,
                riskNote = ownerApproval.riskNote,
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
                            "Writing new code needs a helper on the internet. One free helper is already chosen, and I stay with that same helper for the whole job. I switch to another free helper only if the first one is busy or fails. Paste the secret password from openrouter.ai/keys. It stays on this phone and is not written into the app. Looking things up on the Research tab does not need this.",
                            color = SoftGreen,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Which free helper?", color = SoftGreen, fontSize = 12.sp)
                        ModelSettings.freeHelperChoices().forEach { choice ->
                            TextButton(
                                onClick = { vm.chooseFreeHelper(choice.modelId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (ui.draftModelName == choice.modelId) {
                                        "Selected: ${choice.plainName}. ${choice.plainDetail}"
                                    } else {
                                        "${choice.plainName}. ${choice.plainDetail}"
                                    },
                                    color = if (ui.draftModelName == choice.modelId) NeonGreen else SoftGreen,
                                    fontSize = 12.sp
                                )
                            }
                        }
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
                        Text(
                            "This password is for OpenRouter. Leave the address as https://openrouter.ai/api/v1. It stays on this phone.",
                            color = SoftGreen,
                            fontSize = 10.sp
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
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.setShowMoreHelpers(true) }, modifier = Modifier.fillMaxWidth()) {
                            Text("More free helpers, memory, and GitHub", color = FluoroOrange, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
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

        if (ui.showMoreHelpers) {
            Dialog(onDismissRequest = { vm.setShowMoreHelpers(false) }) {
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                ) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("More free helpers", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "I start with the best helper that still has room. Paste any passwords you have. Each one stays on this phone. If one helper is used up, I switch to the next and keep going. I only stop when every helper is used up, and then I start again when the first one resets.",
                            color = SoftGreen,
                            fontSize = 12.sp
                        )
                        TextButton(onClick = { vm.chooseAutomaticHelper() }) {
                            Text(
                                if (ui.draftPreferredModel.isBlank()) "Selected: use the best helper that is free right now"
                                else "Use the best helper that is free right now",
                                color = NeonGreen,
                                fontSize = 12.sp
                            )
                        }
                        helperPassword("Groq password (optional)", ui.draftGroqApiKey, vm::updateDraftGroqApiKey)
                        Text("Groq is free. About 1,000 requests a day. No card. The address is api.groq.com.", color = SoftGreen, fontSize = 10.sp)
                        helperPassword("Requesty password (optional)", ui.draftRequestyApiKey, vm::updateDraftRequestyApiKey)
                        Text("Requesty uses router.requesty.ai. The password stays on this phone.", color = SoftGreen, fontSize = 10.sp)
                        helperPassword("AI/ML API password (optional)", ui.draftAimlApiKey, vm::updateDraftAimlApiKey)
                        Text("AI/ML API uses api.aimlapi.com and the helper named stealth/union-alpha. The password stays on this phone.", color = SoftGreen, fontSize = 10.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ui.draftCloudflareAccountId,
                            onValueChange = vm::updateDraftCloudflareAccountId,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Cloudflare account id (optional)", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        helperPassword("Cloudflare token (optional)", ui.draftCloudflareApiToken, vm::updateDraftCloudflareApiToken)
                        Text(
                            "Cloudflare needs both boxes. The account id is the long code of letters and numbers on your Cloudflare account page. The token is the secret Cloudflare gives you. I put the account id into the address and send the token with the request. Both stay on this phone.",
                            color = SoftGreen,
                            fontSize = 10.sp
                        )
                        helperPassword("Google Gemini password (optional)", ui.draftGeminiApiKey, vm::updateDraftGeminiApiKey)
                        Text("Google's free Gemini key. From aistudio.google.com.", color = SoftGreen, fontSize = 10.sp)
                        helperPassword("Mistral password (optional)", ui.draftMistralApiKey, vm::updateDraftMistralApiKey)
                        Text("Mistral gives monthly credit and does not need a card.", color = SoftGreen, fontSize = 10.sp)
                        helperPassword("SambaNova password (optional)", ui.draftSambanovaApiKey, vm::updateDraftSambanovaApiKey)
                        Text("SambaNova's free plan is about 20 requests a day.", color = SoftGreen, fontSize = 10.sp)
                        helperPassword("Cerebras trial password (optional)", ui.draftCerebrasApiKey, vm::updateDraftCerebrasApiKey)
                        Text("Cerebras is a short trial and needs a card. Leave this blank if you do not have one.", color = SoftGreen, fontSize = 10.sp)
                        Text("GitHub's free model service closed in July 2026, so there is no box for it.", color = SoftGreen, fontSize = 10.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("What I remember", color = NeonGreen, fontSize = 14.sp)
                        TextButton(onClick = { vm.updateDraftMemoryRetention(ModelSettings.MEMORY_KEEP_CAPPED) }) {
                            Text(
                                if (ui.draftMemoryRetention == ModelSettings.MEMORY_KEEP_CAPPED) "Selected: clean up old memories"
                                else "Clean up old memories",
                                color = SoftGreen,
                                fontSize = 12.sp
                            )
                        }
                        Text("I keep what you tell me, and I drop old unimportant notes after about six months. Strong rules stay.", color = SoftGreen, fontSize = 10.sp)
                        TextButton(onClick = { vm.updateDraftMemoryRetention(ModelSettings.MEMORY_KEEP_FOREVER) }) {
                            Text(
                                if (ui.draftMemoryRetention == ModelSettings.MEMORY_KEEP_FOREVER) "Selected: keep everything I remember"
                                else "Keep everything I remember",
                                color = SoftGreen,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Send my own changes to GitHub", color = NeonGreen, fontSize = 14.sp)
                        OutlinedTextField(
                            value = ui.draftGithubRepo,
                            onValueChange = vm::updateDraftGithubRepo,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("GitHub project (owner/name)", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        OutlinedTextField(
                            value = ui.draftGithubBranch,
                            onValueChange = vm::updateDraftGithubBranch,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Branch", color = SoftGreen) },
                            singleLine = true,
                            colors = fieldColors()
                        )
                        helperPassword("GitHub token (optional)", ui.draftGithubToken, vm::updateDraftGithubToken)
                        Text("The token stays on this phone. Sending still needs the phone lock.", color = SoftGreen, fontSize = 10.sp)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.setShowMoreHelpers(false) }) { Text("Close", color = SoftGreen) }
                            TextButton(onClick = {
                                vm.saveDraftModelSettings()
                                vm.setShowMoreHelpers(false)
                            }) { Text("Save", color = NeonGreen, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun helperPassword(label: String, value: String, onChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = SoftGreen) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = fieldColors()
    )
}
