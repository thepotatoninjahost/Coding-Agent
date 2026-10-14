package com.codingagent.core

import com.codingagent.workspace.OwnerApprovalToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerApprovalTokenTest {
    @Test
    fun signedApprovalIsBoundToTheExactProposalFingerprint() {
        val token = OwnerApprovalToken.authenticated("proposal-bound", now = 1_000L, approvalNumber = 1, fingerprint = "a".repeat(64))
        assertFalse(token.consume("proposal-bound", 1, "b".repeat(64), 1_001L))
    }

    @Test
    fun tokenIsBoundToOneProposalAndConsumedOnce() {
        val token = OwnerApprovalToken.authenticated("proposal-1", now = 1_000L)

        assertTrue(token.consume("proposal-1", 1, 1_001L))
        assertFalse(token.consume("proposal-1", 1, 1_002L))
        assertFalse(token.consume("proposal-2", 1, 1_003L))
    }

    @Test
    fun tokenExpiresAfterOneMinute() {
        val token = OwnerApprovalToken.authenticated("proposal-1", now = 1_000L)

        assertTrue(token.consume("proposal-1", 1, 61_000L))
        assertFalse(
            OwnerApprovalToken.authenticated("proposal-1", now = 1_000L)
                .consume("proposal-1", 1, 61_001L)
        )
    }

    @Test
    fun signedTokenCannotBeReplayedAsTheOtherApprovalStep() {
        val firstApproval = OwnerApprovalToken.authenticated(
            "proposal-1", now = 1_000L, approvalNumber = 1
        )
        assertFalse(firstApproval.consume("proposal-1", 2, 1_001L))
        assertTrue(firstApproval.consume("proposal-1", 1, 1_001L))
    }

    @Test
    fun malformedSignatureCannotAuthorizeApproval() {
        val forged = OwnerApprovalToken.fromTestSignature(
            "proposal-1", 1, 1_000L, "nonce", "a".repeat(64), byteArrayOf(1, 2, 3)
        )
        assertFalse(forged.consume("proposal-1", 1, 1_001L))
    }

    @Test
    fun tokenCannotBeUsedBeforeItsIssueTime() {
        val token = OwnerApprovalToken.authenticated("proposal-1", now = 2_000L)

        assertFalse(token.consume("proposal-1", 1, 1_999L))
        assertTrue(token.consume("proposal-1", 1, 2_000L))
    }
}
