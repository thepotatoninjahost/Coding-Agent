package com.codingagent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.codingagent.agent.ApprovalModality
import com.codingagent.core.LocalStore
import com.codingagent.core.normalizeOwnerSpokenPhrase
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.OwnerApprovalKey
import com.codingagent.workspace.PendingChangeProposal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host surface for approval outcomes so CodingAgentApp keeps pending-proposal and chat state.
 */
internal object NoOpOwnerApprovalHost : OwnerApprovalHost {
    override fun isBusy(): Boolean = true
    override fun setActiveJob(job: Job?) {}
    override fun setStatus(status: AgentStatus, detail: String) {}
    override fun clearPendingProposal(detail: String) {}
    override fun onAwaitingSecond(proposal: PendingChangeProposal) {}
    override fun onRepairRequired(proposal: PendingChangeProposal, detail: String) {}
    override fun onApplied(result: MutationApprovalResult.Applied) {}
    override fun onRejected(reason: String) {}
    override fun onFailed(message: String) {}
    override fun onPassphrasesEnrolled(detail: String) {}
}

internal interface OwnerApprovalHost {
    fun isBusy(): Boolean
    fun setActiveJob(job: Job?)
    fun setStatus(status: AgentStatus, detail: String)
    fun clearPendingProposal(detail: String)
    fun onAwaitingSecond(proposal: PendingChangeProposal)
    fun onRepairRequired(proposal: PendingChangeProposal, detail: String)
    fun onApplied(result: MutationApprovalResult.Applied)
    fun onRejected(reason: String)
    fun onFailed(message: String)
    fun onPassphrasesEnrolled(detail: String)
}

/**
 * ONE JOB: Owner approval flow — dialog state, on-device speech, biometric signing, coordinator approve.
 */
