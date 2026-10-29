package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.ModelFailure
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelConnectionProbe
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.ModelSettings
import com.codingagent.model.OpenRouterFreeModels
import com.codingagent.model.ProbeResult
import com.codingagent.model.ProviderCatalog
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

class ModelSettingsTest {
    private val FREE_MODELS_JSON = """
        {"data":[
          {"id":"minimax/minimax-m3.1-flash:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":2000000},
          {"id":"nvidia/nemotron-3-ultra-550b-a55b:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":128000},
          {"id":"cohere/north-mini-code:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":256000}
        ]}
    """.trimIndent()

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
        assertEquals("", defaults.modelName)
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
        assertEquals("", defaults.modelName)
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
        assertEquals("", restored.modelName)
        assertFalse(restored.rotationModels.contains("poolside/laguna-s-2.1:free"))
        assertFalse(restored.rotationModels.contains("liquid/lfm-2.5-2.6b"))
        assertTrue(restored.apiKey.isEmpty())
        assertFalse(restored.isRemoteConfigured())
    }

    @Test
    fun savedFreeRouterIsPinnedToOneCapableHelper() {
        val restored = ModelSettings.fromJson(
            """{"baseUrl":"https://openrouter.ai/api/v1","apiKey":"","modelName":"openrouter/free","rotationModels":""}"""
        )
        assertEquals("", restored.modelName)
        assertFalse(restored.modelName.contains("cohere"))
        assertFalse(restored.rotationModels.contains("google/gemma-4-31b-it:free"))
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
    fun retiredMiniMaxIsNotADefaultAndASavedChoiceIsReplaced() {
        assertTrue(ProviderCatalog.bundled.none { it.modelId.contains("minimax") })
        val saved = ModelSettings.fromJson(
            """{"baseUrl":"https://openrouter.ai/api/v1","apiKey":"test-key","modelName":"minimax/minimax-m3.1-flash:free","preferredModel":"minimax/minimax-m3.1-flash:free","rotationModels":"minimax/minimax-m3.1-flash:free, cohere/north-mini-code:free"}"""
        )
        assertFalse(saved.modelName.contains("minimax"))
        assertFalse(saved.preferredModel.contains("minimax"))
        assertFalse(saved.rotationModels.contains("minimax"))
        assertEquals("", saved.modelName)
        assertTrue(saved.rotationModels.contains("cohere/north-mini-code:free"))
    }

    @Test
    fun freeOpenRouterModelsComeFromTheLiveListAndSkipARetiredOne() {
        val ranked = OpenRouterFreeModels.rankJson(
            """
            {"data":[
              {"id":"paid/model","pricing":{"prompt":"0.15","completion":"0"},"supported_parameters":["tools"],"context_length":99999},
              {"id":"minimax/minimax-m3.1-flash:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":2000000},
              {"id":"no-tools/free","pricing":{"prompt":"0","completion":"0"},"context_length":900000},
              {"id":"cohere/north-mini-code:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":1000},
              {"id":"big/tools:free","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"],"context_length":800000}
            ]}
            """.trimIndent(),
        )
        assertEquals("big/tools:free", ranked[0])
        assertEquals("cohere/north-mini-code:free", ranked[1])
        assertTrue(ranked.contains("no-tools/free"))
        assertTrue(ranked.indexOf("no-tools/free") > ranked.indexOf("big/tools:free"))
        assertFalse(ranked.any { it.contains("minimax") || it.startsWith("paid/") })
    }

    @Test
    fun setupSkipsTheRetiredMiniMax404AndStillConnects() {
        OpenRouterFreeModels.clearCache()
        val chats = AtomicInteger()
        val exact = "The free launch period for MiniMax M3.1 Flash ended on Oct 22, 2026. " +
            "The ':free' variant has been retired; use 'minimax/minimax-m3.1-flash' " +
            "(list price: \$0.15/M input, \$0.60/M output) instead."
        val plain = ModelFailure.humanize("Model HTTP 404: $exact")
        assertFalse(plain.contains("Model HTTP"))
        assertFalse(plain.contains("404"))
        assertFalse(plain.contains("0.15"))
        assertTrue(plain.contains("no longer available"))

        val settings = ModelSettings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "test-key",
            modelName = "cohere/north-mini-code:free"
        )
        val result = ModelConnectionProbe.probe(settings) { candidate ->
            candidate.remoteGateway(
                timeoutMillis = 5_000,
                quotaFile = java.io.File.createTempFile("quota", ".json"),
                probe = true,
                connectionFactory = { url ->
                    if (url.contains("/key")) {
                        scriptedConnection(200, """{"data":{}}""")
                    } else if (url.endsWith("/models")) {
                        scriptedConnection(200, FREE_MODELS_JSON)
                    } else {
                        if (chats.incrementAndGet() == 1) {
                            scriptedConnection(404, """{"error":{"message":${org.json.JSONObject.quote(exact)},"code":404}}""")
                        } else {
                            scriptedConnection(
                                200,
                                "data: {\"choices\":[{\"delta\":{\"content\":\"pong\"}}]}\n\n" +
                                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                                    "data: [DONE]\n\n"
                            )
                        }
                    }
                }
            )
        }
        assertTrue(chats.get() >= 2)
        assertTrue(result is ProbeResult.Ok)
        val detail = (result as ProbeResult.Ok).detail
        assertTrue(detail.contains("Connected"))
        assertFalse(detail.contains("Model HTTP"))
        assertFalse(detail.contains("404"))
        assertFalse(detail.contains("MiniMax"))
        assertFalse(detail.contains("0.15"))
    }

    @Test
    fun modelConnectionProbeAcceptsNonBlankTextResponse() {
        assertEquals(
            ProbeResult.Ok("Connected. test-model answered."),
            probeResponse(ModelResponse.Text("  pong  "))
        )
    }

    private fun scriptedConnection(code: Int, body: String): HttpURLConnection =
        object : HttpURLConnection(java.net.URL("https://openrouter.ai/api/v1/chat/completions")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = code
            override fun getInputStream() = body.byteInputStream()
            override fun getErrorStream() = if (code in 200..299) null else body.byteInputStream()
            override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun getHeaderField(name: String?): String? = null
            override fun getPermission() = null
            override fun getContent() = null
            override fun getContentLength() = -1
            override fun getResponseMessage() = if (code == 404) "Not Found" else "OK"
            override fun getRequestMethod() = "POST"
            override fun setRequestMethod(method: String?) = Unit
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
