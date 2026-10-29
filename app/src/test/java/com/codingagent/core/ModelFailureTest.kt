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
    fun nvidiaWorkerCapSwitchesHelpersInsteadOfRetrying() {
        assertTrue(ModelFailure.isCapacity(nvidia503))
        assertTrue(ModelFailure.isRateLimit(nvidia503))
        assertFalse(ModelFailure.isRetryable(nvidia503))
        assertTrue(ModelFailure.isCapacity(upstream))
        assertFalse(ModelFailure.isRetryable(upstream))
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
    fun rejectedKeyAndRateLimitArePlainEnglish() {
        val rejected = ModelFailure.humanize(
            """Model HTTP 401: {"error":{"message":"Invalid API key"}}"""
        )
        assertTrue(rejected.contains("secret password", ignoreCase = true))
        assertTrue(rejected.contains("openrouter.ai/keys"))
        assertFalse(rejected.contains("{"))

        val limited = ModelFailure.humanize(
            """Model HTTP 429: {"error":{"message":"Rate limit exceeded"}}"""
        )
        assertTrue(ModelFailure.isRateLimit(limited).not())
        assertTrue(ModelFailure.isRateLimit("""Model HTTP 429: {"error":{"message":"Rate limit exceeded"}}"""))
        assertTrue(limited.contains("slow down", ignoreCase = true))
        assertFalse(limited.contains("{"))
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

    @Test
    fun aGroqMinuteLimitThatTheRequestExceedsIsTooBigAndAShortOneWaits() {
        val now = 1_700_000_000_000L
        val pace = """
            Model HTTP 429: Rate limit reached for model openai/gpt-oss-120b on tokens per minute (TPM): Limit 8000, Used 6800, Requested 1200. Please try again in 1s.
            x-ratelimit-reset-tokens: 1s
            Retry-After: 1
        """.trimIndent()
        assertFalse(ModelFailure.isRequestTooLarge(pace))
        assertFalse(ModelFailure.isDailyFreeLimit(pace))
        assertTrue(ModelFailure.isRateLimit(pace))
        assertEquals(now + 1_000L, com.codingagent.model.QuotaHeaders.resetFromMessage(pace, now))
        assertEquals(now + 1_000L, com.codingagent.model.QuotaHeaders.parseReset("1s", now))
        val duration = com.codingagent.model.QuotaHeaders.parseReset("2m59.56s", now)!! - now
        assertTrue(duration in 179_000L..180_000L)

        val oversize = "Model HTTP 429: tokens per minute (TPM): Limit 8000, Used 0, Requested 12000. Please try again in 1s.\n" +
            "x-ratelimit-reset-tokens: 1s\nRetry-After: 1"
        assertTrue(ModelFailure.isRequestTooLarge(oversize))
        assertFalse(ModelFailure.isRateLimit(oversize))
        assertFalse(ModelFailure.isDailyFreeLimit(oversize))

        val soon = System.currentTimeMillis() + 30_000L
        val phrase = ModelFailure.humanize("ALL_HELPERS_EXHAUSTED\nresume-at-ms:$soon\nresume-scheduled")
        assertTrue(phrase.contains("in 30 seconds") || phrase.contains("in 29 seconds") || phrase.contains("in 31 seconds"))
        assertFalse(phrase.contains("today at"))

        val quota = com.codingagent.model.ProviderQuotaBook()
        quota.noteHttp("groq", "openai/gpt-oss-120b", 429, 0, now + 1_000L)
        assertFalse(quota.isAvailable("groq", "openai/gpt-oss-120b", now + 100L))
        quota.noteFailure("groq", "openai/gpt-oss-120b", oversize, now)
        assertTrue(quota.isAvailable("groq", "openai/gpt-oss-120b", now + 100L))
    }
}
