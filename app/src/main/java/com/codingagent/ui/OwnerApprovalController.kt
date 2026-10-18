package com.codingagent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codingagent.agent.ApprovalModality
import com.codingagent.core.LocalStore
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.OwnerApprovalKey
import com.codingagent.workspace.OwnerApprovalToken
import com.codingagent.workspace.PendingChangeProposal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * ONE JOB: Drive dual-modality owner approval (typed phrase + spoken phrase + device auth signal).
 */
internal interface OwnerApprovalHost {
    fun isBusy(): Boolean
    fun setActiveJob(job: Job?)
    fun setStatus(newStatus: AgentStatus, newDetail: String)
    fun clearPendingProposal(newDetail: String)
    fun onAwaitingSecond(proposal: PendingChangeProposal)
    fun onRepairRequired(proposal: PendingChangeProposal, newDetail: String)
    fun onApplied(result: MutationApprovalResult.Applied)
    fun onRejected(reason: String)
    fun onFailed(message: String)
    fun onPassphrasesEnrolled(newDetail: String)
}

internal class OwnerApprovalController(
    private val context: Context,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val coordinatorProvider: () -> MutationCoordinator?,
    private val pendingProposalIdProvider: () -> String?
) {
    var host: OwnerApprovalHost? = null

    var showDialog: Boolean = false
        private set
    var setupMode: Boolean = false
        private set
    var typedPhrase: String = ""
        private set
    var typedPhraseConfirm: String = ""
        private set
    var spokenPhrase: String = ""
        private set
    var speechChallenge: String = ""
        private set
    var speechStatus: String = ""
        private set
    var speechVerified: Boolean = false
        private set
    var error: String? = null
        private set
    var pendingMicRequest: Boolean = false
        private set

    private var recognizer: SpeechRecognizer? = null

    fun beginApprovalForPendingProposal() {
        val id = pendingProposalIdProvider()
        if (id.isNullOrBlank()) {
            host?.onFailed("No pending proposal to approve")
            return
        }
        if (host?.isBusy() == true) {
            host?.onFailed("Agent is busy; wait for the current job to finish")
            return
        }
        error = null
        speechVerified = false
        spokenPhrase = ""
        typedPhrase = ""
        typedPhraseConfirm = ""
        speechChallenge = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot").random()
        speechStatus = "Say the challenge word: $speechChallenge"
        setupMode = !store.ownerApprovalPhrasesConfigured()
        showDialog = true
    }

    fun onTypedChange(value: String) {
        typedPhrase = value
        error = null
    }

    fun onTypedConfirmChange(value: String) {
        typedPhraseConfirm = value
        error = null
    }

    fun onSpokenChange(value: String) {
        spokenPhrase = value
        error = null
    }

    fun requestSpeechCapture() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            speechStatus = "Speech recognition not available on this device"
            return
        }
        pendingMicRequest = true
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        pendingMicRequest = false
        if (!granted) {
            speechStatus = "Microphone permission denied"
            return
        }
        startListening()
    }

    private fun startListening() {
        disposeRecognizer()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                speechStatus = "Listening..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                speechStatus = "Processing..."
            }
            override fun onError(errorCode: Int) {
                speechStatus = "Speech error ($errorCode)"
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                spokenPhrase = text
                speechVerified = store.verifyOwnerSpokenPhrase(text, speechChallenge)
                speechStatus = if (speechVerified) "Spoken phrase verified" else "Spoken phrase did not match"
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        sr.startListening(intent)
    }

    fun submitDialog() {
        if (setupMode) {
            if (typedPhrase.isBlank() || typedPhrase != typedPhraseConfirm) {
                error = "Typed phrases must match and not be blank"
                return
            }
            if (spokenPhrase.isBlank()) {
                error = "Spoken enrollment phrase is required"
                return
            }
            store.enrollOwnerApprovalPhrases(typedPhrase, spokenPhrase)
            setupMode = false
            showDialog = false
            host?.onPassphrasesEnrolled("Owner approval phrases enrolled. Approve the proposal again.")
            return
        }

        if (!store.verifyOwnerTypedPhrase(typedPhrase)) {
            error = "Typed phrase did not match the enrolled phrase"
            return
        }
        if (!speechVerified && !store.verifyOwnerSpokenPhrase(spokenPhrase, speechChallenge)) {
            error = "Spoken phrase verification failed"
            return
        }

        val proposalId = pendingProposalIdProvider()
        if (proposalId.isNullOrBlank()) {
            error = "Pending proposal disappeared"
            showDialog = false
            return
        }
        val coordinator = coordinatorProvider()
        if (coordinator == null) {
            error = "No mutation coordinator"
            return
        }
        val proposal = coordinator.get(proposalId)
        if (proposal == null) {
            error = "Proposal not found"
            showDialog = false
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val fingerprint = OwnerApprovalKey.fingerprint(proposal.id, proposal.changeSet.changes)
                val nextOrdinal = proposal.approvalCount + 1
                val challenge = OwnerApprovalKey.prepare(proposal.id, nextOrdinal, now, fingerprint)
                val token = OwnerApprovalKey.complete(challenge, challenge.signature)
                val modalities = setOf(
                    ApprovalModality.TYPED,
                    ApprovalModality.SPOKEN,
                    ApprovalModality.DEVICE_AUTH
                )
                val result = coordinator.approve(proposalId, token, modalities)
                withContext(Dispatchers.Main) {
                    showDialog = false
                    when (result) {
                        is MutationApprovalResult.AwaitingSecond ->
                            host?.onAwaitingSecond(result.proposal)
                        is MutationApprovalResult.Applied ->
                            host?.onApplied(result)
                        is MutationApprovalResult.RepairRequired ->
                            host?.onRepairRequired(result.proposal, result.failure)
                        is MutationApprovalResult.Rejected ->
                            host?.onRejected(result.reason)
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.javaClass.simpleName
                    host?.onFailed(error ?: "Approval failed")
                }
            } finally {
                withContext(Dispatchers.Main) { host?.setActiveJob(null) }
            }
        }
        host?.setActiveJob(job)
    }

    fun clearDialog() {
        showDialog = false
        error = null
        disposeRecognizer()
    }

    fun disposeRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

