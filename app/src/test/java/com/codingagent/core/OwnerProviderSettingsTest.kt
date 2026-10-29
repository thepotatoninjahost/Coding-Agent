package com.codingagent.core

import com.codingagent.agent.ModelFailure
import com.codingagent.model.ModelSettings
import com.codingagent.model.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerProviderSettingsTest {
    private val accountId = "0123456789abcdef0123456789abcdef"
    private val cloudflareToken = "test-cloudflare-token"
    private val requestyKey = "test-requesty-secret"
    private val aimlKey = "test-aiml-secret"
    private val groqKey = "test-groq-secret"
    private val openRouterKey = "test-openrouter-secret"

    @Test
    fun eachOwnerProviderKeepsItsOwnAddressAndHelper() {
        val settings = ModelSettings(
            apiKey = openRouterKey,
            groqApiKey = groqKey,
            requestyApiKey = "  $requestyKey\n",
            aimlApiKey = aimlKey,
            cloudflareApiToken = cloudflareToken,
            cloudflareAccountId = accountId.uppercase() + " "
        )
        val endpoints = ProviderCatalog.endpoints(settings)

        val openRouter = endpoints.filter { it.providerId == "openrouter" }
        assertTrue(openRouter.isNotEmpty())
        assertTrue(openRouter.all { it.baseUrl == "https://openrouter.ai/api/v1" })
        assertTrue(openRouter.all { it.apiKey == openRouterKey })

        val groq = endpoints.filter { it.providerId == "groq" }
        assertTrue(groq.isNotEmpty())
        assertTrue(groq.all { it.baseUrl == "https://api.groq.com/openai/v1" })
        assertTrue(groq.all { it.apiKey == groqKey })

        val requesty = endpoints.filter { it.providerId == "requesty" }
        assertTrue(requesty.size >= 2)
        assertTrue(requesty.all { it.baseUrl == "https://router.requesty.ai/v1" })
        assertTrue(requesty.any { it.modelId == "nvidia/nemotron-3-ultra-550b-a55b" })
        assertTrue(requesty.any { it.modelId == "google/gemma-4-31b-it" })
        assertTrue(requesty.none { it.modelId.endsWith(":free") })
        assertTrue(requesty.none { it.modelId.contains("cohere") })
        assertTrue(requesty.none { it.modelId == "anthropic/claude-fable-5" })
        assertTrue(requesty.all { it.apiKey == requestyKey })

        val aiml = endpoints.filter { it.providerId == "aiml" }.single()
        assertEquals("https://api.aimlapi.com/v1", aiml.baseUrl)
        assertEquals("stealth/union-alpha", aiml.modelId)
        assertEquals(ProviderCatalog.AIML_MODEL, aiml.modelId)
        assertEquals(aimlKey, aiml.apiKey)

        val cloudflare = endpoints.filter { it.providerId == "cloudflare" }.single()
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1",
            cloudflare.baseUrl
        )
        assertEquals("@cf/openai/gpt-oss-120b", cloudflare.modelId)
        assertEquals(cloudflareToken, cloudflare.apiKey)
        assertFalse(cloudflare.baseUrl.contains(".."))
        assertFalse(cloudflare.baseUrl.contains(" "))

        val status = settings.statusSummary()
        assertFalse(status.contains(openRouterKey))
        assertFalse(status.contains(groqKey))
        assertFalse(status.contains(requestyKey))
        assertFalse(status.contains(aimlKey))
        assertFalse(status.contains(cloudflareToken))
        assertFalse(status.contains(accountId))
        val shown = ModelFailure.humanize(
            "Helper said no. Bearer $cloudflareToken at https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1"
        )
        assertFalse(shown.contains(cloudflareToken))
        assertFalse(shown.contains(accountId))
        assertTrue(shown.contains("[account]"))
        assertTrue(shown.contains("[redacted]"))

        val restored = ModelSettings.fromJson(ModelSettings.toJson(settings))
        assertEquals(requestyKey, restored.requestyApiKey)
        assertEquals(aimlKey, restored.aimlApiKey)
        assertEquals(cloudflareToken, restored.cloudflareApiToken)
        assertEquals(accountId, restored.cloudflareAccountId)
        assertFalse(restored.statusSummary().contains(cloudflareToken))
        assertFalse(restored.statusSummary().contains(accountId))
    }

    @Test
    fun cloudflareAccountIdMustBeTheLongCodeBeforeItIsUsed() {
        assertNull(ProviderCatalog.cloudflareBaseUrl("../evil"))
        assertNull(ProviderCatalog.cloudflareBaseUrl("0123456789abcdef0123456789abcdef/extra"))
        assertNull(ProviderCatalog.cloudflareBaseUrl("not-an-id"))
        assertNull(ProviderCatalog.cloudflareBaseUrl(""))

        val tokenOnly = ModelSettings(cloudflareApiToken = cloudflareToken, cloudflareAccountId = "../evil")
        assertTrue(ProviderCatalog.endpoints(tokenOnly).none { it.providerId == "cloudflare" })
        assertTrue(ProviderCatalog.endpoints(tokenOnly).none { it.baseUrl.contains("..") || it.baseUrl.contains("/evil") })
        assertTrue(tokenOnly.validationErrors().any { it.contains("account id") })
        assertFalse(tokenOnly.validationErrors().joinToString(" ").contains(cloudflareToken))
        assertFalse(tokenOnly.statusSummary().contains(cloudflareToken))
        assertNull(tokenOnly.remoteGateway())

        val openRouterStillWorks = ModelSettings(
            apiKey = openRouterKey,
            cloudflareApiToken = cloudflareToken,
            cloudflareAccountId = "not-an-id"
        )
        val helpers = ProviderCatalog.endpoints(openRouterStillWorks)
        assertTrue(helpers.isNotEmpty())
        assertTrue(helpers.all { it.providerId == "openrouter" })
        assertTrue(helpers.all { it.baseUrl == ModelSettings.OPENROUTER_BASE_URL })
        assertTrue(openRouterStillWorks.validationErrors().isEmpty())
        assertNotNull(openRouterStillWorks.remoteGateway())
        val note = openRouterStillWorks.statusSummary()
        assertTrue(note.contains("Cloudflare still needs the account id"))
        assertFalse(note.contains(cloudflareToken))
        assertFalse(note.contains("not-an-id"))
    }

    @Test
    fun groqPasswordAloneConnectsAndIsTheOnlyReadyHelper() {
        val groqOnly = ModelSettings(
            baseUrl = ProviderCatalog.GROQ_URL,
            apiKey = "",
            modelName = "openai/gpt-oss-120b",
            groqApiKey = groqKey
        )
        assertTrue(groqOnly.validationErrors().isEmpty())
        assertTrue(groqOnly.isRemoteConfigured())
        assertNotNull(groqOnly.remoteGateway())
        val names = ProviderCatalog.providerNames(groqOnly)
        assertEquals(listOf("Groq"), names)
        val status = groqOnly.statusSummary()
        assertTrue(status.contains("Helper ready: Groq"))
        assertFalse(status.contains("OpenRouter"))
        assertFalse(status.contains("not connected"))
        assertFalse(status.contains("openrouter.ai/keys"))
        val endpoints = ProviderCatalog.endpoints(groqOnly)
        assertTrue(endpoints.isNotEmpty())
        assertTrue(endpoints.all { it.providerId == "groq" })
        assertTrue(endpoints.all { it.baseUrl == ProviderCatalog.GROQ_URL })
        assertTrue(endpoints.all { it.apiKey == groqKey })
        assertTrue(endpoints.any { it.modelId == "openai/gpt-oss-120b" })
    }

    @Test
    fun aGroqKeyPastedIntoTheMainBoxDoesNotInventOpenRouter() {
        val duplicated = ModelSettings(
            baseUrl = ProviderCatalog.GROQ_URL,
            apiKey = groqKey,
            modelName = "openai/gpt-oss-120b",
            groqApiKey = groqKey
        )
        assertEquals(listOf("Groq"), ProviderCatalog.providerNames(duplicated))
        assertFalse(duplicated.statusSummary().contains("OpenRouter"))
        assertTrue(duplicated.statusSummary().contains("Groq"))
        assertTrue(ProviderCatalog.endpoints(duplicated).none { it.providerId == "openrouter" })

        val mainBoxOnly = duplicated.copy(groqApiKey = "")
        assertEquals(listOf("Groq"), ProviderCatalog.providerNames(mainBoxOnly))
        assertTrue(ProviderCatalog.endpoints(mainBoxOnly).all { it.apiKey == groqKey && it.providerId == "groq" })
        assertFalse(mainBoxOnly.statusSummary().contains("OpenRouter"))
    }
}
