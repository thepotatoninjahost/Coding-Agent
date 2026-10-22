package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelConnectionProbe
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.ModelSettings
import com.codingagent.model.ProbeResult

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
    fun remoteHttpRequiresHttpsAndDoesNotTrustHostnamePrefixes() {
        val insecure = ModelSettings(
            baseUrl = "http://example.com/v1",
            apiKey = "key",
            modelName = "model"
        )
        assertTrue(insecure.validationErrors().any { it.contains("HTTPS") })

        val lookalike = ModelSettings(
            baseUrl = "http://localhost.evil.example/v1",
            apiKey = "",
            modelName = "model"
        )
        assertTrue(lookalike.validationErrors().any { it.contains("HTTPS") || it.contains("API key") })

        val loopback = ModelSettings(
            baseUrl = "http://127.0.0.1:8080/v1",
            apiKey = "",
            modelName = "model"
        )
        assertTrue(loopback.validationErrors().isEmpty())

        val metadata = ModelSettings(
            baseUrl = "https://169.254.169.254/latest",
            apiKey = "key",
            modelName = "model"
        )
        assertTrue(metadata.validationErrors().any { it.contains("link-local") })

        val lan = ModelSettings(
            baseUrl = "https://192.168.1.20:11434/v1",
            apiKey = "key",
            modelName = "model"
        )
        assertTrue(lan.validationErrors().isEmpty())
    }

    @Test
    fun jsonRoundTripPreservesFieldsWithoutLoggingKeyInSummary() {
        val original = ModelSettings(
            backend = ModelBackend.REMOTE,
            baseUrl = "https://example.com/v1/",
            apiKey = "secret-value",
            modelName = "whatever-the-user-picked",
            systemPrompt = "Prefer concise Kotlin examples.",
            onboarded = true
        )
        val restored = ModelSettings.fromJson(ModelSettings.toJson(original))
        assertEquals(ModelBackend.REMOTE, restored.backend)
        assertEquals("https://example.com/v1", restored.baseUrl)
        assertEquals("secret-value", restored.apiKey)
        assertEquals("whatever-the-user-picked", restored.modelName)
        assertEquals("Prefer concise Kotlin examples.", restored.systemPrompt)
        assertTrue(restored.onboarded)
        assertFalse(restored.statusSummary().contains("secret-value"))
    }

    @Test
    fun corruptJsonFallsBackToOpenRouterFreeModelWithoutAKey() {
        val defaults = ModelSettings.fromJson("{not-json")
        assertEquals(ModelBackend.REMOTE, defaults.backend)
        assertEquals(ModelSettings.OPENROUTER_BASE_URL, defaults.baseUrl)
        assertEquals(ModelSettings.OPENROUTER_FREE_MODEL, defaults.modelName)
        assertTrue(defaults.apiKey.isEmpty())
        assertFalse(defaults.onboarded)
        assertFalse(defaults.isRemoteConfigured())
    }

    @Test
    fun legacyNonRemoteBackendJsonMapsToRemote() {
        // Old installs may still have a discarded local-backend label in JSON; always load as REMOTE.
        val legacy = """{"backend":"LOCAL_LEGACY","baseUrl":"","apiKey":"","modelName":"","onboarded":false}"""
        val restored = ModelSettings.fromJson(legacy)
        assertEquals(ModelBackend.REMOTE, restored.backend)
        assertFalse(restored.isRemoteConfigured())
        assertTrue(restored.statusSummary().contains("Helper not connected"))
    }

    @Test
    fun defaultsSelectOpenRouterFreeModelWithoutAKey() {
        val defaults = ModelSettings()
        assertEquals(ModelBackend.REMOTE, defaults.backend)
        assertEquals("https://openrouter.ai/api/v1", defaults.baseUrl)
        assertEquals(ModelSettings.OPENROUTER_FREE_MODEL, defaults.modelName)
        assertFalse(defaults.modelName == "openrouter/free")
        assertTrue(defaults.apiKey.isEmpty())
        assertFalse(defaults.onboarded)
        assertFalse(defaults.isRemoteConfigured())
        assertTrue(defaults.validationErrors().any { it.contains("openrouter.ai/keys") })
        assertTrue(defaults.statusSummary().contains("Helper not connected"))
        assertFalse(ModelSettings.toJson(defaults).contains("sk-"))
        assertFalse(defaults.statusSummary().contains("sk-"))
    }

    @Test
    fun blankSavedProviderBecomesOpenRouterFreeModel() {
        val restored = ModelSettings.fromJson("""{"baseUrl":"","apiKey":"","modelName":""}""")
        assertEquals(ModelSettings.OPENROUTER_BASE_URL, restored.baseUrl)
        assertEquals(ModelSettings.OPENROUTER_FREE_MODEL, restored.modelName)
        assertTrue(restored.rotationModels.contains("poolside/laguna-s-2.1:free"))
        assertFalse(restored.rotationModels.contains("liquid/lfm-2.5-2.6b"))
        assertTrue(restored.apiKey.isEmpty())
        assertFalse(restored.isRemoteConfigured())
    }

    @Test
    fun savedFreeRouterIsPinnedToOneCapableHelper() {
        val restored = ModelSettings.fromJson(
            """{"baseUrl":"https://openrouter.ai/api/v1","apiKey":"","modelName":"openrouter/free","rotationModels":""}"""
        )
        assertEquals(ModelSettings.OPENROUTER_FREE_MODEL, restored.modelName)
        assertTrue(restored.rotationModels.contains("google/gemma-4-31b-it:free"))
        assertFalse(restored.modelName.contains("2.6b"))
    }

    @Test
    fun savedCustomProviderIsNotReplacedWithOpenRouter() {
        val restored = ModelSettings.fromJson(
            """{"baseUrl":"https://example.com/v1","apiKey":"","modelName":"custom-model"}"""
        )
        assertEquals("https://example.com/v1", restored.baseUrl)
        assertEquals("custom-model", restored.modelName)
        assertTrue(restored.apiKey.isEmpty())
    }

    @Test
    fun probeReportsMissingOpenRouterKeyWithoutCallingTheNetwork() {
        var called = false
        val result = ModelConnectionProbe.probe(ModelSettings()) {
            called = true
            null
        }
        assertFalse(called)
        val failed = result as ProbeResult.Failed
        assertTrue(failed.reason.contains("secret password", ignoreCase = true))
        assertTrue(failed.reason.contains("openrouter.ai/keys"))
    }

    @Test
    fun probeExplainsRejectedSecretPasswordAndRateLimit() {
        val rejected = probeFailure("""Model HTTP 401: {"error":{"message":"Invalid API key"}}""")
        assertTrue(rejected.contains("secret password", ignoreCase = true))
        assertTrue(rejected.contains("openrouter.ai/keys"))
        assertFalse(rejected.contains("Invalid API key"))
        assertFalse(rejected.contains("{"))

        val limited = probeFailure("""Model HTTP 429: {"error":{"message":"Rate limit exceeded: free-models-per-day"}} X-RateLimit-Reset: 1893456000""")
        assertTrue(limited.contains("used up", ignoreCase = true))
        assertTrue(limited.contains("1,000"))
        assertTrue(limited.contains("$10"))
        assertFalse(limited.contains("slow down", ignoreCase = true))
        assertFalse(limited.contains("free-models-per-day"))
        assertFalse(limited.contains("{"))
    }

    private fun probeFailure(message: String): String {
        val result = ModelConnectionProbe.probe(
            ModelSettings(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = "not-a-real-key",
                modelName = "openrouter/free"
            ),
            gatewayFactory = {
                object : ModelGateway {
                    override fun complete(request: ModelRequest): ModelResponse =
                        ModelResponse.Failure(message)
                }
            }
        )
        return (result as ProbeResult.Failed).reason
    }
    @Test
    fun customSystemInstructionsAreAddedWithoutReplacingSafetyRules() {
        val settings = ModelSettings(systemPrompt = "Prefer concise Kotlin examples.").normalized()
        val prompt = settings.effectiveSystemPrompt()

        assertTrue(prompt.contains("Code changes (create_file, replace_text) only STAGE a proposal"))
        assertTrue(prompt.contains("Prefer concise Kotlin examples."))
        assertTrue(prompt.contains("do not override the built-in safety, evidence, or approval rules"))
    }

    @Test
    fun customSystemInstructionsArePreservedInFull() {
        val original = "owner instruction ".repeat(2_000)
        val normalized = ModelSettings(systemPrompt = original).normalized()
        assertEquals(original.trim(), normalized.systemPrompt)
    }


    @Test
    fun modelConnectionProbeRejectsEmptyTextResponse() {
        assertEquals(
            ProbeResult.Failed("Model returned no usable message content"),
            probeResponse(ModelResponse.Text(""))
        )
    }

    @Test
    fun modelConnectionProbeRejectsWhitespaceOnlyTextResponse() {
        assertEquals(
            ProbeResult.Failed("Model returned no usable message content"),
            probeResponse(ModelResponse.Text(" \t\n "))
        )
    }

    @Test
    fun modelConnectionProbeAcceptsNonBlankTextResponse() {
        assertEquals(
            ProbeResult.Ok("Reached model (pong)"),
            probeResponse(ModelResponse.Text("  pong  "))
        )
    }

    private fun probeResponse(response: ModelResponse): ProbeResult =
        ModelConnectionProbe.probe(
            ModelSettings(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                modelName = "test-model"
            ),
            gatewayFactory = {
                object : ModelGateway {
                    override fun complete(request: ModelRequest): ModelResponse = response
                }
            }
        )

}
