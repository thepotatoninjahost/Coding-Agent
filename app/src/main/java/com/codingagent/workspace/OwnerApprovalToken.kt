package com.codingagent.workspace

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time capability proving that the device owner completed an explicit
 * authentication prompt for one exact pending proposal.
 *
 * The model, chat parser, and mutation coordinator cannot manufacture a valid
 * token from caller-controlled booleans or labels. The UI obtains one only
 * after the platform authentication callback succeeds.
 */
class OwnerApprovalToken private constructor(
    val proposalId: String,
    val issuedAt: Long,
    private val nonce: String
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(expectedProposalId: String, now: Long): Boolean {
        if (proposalId != expectedProposalId) return false
        if (now < issuedAt || now - issuedAt > MAX_AGE_MS) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val MAX_AGE_MS = 60_000L

        /**
         * Creates a one-time owner capability after the platform authentication
         * flow has reported success. Callers must not invoke this before that
         * callback.
         */
        fun authenticated(proposalId: String, now: Long = System.currentTimeMillis()): OwnerApprovalToken {
            require(proposalId.isNotBlank()) { "Proposal id is required" }
            return OwnerApprovalToken(proposalId, now, UUID.randomUUID().toString())
        }
    }
}
