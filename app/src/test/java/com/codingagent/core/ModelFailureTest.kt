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
        assertTrue(line.contains("worker pool", ignoreCase = true) || line.contains("ResourceExhausted"))
        assertFalse(line.contains("\"origin\""))
        assertTrue(line.contains("Model settings"))
    }

    @Test
    fun ordinaryTextIsNotCapacity() {
        assertFalse(ModelFailure.isCapacity("file does not exist: SelfEvolution.kt"))
        assertFalse(ModelFailure.isRateLimit("Model returned no usable message content"))
        assertTrue(ModelFailure.isEmpty("Model returned no usable message content"))
    }

    @Test
    fun waitSecondsDefaultsForCapacity() {
        assertEquals(12, ModelFailure.waitSeconds(nvidia503))
    }
}
