package com.codingagent.core

import com.codingagent.workspace.ApprovalChannel
import com.codingagent.workspace.ApprovalPhrase
import com.codingagent.workspace.OwnerApprovalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerApprovalTokenTest {
    private val ownerPhrase = "owner-created test phrase"

    @Test
    fun phraseFingerprintChangesWhenOwnerPhraseChanges() {
        assertNotEquals(
            ApprovalPhrase.fingerprint("proposal-1234", ownerPhrase),
            ApprovalPhrase.fingerprint("proposal-1234", "a different owner-created phrase")
        )
    }

    @Test
    fun samePhraseHasDifferentVerifierForDifferentProposals() {
        assertNotEquals(
            ApprovalPhrase.fingerprint("proposal-1", ownerPhrase),
            ApprovalPhrase.fingerprint("proposal-2", ownerPhrase)
        )
    }

    @Test
    fun spokenPhraseMustMatchOwnerCreatedPhrase() {
        assertTrue(OwnerApprovalToken.spoken("proposal-1234", ownerPhrase, ownerPhrase.lowercase()) != null)
        assertNull(OwnerApprovalToken.spoken("proposal-1234", ownerPhrase, "different phrase"))
        assertNull(OwnerApprovalToken.spoken("proposal-1234", " ", ownerPhrase))
    }

    @Test
    fun typedPhraseMustMatchExactly() {
        assertTrue(OwnerApprovalToken.typed("proposal-1234", ownerPhrase, ownerPhrase) != null)
        assertNull(OwnerApprovalToken.typed("proposal-1234", ownerPhrase, ownerPhrase.lowercase()))
        assertNull(OwnerApprovalToken.typed("proposal-1234", ownerPhrase, "$ownerPhrase "))
        assertNull(OwnerApprovalToken.typed("proposal-1234", "", ""))
    }

    @Test
    fun tokenIsBoundToProposalChannelPhraseAndConsumedOnce() {
        val token = OwnerApprovalToken.spoken("proposal-1234", ownerPhrase, ownerPhrase, now = 1_000L)!!

        assertEquals(ApprovalChannel.SPOKEN_PHRASE, token.channel)
        assertTrue(token.consume("proposal-1234", ApprovalChannel.SPOKEN_PHRASE, 1_001L))
        assertFalse(token.consume("proposal-1234", ApprovalChannel.SPOKEN_PHRASE, 1_002L))
        val other = OwnerApprovalToken.spoken("proposal-1234", ownerPhrase, ownerPhrase, now = 1_000L)!!
        assertFalse(other.consume("proposal-2", ApprovalChannel.SPOKEN_PHRASE, 1_001L))
        assertFalse(other.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 1_001L))
        assertNotEquals(
            other.phraseFingerprint,
            OwnerApprovalToken.typed("proposal-1234", "different owner phrase", "different owner phrase")!!.phraseFingerprint
        )
    }

    @Test
    fun tokenExpiresAndCannotBeUsedBeforeIssueTime() {
        val early = OwnerApprovalToken.typed("proposal-1234", ownerPhrase, ownerPhrase, now = 2_000L)!!
        assertFalse(early.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 1_999L))
        assertTrue(early.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 2_000L))

        val expired = OwnerApprovalToken.typed("proposal-1234", ownerPhrase, ownerPhrase, now = 1_000L)!!
        assertFalse(expired.consume("proposal-1234", ApprovalChannel.TYPED_PHRASE, 61_001L))
    }
}
