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
        assertTrue(requesty.first().modelId.endsWith(":free"))
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
}
