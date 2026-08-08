package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSettingsTest {
    @Test
    fun localBackendNeedsNoRemoteFields() {
        val settings = ModelSettings(backend = ModelBackend.LOCAL_NEXA)
        assertTrue(settings.validationErrors().isEmpty())
        assertNull(settings.remoteGateway())
        assertTrue(settings.statusSummary().contains("Local NPU"))
    }

    @Test
    fun remoteRequiresUrlModelAndKeyUnlessLocalhost() {
        val incomplete = ModelSettings(
            backend = ModelBackend.REMOTE_OPENAI,
            baseUrl = "",
            apiKey = "",
            modelName = ""
        )
        assertTrue(incomplete.validationErrors().size >= 2)

        val remote = ModelSettings(
            backend = ModelBackend.REMOTE_OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            modelName = "gpt-4o-mini"
        )
        assertTrue(remote.validationErrors().isEmpty())
        assertTrue(remote.isRemoteConfigured())
        assertNotNull(remote.remoteGateway())

        val localhost = ModelSettings(
            backend = ModelBackend.REMOTE_OPENAI,
            baseUrl = "http://127.0.0.1:8080/v1",
            apiKey = "",
            modelName = "local-model"
        )
        assertTrue(localhost.validationErrors().isEmpty())
        assertNotNull(localhost.remoteGateway())
    }

    @Test
    fun jsonRoundTripPreservesFieldsWithoutLoggingKeyInSummary() {
        val original = ModelSettings(
            backend = ModelBackend.REMOTE_OPENAI,
            baseUrl = "https://example.com/v1/",
            apiKey = "sk-secret-value",
            modelName = "my-model",
            onboarded = true
        )
        val restored = ModelSettings.fromJson(ModelSettings.toJson(original))
        assertEquals(ModelBackend.REMOTE_OPENAI, restored.backend)
        assertEquals("https://example.com/v1", restored.baseUrl)
        assertEquals("", restored.apiKey)
        assertEquals("CODING_AGENT_MODEL_API_KEY", restored.apiKeyRef)
        assertEquals("my-model", restored.modelName)
        assertFalse(ModelSettings.toJson(original).contains("sk-secret-value"))
        assertTrue(restored.onboarded)
        assertFalse(restored.statusSummary().contains("sk-secret"))
    }

    @Test
    fun corruptJsonFallsBackToDefaults() {
        val defaults = ModelSettings.fromJson("{not-json")
        assertEquals(ModelBackend.LOCAL_NEXA, defaults.backend)
        assertFalse(defaults.onboarded)
    }
}
