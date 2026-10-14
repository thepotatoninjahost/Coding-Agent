package com.codingagent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerApprovalPhraseStoreTest {
    @Test
    fun enrollmentStoresOnlySaltedVerifiersAndChecksBothFactors() {
        val values = mutableMapOf<String, String?>()
        val store = OwnerApprovalPhraseStore({ values[it] }, { key, value ->
            if (value == null) values.remove(key) else values[key] = value
        })

        store.enroll("Typed-phrase-Blue", "spoken phrase cedar")

        assertTrue(store.isConfigured())
        assertTrue(store.verifyTyped("Typed-phrase-Blue"))
        assertFalse(store.verifyTyped("typed-phrase-blue"))
        assertFalse(store.verifyTyped("wrong"))
        assertTrue(store.verifySpoken("Spoken Phrase Cedar, Maple.", "maple"))
        assertFalse(store.verifySpoken("Spoken Phrase Cedar", "maple"))
        assertFalse(store.verifySpoken("Spoken Phrase Cedar, Harbor.", "maple"))
        assertTrue(values.values.all { !it.orEmpty().contains("Typed-phrase-Blue") && !it.orEmpty().contains("spoken phrase cedar") })
        assertNotEquals(values.values.first(), values.values.last())
    }

    @Test
    fun blankOrIdenticalPassphrasesAreRejected() {
        val values = mutableMapOf<String, String?>()
        val store = OwnerApprovalPhraseStore({ values[it] }, { key, value ->
            if (value == null) values.remove(key) else values[key] = value
        })

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.enroll("", "spoken phrase")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.enroll("same phrase", " SAME   PHRASE ")
        }
        assertFalse(store.isConfigured())
    }
}
