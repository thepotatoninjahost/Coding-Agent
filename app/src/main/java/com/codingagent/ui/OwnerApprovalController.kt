package com.codingagent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.codingagent.agent.ApprovalModality
import com.codingagent.agent.RiskyChange
import com.codingagent.core.LocalStore
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.OwnerApprovalKey
import com.codingagent.workspace.PendingChangeProposal
import java.security.SecureRandom
import java.security.Signature
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
    fun onGitHubApproved(fingerprint: String)
    fun onRejected(reason: String)
    fun onFailed(message: String)
    fun onPassphrasesEnrolled(newDetail: String)
}

internal class OwnerApprovalController(
    private val context: Context,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val coordinatorProvider: () -> MutationCoordinator?,
    private val pendingProposalIdProvider: () -> String?,
    private val activityProvider: () -> FragmentActivity
) {
    var host: OwnerApprovalHost? = null

    var showDialog by mutableStateOf(false)
        private set
    var setupMode by mutableStateOf(false)
        private set
    var typedPhrase by mutableStateOf("")
        private set
    var typedPhraseConfirm by mutableStateOf("")
        private set
    var spokenPhrase by mutableStateOf("")
        private set
    var speechChallenge by mutableStateOf("")
        private set
    var speechStatus by mutableStateOf("")
        private set
    var speechVerified by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingMicRequest by mutableStateOf(false)
        private set
    var phoneLock by mutableStateOf(false)
        private set
    var riskNote by mutableStateOf("")
        private set

    private var recognizer: SpeechRecognizer? = null
    private var githubFingerprint: String? = null
    private var biometricPrompt: BiometricPrompt? = null

    fun beginApprovalForPendingProposal() {
        val id = pendingProposalIdProvider()
        if (id.isNullOrBlank()) {
            host?.onFailed("Nothing is waiting for you to approve.")
            return
        }
        if (host?.isBusy() == true) {
            host?.onFailed("Still working. Wait until it finishes, then say yes.")
            return
        }
        githubFingerprint = null
        val proposal = coordinatorProvider()?.get(id)
        val kinds = if (proposal == null) emptySet() else RiskyChange.kinds(proposal.changeSet.changes, proposal.request)
        phoneLock = kinds.isNotEmpty()
        riskNote = RiskyChange.plainSummary(kinds)
        if (kinds.isEmpty() && proposal != null) {
            confirmOrdinary(proposal)
            return
        }
        error = null
        speechVerified = false
        spokenPhrase = ""
        typedPhrase = ""
        typedPhraseConfirm = ""
        setupMode = !store.ownerApprovalPhrasesConfigured()
        speechChallenge = if (setupMode) "" else freshSpeechChallenge()
        speechStatus = if (setupMode) {
            "Say the words you want to save, or type them in the box below."
        } else {
            "Say the words you saved, then these words: $speechChallenge"
        }
        showDialog = true
    }

    private fun confirmOrdinary(proposal: PendingChangeProposal) {
        val activity = activityProvider()
        val job = scope.launch(Dispatchers.Main) {
            try {
                val now = System.currentTimeMillis()
                val fingerprint = OwnerApprovalKey.fingerprint(proposal.id, proposal.changeSet.changes)
                val nextOrdinal = proposal.approvalCount + 1
                var usedLock = true
                val token = try {
                    val signingChallenge = withContext(Dispatchers.IO) {
                        OwnerApprovalKey.prepare(proposal.id, nextOrdinal, now, fingerprint)
                    }
                    val unlocked = awaitDeviceSignature(
                        activity,
                        signingChallenge.signature,
                        "Confirm it is you",
                        "Use your fingerprint, face, or screen lock. One yes saves this file."
                    )
                    withContext(Dispatchers.IO) { OwnerApprovalKey.complete(signingChallenge, unlocked) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (!lockScreenMissing(t)) throw t
                    usedLock = false
                    host?.setStatus(
                        AgentStatus.APPROVAL,
                        "This phone has no screen lock. One tap saves this file."
                    )
                    val local = withContext(Dispatchers.IO) {
                        OwnerApprovalKey.prepareLocal(proposal.id, nextOrdinal, now, fingerprint)
                    }
                    withContext(Dispatchers.IO) { OwnerApprovalKey.complete(local, local.signature) }
                }
                val modalities = if (usedLock) setOf(ApprovalModality.DEVICE_AUTH) else emptySet()
                val result = withContext(Dispatchers.IO) {
                    coordinatorProvider()?.approve(proposal.id, token, modalities, singleConfirmation = true)
                        ?: MutationApprovalResult.Rejected("This project is not open, so the change cannot be saved.")
                }
                when (result) {
                    is MutationApprovalResult.AwaitingSecond -> host?.onAwaitingSecond(result.proposal)
                    is MutationApprovalResult.Applied -> host?.onApplied(result)
                    is MutationApprovalResult.RepairRequired -> host?.onRepairRequired(result.proposal, result.failure)
                    is MutationApprovalResult.Rejected -> host?.onRejected(result.reason)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                host?.onFailed(plainApprovalError(t))
            } finally {
                biometricPrompt = null
                host?.setActiveJob(null)
            }
        }
        host?.setActiveJob(job)
    }

    fun beginGitHubPush(fingerprint: String) {
        if (!fingerprint.matches(Regex("[a-f0-9]{64}"))) {
            host?.onFailed("I could not lock this send to the exact files. Nothing left the phone.")
            return
        }
        if (host?.isBusy() == true) {
            host?.onFailed("Still working. Wait until it finishes, then try the send again.")
            return
        }
        githubFingerprint = fingerprint
        phoneLock = true
        riskNote = RiskyChange.plainSummary(setOf(RiskyChange.Kind.LEAVES_PHONE))
        error = null
        speechVerified = false
        spokenPhrase = ""
        typedPhrase = ""
        typedPhraseConfirm = ""
        setupMode = !store.ownerApprovalPhrasesConfigured()
        speechChallenge = if (setupMode) "" else freshSpeechChallenge()
        speechStatus = if (setupMode) {
            "Say the words you want to save, or type them in the box below."
        } else {
            "Say the words you saved, then these words: $speechChallenge"
        }
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
            speechStatus = "This phone cannot turn speech into text. Type the words instead."
            return
        }
        pendingMicRequest = true
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        pendingMicRequest = false
        if (!granted) {
            speechStatus = "This app cannot use the microphone. Type the words instead."
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
                speechStatus = "Listening. Say the words now."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                speechStatus = "Turning your speech into text."
            }
            override fun onError(errorCode: Int) {
                speechStatus = plainSpeechError(errorCode)
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                spokenPhrase = text
                speechVerified = store.verifyOwnerSpokenPhrase(text, speechChallenge)
                speechStatus = if (speechVerified) "Those spoken words match." else "Those spoken words do not match what you saved."
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
                error = "The two typed lines must be the same, and neither can be empty."
                return
            }
            if (spokenPhrase.isBlank()) {
                error = "Type the words you will say out loud."
                return
            }
            try {
                store.enrollOwnerApprovalPhrases(typedPhrase, spokenPhrase)
            } catch (t: IllegalArgumentException) {
                error = t.message ?: "Could not save your phrases."
                return
            }
            setupMode = false
            showDialog = false
            host?.onPassphrasesEnrolled("Your secret phrases are saved on this phone. Say yes to the change again.")
            return
        }

        if (!store.verifyOwnerTypedPhrase(typedPhrase)) {
            error = "Those typed words do not match what you saved."
            return
        }
        if (!speechVerified && !store.verifyOwnerSpokenPhrase(spokenPhrase, speechChallenge)) {
            error = "The spoken words do not match. Include the extra words shown above."
            return
        }

        val githubTarget = githubFingerprint
        if (githubTarget != null) {
            submitGitHubPush(githubTarget)
            return
        }

        val proposalId = pendingProposalIdProvider()
        if (proposalId.isNullOrBlank()) {
            error = "That change is no longer waiting."
            showDialog = false
            return
        }
        val coordinator = coordinatorProvider()
        if (coordinator == null) {
            error = "This project is not open, so the change cannot be saved."
            return
        }
        val proposal = coordinator.get(proposalId)
        if (proposal == null) {
            error = "That change could not be found."
            showDialog = false
            return
        }

        val activity = activityProvider()
        val job = scope.launch(Dispatchers.Main) {
            try {
                val now = System.currentTimeMillis()
                val fingerprint = OwnerApprovalKey.fingerprint(proposal.id, proposal.changeSet.changes)
                val nextOrdinal = proposal.approvalCount + 1
                val signingChallenge = withContext(Dispatchers.IO) {
                    OwnerApprovalKey.prepare(proposal.id, nextOrdinal, now, fingerprint)
                }
                val unlocked = awaitDeviceSignature(
                    activity,
                    signingChallenge.signature,
                    if (phoneLock) "Confirm with phone lock ($nextOrdinal of 2)" else "Confirm it is you ($nextOrdinal of 2)",
                    if (phoneLock) "Spoken words are not enough. Use your fingerprint, face, or screen lock."
                    else "Use your fingerprint, face, or screen lock"
                )
                val token = withContext(Dispatchers.IO) {
                    OwnerApprovalKey.complete(signingChallenge, unlocked)
                }
                val modalities = setOf(
                    ApprovalModality.TYPED,
                    ApprovalModality.SPOKEN,
                    ApprovalModality.DEVICE_AUTH
                )
                val result = withContext(Dispatchers.IO) {
                    coordinator.approve(proposalId, token, modalities)
                }
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
            } catch (cancelled: CancellationException) {
                showDialog = false
                throw cancelled
            } catch (t: Throwable) {
                error = plainApprovalError(t)
                host?.onFailed(error ?: "The phone could not confirm it is you.")
            } finally {
                biometricPrompt = null
                host?.setActiveJob(null)
            }
        }
        host?.setActiveJob(job)
    }

    private fun submitGitHubPush(fingerprint: String) {
        val activity = activityProvider()
        val job = scope.launch(Dispatchers.Main) {
            try {
                val now = System.currentTimeMillis()
                val signingChallenge = withContext(Dispatchers.IO) {
                    OwnerApprovalKey.prepare("github-push", 1, now, fingerprint)
                }
                val unlocked = awaitDeviceSignature(
                    activity,
                    signingChallenge.signature,
                    "Confirm with phone lock",
                    "This sends your work off this phone. Use your fingerprint, face, or screen lock."
                )
                val token = withContext(Dispatchers.IO) {
                    OwnerApprovalKey.complete(signingChallenge, unlocked)
                }
                val accepted = withContext(Dispatchers.IO) {
                    token.consume("github-push", 1, fingerprint, now)
                }
                if (!accepted) {
                    error = "The phone lock did not match this send. Nothing left the phone."
                    return@launch
                }
                showDialog = false
                githubFingerprint = null
                host?.onGitHubApproved(fingerprint)
            } catch (cancelled: CancellationException) {
                showDialog = false
                throw cancelled
            } catch (t: Throwable) {
                error = plainApprovalError(t)
                host?.onFailed(error ?: "The phone could not confirm it is you.")
            } finally {
                biometricPrompt = null
                host?.setActiveJob(null)
            }
        }
        host?.setActiveJob(job)
    }

    private suspend fun awaitDeviceSignature(
        activity: FragmentActivity,
        signature: Signature,
        title: String,
        subtitle: String
    ): Signature = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.signature
                    if (!cont.isActive) return
                    if (unlocked == null) {
                        cont.resumeWithException(
                            IllegalStateException("The phone did not finish confirming it is you. Try again.")
                        )
                    } else {
                        cont.resume(unlocked)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(errString.toString()))
                }
            }
        )
        biometricPrompt = prompt
        cont.invokeOnCancellation {
            activity.runOnUiThread { runCatching { prompt.cancelAuthentication() } }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
    }

    fun clearDialog() {
        showDialog = false
        error = null
        runCatching { biometricPrompt?.cancelAuthentication() }
        biometricPrompt = null
        disposeRecognizer()
    }

    fun disposeRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

