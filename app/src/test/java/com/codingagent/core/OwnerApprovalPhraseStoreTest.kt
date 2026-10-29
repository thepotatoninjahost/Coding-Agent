package com.codingagent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE JOB: Unit-test owner typed/spoken phrase enrollment and verification.
 */
class OwnerApprovalPhraseStoreTest {
    private fun store(): OwnerApprovalPhraseStore {
        val map = mutableMapOf<String, String?>()
        return OwnerApprovalPhraseStore(
            readSecret = { map[it] },
            writeSecret = { k, v -> map[k] = v }
        )
    }

    @Test
    fun enrollAndVerifyTypedAndSpoken() {
        val s = store()
        assertFalse(s.isConfigured())
        s.enroll("TypedSecret-1", "spoken secret one")
        assertTrue(s.isConfigured())
        assertTrue(s.verifyTyped("TypedSecret-1"))
        assertFalse(s.verifyTyped("wrong"))
        assertTrue(s.verifySpoken("spoken secret one challenge", "challenge"))
        assertFalse(s.verifySpoken("spoken secret one challenge", "other"))
        assertFalse(s.verifySpoken("wrong phrase challenge", "challenge"))
    }

    @Test
    fun shortPhraseIsRejected() {
        val s = store()
        try {
            s.enroll("short", "spoken secret one")
            throw AssertionError("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("8"))
        }
    }

    @Test
    fun spokenAndTypedMustDifferWhenNormalized() {
        val s = store()
        try {
            s.enroll("same phrase", "same phrase")
            throw AssertionError("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("different"))
        }
    }

    @Test
    fun normalizeOwnerSpokenPhraseCollapsesNoise() {
        assertTrue(normalizeOwnerSpokenPhrase("  Hello,  WORLD!! ") == "hello world")
    }
}
