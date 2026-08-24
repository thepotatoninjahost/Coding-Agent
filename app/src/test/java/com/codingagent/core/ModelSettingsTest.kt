package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelSettings

class ModelSettingsTest {
    @Test
    fun remoteRequiresUrlModelAndKeyUnlessLocalhost() {
        val incomplete = ModelSettings(
            backend = ModelBackend.REMOTE,
            baseUrl = "",
            apiKey = "",
            modelName = ""
        )
        assertTrue(incomplete.validationErrors().size >= 2)

        val remote = ModelSettings(
            backend = ModelBackend.REMOTE,
            baseUrl = "https://example.com/v1",
            apiKey = "key-test",
            modelName = "user-chosen-model"
        )
        assertTrue(remote.validationErrors().isEmpty())
        assertTrue(remote.isRemoteConfigured())
        assertNotNull(remote.remoteGateway())

        val localhost = ModelSettings(
            backend = ModelBackend.REMOTE,
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
            backend = ModelBackend.REMOTE,
            baseUrl = "https://example.com/v1/",
            apiKey = "secret-value",
            modelName = "whatever-the-user-picked",
            onboarded = true
        )
        val restored = ModelSettings.fromJson(ModelSettings.toJson(original))
        assertEquals(ModelBackend.REMOTE, restored.backend)
        assertEquals("https://example.com/v1", restored.baseUrl)
        assertEquals("secret-value", restored.apiKey)
        assertEquals("whatever-the-user-picked", restored.modelName)
        assertTrue(restored.onboarded)
        assertFalse(restored.statusSummary().contains("secret-value"))
    }

    @Test
    fun corruptJsonFallsBackToEmptyRemoteDefaults() {
        val defaults = ModelSettings.fromJson("{not-json")
        assertEquals(ModelBackend.REMOTE, defaults.backend)
        assertTrue(defaults.baseUrl.isEmpty())
        assertTrue(defaults.modelName.isEmpty())
        assertTrue(defaults.apiKey.isEmpty())
        assertFalse(defaults.onboarded)
    }

    @Test
    fun legacyNonRemoteBackendJsonMapsToRemote() {
        // Old installs may still have a discarded local-backend label in JSON; always load as REMOTE.
        val legacy = """{"backend":"LOCAL_LEGACY","baseUrl":"","apiKey":"","modelName":"","onboarded":false}"""
        val restored = ModelSettings.fromJson(legacy)
        assertEquals(ModelBackend.REMOTE, restored.backend)
        assertFalse(restored.isRemoteConfigured())
        assertTrue(restored.statusSummary().contains("Remote"))
    }

    @Test
    fun defaultsDoNotHardcodeProviderOrModel() {
        val defaults = ModelSettings()
        assertEquals(ModelBackend.REMOTE, defaults.backend)
        assertTrue(defaults.baseUrl.isEmpty())
        assertTrue(defaults.modelName.isEmpty())
        assertTrue(defaults.apiKey.isEmpty())
        assertFalse(defaults.onboarded)
        assertFalse(defaults.isRemoteConfigured())
        assertTrue(defaults.statusSummary().contains("Remote"))
    }
}