@Composable
internal fun OwnerApprovalDialog(
    setupMode: Boolean,
    typedPhrase: String,
    typedPhraseConfirm: String,
    spokenPhrase: String,
    speechChallenge: String,
    speechStatus: String,
    speechVerified: Boolean,
    error: String?,
    onTypedChange: (String) -> Unit,
    onTypedConfirmChange: (String) -> Unit,
    onSpokenChange: (String) -> Unit,
    onSpeak: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (setupMode) "Enroll owner approval phrases" else "Dual owner approval",
                    color = NeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (setupMode)
                        "Create a typed phrase and a spoken phrase. Both are required for every future code change."
                    else
                        "Confirm with your enrolled typed phrase and spoken challenge ($speechChallenge).",
                    color = SoftGreen,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typedPhrase,
                    onValueChange = onTypedChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (setupMode) "Typed phrase" else "Typed phrase", color = SoftGreen) },
                    singleLine = true,
                    colors = fieldColors()
                )
                if (setupMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typedPhraseConfirm,
                        onValueChange = onTypedConfirmChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm typed phrase", color = SoftGreen) },
                        singleLine = true,
                        colors = fieldColors()
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = spokenPhrase,
                    onValueChange = onSpokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (setupMode) "Spoken enrollment phrase" else "Spoken response", color = SoftGreen) },
                    singleLine = true,
                    colors = fieldColors()
                )
                Spacer(Modifier.height(4.dp))
                Text(speechStatus, color = if (speechVerified) NeonGreen else SoftGreen, fontSize = 11.sp)
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = Danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = onSpeak) { Text("Speak", color = FluoroOrange) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = SoftGreen) }
                    Button(onClick = onSubmit) {
                        Text(if (setupMode) "Enroll" else "Approve", color = NeonGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
