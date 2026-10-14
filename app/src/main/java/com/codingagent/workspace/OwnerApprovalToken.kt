package com.codingagent.workspace

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time, cryptographically authenticated owner capability for one exact
 * proposal and approval ordinal. Android production tokens must carry a valid
 * signature from the auth-per-use Android Keystore key.
 */
class OwnerApprovalToken private constructor(
    val proposalId: String,
    val approvalNumber: Int,
    val issuedAt: Long,
    private val nonce: String,
    private val signature: ByteArray,
    private val testOnly: Boolean
) {
    private val consumed = AtomicBoolean(false)

    internal fun consume(expectedProposalId: String, expectedApprovalNumber: Int, now: Long): Boolean {
        if (proposalId != expectedProposalId) return false
        // The wildcard ordinal is permitted only for legacy JVM acceptance tests.
        if (!(testOnly && approvalNumber == 0) && approvalNumber != expectedApprovalNumber) return false
        if (now < issuedAt || now - issuedAt > MAX_AGE_MS) return false
        if (!OwnerApprovalKey.verify(
                proposalId, approvalNumber, issuedAt, nonce, signature, testOnly
            )) return false
        return consumed.compareAndSet(false, true)
    }

    companion object {
        private const val MAX_AGE_MS = 60_000L

        internal fun fromSigned(
            proposalId: String,
            approvalNumber: Int,
            issuedAt: Long,
            nonce: String,
            signature: ByteArray
        ): OwnerApprovalToken {
            require(proposalId.isNotBlank() && approvalNumber in 1..2 && nonce.isNotBlank())
            return OwnerApprovalToken(proposalId, approvalNumber, issuedAt, nonce, signature.copyOf(), false)
        }

        internal fun fromTestSignature(
            proposalId: String,
            approvalNumber: Int,
            issuedAt: Long,
            nonce: String,
            signature: ByteArray
        ): OwnerApprovalToken =
            OwnerApprovalToken(proposalId, approvalNumber, issuedAt, nonce, signature.copyOf(), true)

        /** JVM-only compatibility factory for tests; throws on Android. */
        internal fun authenticated(
            proposalId: String,
            now: Long = System.currentTimeMillis(),
            approvalNumber: Int = 0
        ): OwnerApprovalToken {
            require(proposalId.isNotBlank()) { "Proposal id is required" }
            return OwnerApprovalKey.testToken(proposalId, approvalNumber, now)
        }
    }
}
