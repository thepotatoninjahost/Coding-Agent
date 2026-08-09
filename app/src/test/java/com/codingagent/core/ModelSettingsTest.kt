package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSettingsTest {
    @Test
    fun remoteRequiresUrlAndModelAndAllowsProvidersWithoutAnApiKey() {
        val incomplete = ModelSettings(
            baseUrl = "",
            apiKey = "",
            modelName = ""
        )
        assertTrue(incomplete.validationErrors().size >= 2)

        val remote = ModelSettings(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            modelName = "goliath-400b"
        )
        assertTrue(remote.validationErrors().isEmpty())
        assertTrue(remote.isConfigured())
        assertNotNull(remote.remoteGateway())

        val noAuthProvider = ModelSettings(
            baseUrl = "https://model.example.com/v1",
            apiKey = "",
            modelName = "open-model"
        )
        assertTrue(noAuthProvider.validationErrors().isEmpty())
        assertNotNull(noAuthProvider.remoteGateway())
    }

    @Test
    fun localhostProviderCanUseHttp() {
        val localhost = ModelSettings(
            baseUrl = "http://127.0.0.1:8080/v1",
            apiKey = "",
            modelName = "local-api-model"
        )
        assertTrue(localhost.validationErrors().isEmpty())
        assertNotNull(localhost.remoteGateway())
    }

    @Test
    fun jsonRoundTripPreservesConfigurationWithoutPersistingKey() {
        val original = ModelSettings(
            baseUrl = "https://example.com/v1/",
            apiKey = "sk-secret-value",
            modelName = "goliath-400b",
            onboarded = true
        )
        val serialized = ModelSettings.toJson(original)
        val restored = ModelSettings.fromJson(serialized)
        assertEquals(ModelBackend.REMOTE_OPENAI, restored.backend)
        assertEquals("https://example.com/v1", restored.baseUrl)
        assertEquals("", restored.apiKey)
        assertEquals("CODING_AGENT_MODEL_API_KEY", restored.apiKeyRef)
        assertEquals("goliath-400b", restored.modelName)
        assertFalse(serialized.contains("sk-secret-value"))
        assertTrue(restored.onboarded)
        assertFalse(restored.statusSummary().contains("sk-secret"))
    }

    @Test
    fun legacyLocalSettingsAreNotReactivated() {
        val restored = ModelSettings.fromJson("{\"backend\":\"LOCAL_OLD_PROVIDER\",\"onboarded\":true}")
        assertEquals(ModelBackend.REMOTE_OPENAI, restored.backend)
        assertFalse(restored.onboarded)
        assertTrue(restored.validationErrors().isNotEmpty())
    }

    @Test
    fun corruptJsonFallsBackToUnconfiguredRemoteDefaults() {
        val defaults = ModelSettings.fromJson("{not-json")
        assertEquals(ModelBackend.REMOTE_OPENAI, defaults.backend)
        assertFalse(defaults.onboarded)
        assertTrue(defaults.validationErrors().isNotEmpty())
    }
}
