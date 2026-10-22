package com.codingagent.model

/**
 * Free helpers the phone can call with an OpenAI-shaped API.
 *
 * Checked on 22 October 2026. GitHub Models was retired on 30 July 2026, so it has no key slot.
 * Cerebras is only a short trial that needs a card. The others below have a standing free tier
 * or a monthly credit that does not need a card.
 *
 * Coding order uses public boards, not a guess:
 * - gpt-oss-120b: LiveCodeBench, October 2026, 87.8 percent (modelgrep).
 * - Gemini 3.8 Flash: BenchLM coding, 20 October 2026, 60.9.
 * - Gemini 3.6 Flash: BenchLM coding, 20 October 2026, 51.0.
 * - Qwen3.8 27B: BenchLM coding, 20 October 2026, 46.5.
 * MiniMax M3.1 Flash's published 61.3 is for the paid id. The free OpenRouter id is the same family,
 * so it follows the models whose score is for the exact free id.
 */
object ProviderCatalog {
    const val GROQ_URL = "https://api.groq.com/openai/v1"
    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val MISTRAL_URL = "https://api.mistral.ai/v1"
    const val SAMBANOVA_URL = "https://api.sambanova.ai/v1"
    const val CEREBRAS_URL = "https://api.cerebras.ai/v1"
    const val REQUESTY_URL = "https://router.requesty.ai/v1"
    const val AIML_URL = "https://api.aimlapi.com/v1"
    const val AIML_MODEL = "stealth/union-alpha"
    private val CLOUDFLARE_ACCOUNT_ID = Regex("^[a-f0-9]{32}$")

    /** The account id is the 32-character code from the Cloudflare account page, not the token. */
    fun cloudflareBaseUrl(accountId: String): String? {
        val id = accountId.trim().lowercase()
        if (!CLOUDFLARE_ACCOUNT_ID.matches(id)) return null
        return "https://api.cloudflare.com/client/v4/accounts/$id/ai/v1"
    }

    data class RankedModel(
        val providerId: String,
        val modelId: String,
        val plainName: String,
        val why: String
    )

    val bundled: List<RankedModel> = listOf(
        RankedModel(
            "groq",
            "openai/gpt-oss-120b",
            "Groq large code helper",
            "LiveCodeBench in October 2026 listed gpt-oss-120b at 87.8 percent. Groq's free plan allows about 1,000 requests a day for this model."
        ),
        RankedModel(
            "cloudflare",
            "@cf/openai/gpt-oss-120b",
            "Cloudflare code helper",
            "Same open model as the Groq helper. Cloudflare's chat endpoint documents this id, and the address is built from the account id."
        ),
        RankedModel(
            "gemini",
            "gemini-3.8-flash",
            "Google Gemini",
            "BenchLM's coding board on 20 October 2026 scored Gemini 3.8 Flash at 60.9. Google's free tier includes this model."
        ),
        RankedModel(
            "gemini",
            "gemini-3.6-flash",
            "Google Gemini smaller helper",
            "BenchLM's coding board on 20 October 2026 scored Gemini 3.6 Flash at 51.0."
        ),
        RankedModel(
            "groq",
            "qwen/qwen3.8-27b",
            "Groq Qwen helper",
            "BenchLM's coding board on 20 October 2026 scored Qwen3.8 27B at 46.5. Groq serves it on the free plan."
        ),
        RankedModel(
            "requesty",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "Requesty free helper",
            "Requesty answers free models and refuses paid ones. The :free id is tried first."
        ),
        RankedModel(
            "requesty",
            "cohere/north-mini-code:free",
            "Requesty code helper",
            "Another free Requesty model. Paid Requesty models are refused, so they are not listed."
        ),
        RankedModel(
            "requesty",
            "google/gemma-4-31b-it:free",
            "Requesty Gemma helper",
            "A free Requesty model used when the first one is busy."
        ),
        RankedModel(
            "aiml",
            "stealth/union-alpha",
            "AI/ML API helper",
            "This is the helper used on AI/ML API. Their page shows the id stealth/union-alpha at api.aimlapi.com."
        ),
        RankedModel(
            "openrouter",
            "minimax/minimax-m3.1-flash:free",
            "MiniMax Flash",
            "The paid MiniMax M3.1 Flash was rank 17 on BenchLM coding (20 October 2026). This is the free OpenRouter id. The published score is for the paid id."
        ),
        RankedModel(
            "openrouter",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "NVIDIA Nemotron Ultra",
            "Largest free NVIDIA model listed on OpenRouter on 22 October 2026. No separate public coding score was found for this free id."
        ),
        RankedModel(
            "openrouter",
            "cohere/north-mini-code:free",
            "Cohere code helper",
            "Free on OpenRouter and already used here for tool calls. No October 2026 leaderboard row was found."
        ),
        RankedModel(
            "openrouter",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "NVIDIA Nemotron Super",
            "Free on OpenRouter on 22 October 2026. Smaller than Nemotron Ultra."
        ),
        RankedModel(
            "groq",
            "openai/gpt-oss-20b",
            "Groq smaller code helper",
            "Smaller sibling of gpt-oss-120b on Groq's free plan."
        ),
        RankedModel(
            "mistral",
            "mistral-medium-latest",
            "Mistral medium",
            "Mistral's free plan gives monthly API credit and does not need a card. No October 2026 coding rank was found for this id."
        ),
        RankedModel(
            "openrouter",
            "google/gemma-4-31b-it:free",
            "Google Gemma",
            "Free on OpenRouter on 22 October 2026."
        ),
        RankedModel(
            "sambanova",
            "DeepSeek-V3.1",
            "SambaNova DeepSeek",
            "SambaNova's free tier listed DeepSeek-V3.1 on 22 October 2026. The published limit is about 20 requests a day."
        ),
        RankedModel(
            "mistral",
            "mistral-small-latest",
            "Mistral small",
            "A smaller Mistral model on the same monthly credit."
        ),
        RankedModel(
            "openrouter",
            "poolside/laguna-s-2.1:free",
            "Poolside code helper",
            "Still listed free on OpenRouter on 22 October 2026. OpenRouter shows it leaving on 31 October 2026, so it is last."
        ),
        RankedModel(
            "cerebras",
            "gpt-oss-120b",
            "Cerebras trial",
            "Cerebras does not have a lasting free tier. A new account can get a short trial that needs a card."
        )
    )

