package com.codingagent.workspace

import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class ApprovalChannel { SPOKEN_PHRASE, TYPED_PHRASE }

/** Proposal-bound challenge text used by both independent approval channels. */
object ApprovalPhrase {
    fun forProposal(proposalId: String): String = "APPROVE CHANGE ${proposalId.take(8).uppercase(Locale.ROOT)}"

    internal fun normalizeSpeech(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[.,!?]+$"), "")
        .uppercase(Locale.ROOT)
}

/** One-time, proposal-bound evidence from one explicit owner approval channel. */
class OwnerApprovalToken private constructor(
    val proposalId: String,
    val channel: ApprovalChannel,
    val issuedAt: Long,
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

        /** Create only after Android speech recognition returns the spoken challenge. */
        internal fun spoken(
            proposalId: String,
            transcript: String,
            now: Long = System.currentTimeMillis()
        ): OwnerApprovalToken? {
            require(proposalId.isNotBlank()) { "Proposal id is required" }
            if (ApprovalPhrase.normalizeSpeech(transcript) != ApprovalPhrase.forProposal(proposalId)) return null
            return OwnerApprovalToken(proposalId, ApprovalChannel.SPOKEN_PHRASE, now, UUID.randomUUID().toString())
        }

        /** Create only after the owner types the challenge exactly, character for character. */
        internal fun typed(
            proposalId: String,
            typedPhrase: String,
            now: Long = System.currentTimeMillis()
        ): OwnerApprovalToken? {
            require(proposalId.isNotBlank()) { "Proposal id is required" }
            if (typedPhrase != ApprovalPhrase.forProposal(proposalId)) return null
            return OwnerApprovalToken(proposalId, ApprovalChannel.TYPED_PHRASE, now, UUID.randomUUID().toString())
        }
    }
}