internal class OwnerApprovalController(
    private val context: Context,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    private val coordinatorProvider: () -> MutationCoordinator?,
    private val pendingProposalIdProvider: () -> String?
) {
    /** Reassigned each composition so host callbacks see current Compose state. */
    var host: OwnerApprovalHost = NoOpOwnerApprovalHost

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
    var speechVerified by mutableStateOf(false)
        private set
    var speechChallenge by mutableStateOf(OwnerSpeechChallenge.next())
        private set
    var speechStatus by mutableStateOf("Speak your passphrase followed by the displayed one-time word.")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var speechPurpose: String = "approval"
    private var speechRecognizer: SpeechRecognizer? = null
    private var approvalPromptOpen: Boolean = false

    fun onTypedChange(value: String) {
        typedPhrase = value
        if (speechVerified) {
            speechVerified = false
            speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
            speechStatus = "Passphrase changed; speak the new challenge."
        }
        error = null
    }

    fun onTypedConfirmChange(value: String) {
        typedPhraseConfirm = value
        error = null
    }

    fun onSpokenChange(value: String) {
        spokenPhrase = value
        if (speechVerified) {
            speechVerified = false
            speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
            speechStatus = "Spoken passphrase changed; speak the new challenge."
        }
        error = null
    }

    fun clearDialog() {
        showDialog = false
        typedPhrase = ""
        typedPhraseConfirm = ""
        spokenPhrase = ""
        speechVerified = false
        error = null
        val activeRecognizer = speechRecognizer
        speechRecognizer = null
        activeRecognizer?.cancel()
    }

    fun disposeRecognizer() {
        val activeRecognizer = speechRecognizer
        speechRecognizer = null
        activeRecognizer?.destroy()
    }

    fun requestSpeechCapture() {
        speechPurpose = if (setupMode) "setup" else "approval"
        if (speechVerified) speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
        speechVerified = false
        error = null
        speechStatus = "Preparing on-device speech recognition…"
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            speechStatus = "On-device speech recognition is unavailable. Approval is blocked."
            error = speechStatus
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginOnDeviceSpeechRecognition()
        } else {
            pendingMicRequest = true
        }
    }

    var pendingMicRequest by mutableStateOf(false)
        private set

    fun onMicrophonePermissionResult(granted: Boolean) {
        pendingMicRequest = false
        if (granted) {
            beginOnDeviceSpeechRecognition()
        } else {
            speechVerified = false
            speechStatus = "Microphone permission denied."
            error = "Spoken passphrase is required; approval was not authorized."
        }
    }

    private fun beginOnDeviceSpeechRecognition() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            speechVerified = false
            speechStatus = "On-device speech recognition is unavailable. Approval is blocked."
            error = speechStatus
            return
        }
        runCatching {
            val previousRecognizer = speechRecognizer
            speechRecognizer = null
            previousRecognizer?.cancel()
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (speechRecognizer !== recognizer) return
                    speechStatus = "Listening. Speak the passphrase, then the challenge words."
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (speechRecognizer !== recognizer) return
                    speechStatus = "Checking spoken passphrase…"
                }
                override fun onError(errorCode: Int) {
                    if (speechRecognizer !== recognizer) return
                    speechVerified = false
                    speechStatus = "Speech recognition failed. Try again."
                    error = "Spoken passphrase was not verified; approval remains blocked."
                    speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
                    speechRecognizer = null
                }
                override fun onResults(results: Bundle?) {
                    if (speechRecognizer !== recognizer) return
                    val recognized = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    val normalized = normalizeOwnerSpokenPhrase(recognized)
                    val expectedSetupTranscript = normalizeOwnerSpokenPhrase(spokenPhrase) +
                        " " + normalizeOwnerSpokenPhrase(speechChallenge)
                    val valid = if (speechPurpose == "setup") {
                        normalized.isNotBlank() && normalized == expectedSetupTranscript
                    } else {
                        store.verifyOwnerSpokenPhrase(recognized, speechChallenge)
                    }
                    speechVerified = valid
                    speechStatus = if (valid) {
                        if (speechPurpose == "setup") "Spoken phrase and challenge confirmed."
                        else "Spoken phrase and challenge matched."
                    } else {
                        "Spoken passphrase did not match. Try again."
                    }
                    error = if (valid) null else "Spoken passphrase was not verified; approval remains blocked."
                    if (!valid) speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
                    speechRecognizer = null
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer.startListening(intent)
        }.onFailure { err ->
            speechVerified = false
            speechStatus = "On-device speech recognition could not start."
            error = "Speech recognition failed closed: ${err.message.orEmpty()}"
            speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
            speechRecognizer = null
        }
    }

    fun beginApprovalForPendingProposal() {
        val id = pendingProposalIdProvider() ?: return
        val coordinator = coordinatorProvider() ?: return
        if (host.isBusy() || approvalPromptOpen || showDialog) return
        if (coordinator.get(id) == null) {
            host.clearPendingProposal("The proposal is no longer pending.")
            return
        }
        setupMode = !store.ownerApprovalPhrasesConfigured()
        typedPhrase = ""
        typedPhraseConfirm = ""
        spokenPhrase = ""
        speechVerified = false
        speechChallenge = OwnerSpeechChallenge.next(speechChallenge)
        speechStatus = if (setupMode) {
            "Create a spoken passphrase yourself, then speak it followed by the one-time word."
        } else {
            "Speak your enrolled spoken passphrase followed by the one-time challenge words."
        }
        error = null
        showDialog = true
    }

    fun submitDialog() {
        val id = pendingProposalIdProvider() ?: run {
            error = "No pending proposal is available."
            return
        }
        if (setupMode) {
            if (typedPhrase.isBlank() || typedPhrase != typedPhraseConfirm) {
                error = "Typed passphrases must be non-empty and match exactly."
                return
            }
            if (spokenPhrase.isBlank() || !speechVerified) {
                error = "Create the spoken passphrase and verify it by speaking it on-device."
                return
            }
            runCatching {
                store.enrollOwnerApprovalPhrases(typedPhrase, spokenPhrase)
            }.onFailure { err ->
                error = err.message.orEmpty().ifBlank { "Owner passphrases could not be saved." }
                return
            }
            clearDialog()
            host.onPassphrasesEnrolled(
                "Owner passphrases saved as salted verifiers. Tap Approve again to authorize the pending proposal."
            )
            return
        }

        if (!store.verifyOwnerTypedPhrase(typedPhrase)) {
            error = "Typed owner passphrase did not match. Approval is blocked."
            return
        }
        if (!speechVerified) {
            error = "The spoken owner passphrase has not been verified. Approval is blocked."
            return
        }
        clearDialog()
        performAuthenticatedApproval(id)
    }

    private fun performAuthenticatedApproval(id: String) {
        val coordinator = coordinatorProvider() ?: return
        if (host.isBusy() || approvalPromptOpen) return
        val proposal = coordinator.get(id) ?: run {
            clearDialog()
            host.clearPendingProposal("The proposal is no longer pending.")
            return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val biometricManager = BiometricManager.from(context)
        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            error = "Owner device authentication is unavailable. Approval is blocked."
            host.setStatus(AgentStatus.STOPPED, error.orEmpty())
            return
        }

        val challenge = runCatching {
            OwnerApprovalKey.prepare(
                proposal.id,
                proposal.approvalCount + 1,
                System.currentTimeMillis(),
                OwnerApprovalKey.fingerprint(proposal.id, proposal.changeSet.changes)
            )
        }.getOrElse { err ->
            error = "Could not prepare a protected approval challenge: ${err.message.orEmpty()}"
            host.setStatus(AgentStatus.FAILED, error.orEmpty())
            return
        }
        approvalPromptOpen = true
        val activity = context as? FragmentActivity ?: run {
            approvalPromptOpen = false
            error = "Owner authentication is unavailable in this UI context."
            host.setStatus(AgentStatus.FAILED, error.orEmpty())
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
                    val token = runCatching {
                        val authenticatedSignature = result.cryptoObject?.signature
                            ?: error("Android did not return the authenticated signing operation")
                        OwnerApprovalKey.complete(challenge, authenticatedSignature)
                    }.getOrElse { err ->
                        host.onFailed("Cryptographic owner approval failed closed: ${err.message.orEmpty()}")
                        return
                    }
                    host.setStatus(
                        AgentStatus.WORKING,
                        "Cryptographically verified owner approval; recording it off the UI thread…"
                    )
                    host.setActiveJob(
                        scope.launch(Dispatchers.IO) {
                            val outcome = runCatching {
                                coordinator.approve(
                                    id,
                                    token,
                                    setOf(
                                        ApprovalModality.SPOKEN,
                                        ApprovalModality.TYPED,
                                        ApprovalModality.DEVICE_AUTH
                                    )
                                )
                            }
                            withContext(Dispatchers.Main.immediate) {
                                outcome.onSuccess { approvalResult ->
                                    when (approvalResult) {
                                        is MutationApprovalResult.AwaitingSecond ->
                                            host.onAwaitingSecond(approvalResult.proposal)
                                        is MutationApprovalResult.RepairRequired ->
                                            host.onRepairRequired(
                                                approvalResult.proposal,
                                                "Original change was rolled back. Review the staged repair and authenticate again."
                                            )
                                        is MutationApprovalResult.Applied ->
                                            host.onApplied(approvalResult)
                                        is MutationApprovalResult.Rejected ->
                                            host.onRejected(approvalResult.reason)
                                    }
                                }.onFailure { err ->
                                    host.onFailed(
                                        "Approval failed: " +
                                            (err.message.orEmpty().ifBlank { err.javaClass.simpleName })
                                    )
                                }
                                host.setActiveJob(null)
                            }
                        }
                    )
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    approvalPromptOpen = false
                    host.setStatus(
                        AgentStatus.STOPPED,
                        "Owner authentication cancelled or failed: $errString"
                    )
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm code change")
            .setSubtitle("Sign this exact proposal as the device owner")
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(true)
            .build()
        runCatching {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(challenge.signature))
        }.onFailure { err ->
            approvalPromptOpen = false
            error = "Protected owner authentication failed closed: ${err.message.orEmpty()}"
            host.setStatus(AgentStatus.FAILED, error.orEmpty())
        }
    }
}
