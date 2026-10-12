package com.codingagent.workspace

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class ApprovalChannel { SPOKEN_PHRASE, TYPED_PHRASE }

/** Normalization used only to compare speech-recognition output with the phrase supplied by the owner. */
object ApprovalPhrase {
    internal fun normalizeSpeech(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[.,!?]+$"), "")
        .uppercase(Locale.ROOT)

    /**
     * Use a deliberately expensive verifier so a stored approval record is not
     * a cheap SHA-256 passphrase-cracking oracle. Proposal id is public, per-proposal salt.
     */
    internal fun fingerprint(proposalId: String, value: String): String {
        val spec = PBEKeySpec(value.toCharArray(), proposalId.toByteArray(Charsets.UTF_8), 600_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                .joinToString("") { "%02x".format(it) }
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * One-time evidence for one approval channel. The phrase is supplied by the owner;
 * this class never generates, embeds, or defaults a password or passphrase.
 *
 * This records explicit interaction confirmation, not proof of speaker identity.
 */
class OwnerApprovalToken private constructor(
    val proposalId: String,
    val channel: ApprovalChannel,
    val issuedAt: Long,
    internal val phraseFingerprint: String,
    private val nonce: String
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(expectedProposalId: String, expectedChannel: ApprovalChannel, now: Long): Boolean {
        if (proposalId != expectedProposalId || channel != expectedChannel) return false
        if (now < issuedAt || now - issuedAt > MAX_AGE_MS) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val MAX_AGE_MS = 60_000L

        /** Called after the owner supplies a phrase and speech recognition returns a matching transcript. */
        internal fun spoken(
            proposalId: String,
            ownerPhrase: String,
            transcript: String,
            now: Long = System.currentTimeMillis()
        ): OwnerApprovalToken? {
            require(proposalId.isNotBlank()) { "Proposal id is required" }
            if (ownerPhrase.isBlank()) return null
            if (ApprovalPhrase.normalizeSpeech(transcript) != ApprovalPhrase.normalizeSpeech(ownerPhrase)) return null
            return OwnerApprovalToken(
                proposalId, ApprovalChannel.SPOKEN_PHRASE, now,
                ApprovalPhrase.fingerprint(proposalId, ownerPhrase), UUID.randomUUID().toString()
            )
        }

        /** Called after the owner types the exact phrase they supplied for this proposal. */
        internal fun typed(
            proposalId: String,
            ownerPhrase: String,
            typedPhrase: String,
            now: Long = System.currentTimeMillis()
        ): OwnerApprovalToken? {
            require(proposalId.isNotBlank()) { "Proposal id is required" }
            if (ownerPhrase.isBlank() || typedPhrase != ownerPhrase) return null
            return OwnerApprovalToken(
                proposalId, ApprovalChannel.TYPED_PHRASE, now,
                ApprovalPhrase.fingerprint(proposalId, ownerPhrase), UUID.randomUUID().toString()
            )
        }
    }
}
