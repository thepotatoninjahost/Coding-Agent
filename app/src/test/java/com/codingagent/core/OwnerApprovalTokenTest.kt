package com.codingagent.core

import com.codingagent.workspace.ApprovalChannel
import com.codingagent.workspace.ApprovalPhrase
import com.codingagent.workspace.OwnerApprovalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerApprovalTokenTest {
    @Test
    fun spokenPhraseMustMatchProposalBoundChallenge() {
        val phrase = ApprovalPhrase.forProposal("proposal-1234")
        assertNotNull(OwnerApprovalToken.spoken("proposal-1234", phrase.lowercase()))
        assertNull(OwnerApprovalToken.spoken("proposal-1234", "APPROVE CHANGE DIFFERENT"))
        assertNull(OwnerApprovalToken.spoken("proposal-1234", "APPROVE CHANGE"))
    }

    @Test
    fun typedPhraseMustMatchExactly() {
        val phrase = ApprovalPhrase.forProposal("proposal-1234")
        assertNotNull(OwnerApprovalToken.typed("proposal-1234", phrase))
        assertNull(OwnerApprovalToken.typed("proposal-1234", phrase.lowercase()))
        assertNull(OwnerApprovalToken.typed("proposal-1234", "$phrase "))
    }

    @Test
    fun tokenIsBoundToProposalChannelAndConsumedOnce() {
        val phrase = ApprovalPhrase.forProposal("proposal-1234")
        val token = OwnerApprovalToken.spoken("proposal-1234", phrase, now = 1_000L)!!

        assertEquals(ApprovalChannel.SPOKEN_PHRASE, token.channel)
        assertTrue(token.consume("proposal-1234", ApprovalChannel.SPOKEN_PHRASE, 1_001L))
        assertFalse(token.consume("proposal-1234", ApprovalChannel.SPOKEN_PHRASE, 1_002L))
        val other = OwnerApprovalToken.spoken("proposal-1234", phrase, now = 1_000L)!!
        assertFalse(other.consume("proposal-2", ApprovalChannel.SPOKEN_PHRASE, 1_001L))
        assertFalse(other.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 1_001L))
    }

    @Test
    fun tokenExpiresAndCannotBeUsedBeforeIssueTime() {
        val phrase = ApprovalPhrase.forProposal("proposal-1234")
        val early = OwnerApprovalToken.typed("proposal-1234", phrase, now = 2_000L)!!
        assertFalse(early.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 1_999L))
        assertTrue(early.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 2_000L))

        val expired = OwnerApprovalToken.typed("proposal-1234", phrase, now = 1_000L)!!
        assertFalse(expired.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 61_001L))
    }
}