    fun endpoints(settings: ModelSettings): List<ProviderEndpoint> {
        val s = settings.normalized()
        val out = mutableListOf<ProviderEndpoint>()
        if (s.apiKey.isNotBlank() && s.baseUrl.contains("openrouter.ai")) {
            bundled.filter { it.providerId == "openrouter" }.forEach { model ->
                out += ProviderEndpoint("openrouter", model.plainName, model.modelId, s.baseUrl, s.apiKey)
            }
            if (s.modelName.isNotBlank() && out.none { it.modelId == s.modelName }) {
                out += ProviderEndpoint("openrouter", "Chosen helper", s.modelName, s.baseUrl, s.apiKey)
            }
        } else if (s.baseUrl.isNotBlank() && s.modelName.isNotBlank() &&
            (s.apiKey.isNotBlank() || ModelEndpointPolicy.isLocalEndpoint(s.baseUrl))
        ) {
            out += ProviderEndpoint("custom", "Your helper", s.modelName, s.baseUrl, s.apiKey)
        }
        fun add(providerId: String, key: String, baseUrl: String) {
            if (key.isBlank()) return
            bundled.filter { it.providerId == providerId }.forEach { model ->
                out += ProviderEndpoint(providerId, model.plainName, model.modelId, baseUrl, key)
            }
        }
        add("groq", s.groqApiKey, GROQ_URL)
        add("gemini", s.geminiApiKey, GEMINI_URL)
        add("mistral", s.mistralApiKey, MISTRAL_URL)
        add("sambanova", s.sambanovaApiKey, SAMBANOVA_URL)
        add("cerebras", s.cerebrasApiKey, CEREBRAS_URL)
        add("requesty", s.requestyApiKey, REQUESTY_URL)
        add("aiml", s.aimlApiKey, AIML_URL)
        cloudflareBaseUrl(s.cloudflareAccountId)?.let { url ->
            add("cloudflare", s.cloudflareApiToken, url)
        }
        return out.distinctBy { it.providerId + "\n" + it.modelId }
    }

    fun providerNames(settings: ModelSettings): List<String> {
        val present = endpoints(settings).map { it.providerId }.distinct()
        return present.map { id ->
            when (id) {
                "openrouter" -> "OpenRouter"
                "groq" -> "Groq"
                "gemini" -> "Google Gemini"
                "mistral" -> "Mistral"
                "sambanova" -> "SambaNova"
                "cerebras" -> "Cerebras"
                "requesty" -> "Requesty"
                "aiml" -> "AI/ML API"
                "cloudflare" -> "Cloudflare"
                else -> "Your helper"
            }
        }
    }
}

data class ProviderEndpoint(
    val providerId: String,
    val plainName: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String
)