private fun plainSpeechError(errorCode: Int): String = when (errorCode) {
    SpeechRecognizer.ERROR_AUDIO -> "The microphone had a problem. Try again, or type the words."
    SpeechRecognizer.ERROR_CLIENT -> "Speech was interrupted. Try again, or type the words."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "This app cannot use the microphone. Type the words instead."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        "The phone could not reach the speech service. Type the words instead."
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
        "The phone could not understand you. Try again, or type the words."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech is busy. Wait a moment, or type the words."
    SpeechRecognizer.ERROR_SERVER -> "The speech service failed. Type the words instead."
    else -> "The phone could not understand you. Try again, or type the words."
}

private fun plainApprovalError(error: Throwable): String {
    val raw = error.message.orEmpty()
    val kind = error.javaClass.simpleName
    if (kind.contains("UserNotAuthenticated") || raw.contains("UserNotAuthenticated", ignoreCase = true)) {
        return "This phone needs a screen lock (PIN, pattern, or fingerprint) before it can confirm you. Set one in the phone's security settings, then try again."
    }
    if (lockScreenMissing(error)) {
        return "This phone has no screen lock. Ordinary files can still be saved with one tap. A delete or a change to this app still needs a screen lock."
    }
    if (raw.isBlank()) return "The phone could not confirm it is you. Try again."
    if (raw.contains("Exception") || raw.contains("java.")) return "The phone could not confirm it is you. Try again."
    return raw
}

