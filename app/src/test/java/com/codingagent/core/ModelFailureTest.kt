package com.codingagent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import com.codingagent.agent.ModelFailure

class ModelFailureTest {
    private val nvidia503 =
        """Model HTTP 503: {"error":{"origin":"provider","message":"ResourceExhausted: Worker local total request limit reached (16/16)"}}"""

    private val upstream =
        """{"id":"gen-1","error":{"message":"Upstream error from Nvidia: ResourceExhausted: Worker local total request limit reached (16/16)"}}"""

    @Test
    fun nvidiaWorkerCapIsCapacityAndRetryable() {
        assertTrue(ModelFailure.isCapacity(nvidia503))
        assertTrue(ModelFailure.isRateLimit(nvidia503))
        assertTrue(ModelFailure.isRetryable(nvidia503))
        assertTrue(ModelFailure.isCapacity(upstream))
        assertTrue(ModelFailure.isRetryable(upstream))
    }

    @Test
    fun humanizeDoesNotDumpRawJsonForWorkerCap() {
        val line = ModelFailure.humanize(nvidia503)
        assertTrue(line.contains("too busy", ignoreCase = true))
        assertFalse(line.contains("\"origin\""))
        assertFalse(line.contains("16/16"))
        assertTrue(line.contains("Setup"))
    }

    @Test
    fun ordinaryTextIsNotCapacity() {
        assertFalse(ModelFailure.isCapacity("file does not exist: SelfEvolution.kt"))
        assertFalse(ModelFailure.isRateLimit("Model returned no usable message content"))
        assertTrue(ModelFailure.isEmpty("Model returned no usable message content"))
    }

    @Test
    fun longUnclassifiedProviderErrorIsReturnedCompletely() {
        val message = "provider failure " + "details ".repeat(100) + "FINAL-DIAGNOSTIC"
        assertEquals(message, ModelFailure.humanize(message))
    }

    @Test
    fun waitSecondsDefaultsForCapacity() {
        assertEquals(12, ModelFailure.waitSeconds(nvidia503))
    }
}