private fun lockScreenMissing(error: Throwable): Boolean {
    val raw = error.message.orEmpty()
    return raw.contains("Secure lock screen", ignoreCase = true) ||
        raw.contains("user authentication", ignoreCase = true) ||
        raw.contains("KeyStoreException", ignoreCase = true)
}

private fun freshSpeechChallenge(): String {
    val pool = CHALLENGE_WORDS.toMutableList()
    val random = SecureRandom()
    for (index in pool.indices) {
        val swap = index + random.nextInt(pool.size - index)
        val current = pool[index]
        pool[index] = pool[swap]
        pool[swap] = current
    }
    return pool.take(4).joinToString(" ")
}

private val CHALLENGE_WORDS = listOf(
    "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
    "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
    "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey",
    "xray", "yankee", "zulu", "amber", "birch", "cedar", "drift", "ember",
    "flint", "grove", "harbor", "ivory", "jasper", "kelp", "lunar", "maple",
    "north", "onyx", "pebble", "quartz", "river", "spruce", "timber", "umber"
)

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
    phoneLock: Boolean,
    riskNote: String,
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
                    when {
                        setupMode -> "Save your two secret phrases"
                        phoneLock -> "Confirm with phone lock"
                        else -> "Confirm this is you"
                    },
                    color = NeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        setupMode ->
                            "Pick words you will type, and words you will say. You need both every time something is saved. They stay on this phone."
                        phoneLock ->
                            riskNote.ifBlank { "Spoken words are not enough. Confirm with the phone lock." } +
                                " Type the words you saved. Then say your spoken words followed by: $speechChallenge."
                        else ->
                            "Type the words you saved. Then say your spoken words followed by: $speechChallenge."
                    },
                    color = SoftGreen,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typedPhrase,
                    onValueChange = onTypedChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Words you type", color = SoftGreen) },
                    singleLine = true,
                    colors = fieldColors()
                )
                if (setupMode) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typedPhraseConfirm,
                        onValueChange = onTypedConfirmChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Type those words again", color = SoftGreen) },
                        singleLine = true,
                        colors = fieldColors()
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = spokenPhrase,
                    onValueChange = onSpokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (setupMode) "Words you will say" else "Words you said", color = SoftGreen) },
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
                        Text(
                            when {
                                setupMode -> "Save phrases"
                                phoneLock -> "Confirm with phone lock"
                                else -> "Say yes"
                            },
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
