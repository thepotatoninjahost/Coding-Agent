package com.codingagent.model

import com.codingagent.agent.ModelFailure
import com.codingagent.core.LocalStore
import java.net.InetAddress
import java.net.URI

/**
 * ONE JOB: User-facing model gateway configuration (base URL, model id, API key,
 * optional rotation fallbacks). Pure data so unit tests do not need Android;
 * persistence lives in [LocalStore].
 */
enum class ModelBackend {
    /** Remote HTTP model endpoint (tools + optional SSE). */
    REMOTE
}

object ModelEndpointPolicy {
    fun validate(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return "The web address is missing."
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return "That web address is not valid."
        val scheme = uri.scheme?.lowercase()
            ?: return "The web address must start with http:// or https://."
        if (scheme != "http" && scheme != "https") return "The web address must use http:// or https://."
        if (uri.userInfo != null) return "Do not put a password inside the web address."
        if (uri.host.isNullOrBlank()) return "The web address needs a site name."
        if (scheme == "http" && !isLocalEndpoint(value)) {
            return "A helper on the internet must use HTTPS. HTTP is allowed only for a helper on this phone."
        }
        dangerousLiteralHost(uri.host)?.let { return it }
        return null
    }

    fun isLocalEndpoint(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    }

    /**
     * Public hostnames must not resolve to a private or link-local address before the API key
     * is sent. A literal private address is allowed because the owner typed that machine.
     */
    fun refuseResolvedDestination(raw: String): String? {
        validate(raw)?.let { return it }
        if (isLocalEndpoint(raw)) return null
        val host = URI(raw.trim()).host?.trim()?.trim('[', ']') ?: return "Base URL must contain a hostname"
        if (isLiteralIp(host)) return null
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (error: Exception) {
            return "That site name could not be found: ${error.message ?: "lookup failed"}"
        }
        if (addresses.isEmpty()) return "That site name could not be found."
        if (addresses.any(::blocksResolvedAddress)) {
            return "That site name points at a private or link-local address. Your secret password was not sent. If you mean a computer in your home, type its address directly."
        }
        return null
    }

    private fun dangerousLiteralHost(host: String?): String? {
        val normalized = host?.trim()?.trim('[', ']')?.lowercase() ?: return null
        if (normalized == "metadata.google.internal" || normalized.endsWith(".metadata.google.internal")) {
            return "That web address is blocked."
        }
        if (!isLiteralIp(normalized)) return null
        val address = runCatching { InetAddress.getByName(normalized) }.getOrNull()
            ?: return "That web address is not valid."
        if (address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) {
            return "That web address uses a link-local or blocked address, so it was refused."
        }
        return null
    }

    private fun isLiteralIp(host: String): Boolean {
        val normalized = host.trim('[', ']')
        if (normalized.contains(':')) return true
        return normalized.split('.').size == 4 && normalized.split('.').all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun blocksResolvedAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 16 &&
            bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() &&
            bytes[11] == 0xff.toByte()
        ) {
            return blocksResolvedAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
    }
}

data class ModelSettings(
    val backend: ModelBackend = ModelBackend.REMOTE,
    val baseUrl: String = OPENROUTER_BASE_URL,
    val apiKey: String = "",
    val modelName: String = OPENROUTER_FREE_MODEL,
    /**
     * Optional ordered fallback model IDs that share the same base URL and API key.
     * Comma, semicolon, or newline separated. When the primary model returns a
     * rate-limit / capacity / overfill error, [RotatingModelGateway] automatically
     * tries the next id in this list. The free helper starts on one model and
     * switches only after that kind of error.
     */
    val rotationModels: String = "",
    val systemPrompt: String = "",
    /** Optional extra HTTP headers, one per line: "Header-Name: value" */
    val extraHeaders: String = "",
    val onboarded: Boolean = false,
    /** "capped" drops old low-importance memories. "forever" keeps them. */
    val memoryRetention: String = MEMORY_KEEP_CAPPED,
    val githubRepo: String = DEFAULT_GITHUB_REPO,
    val githubBranch: String = "main",
    /** Blank means the phone picks the best helper that is still available. */
    val preferredModel: String = "",
    val groqApiKey: String = "",
    val geminiApiKey: String = "",
    val mistralApiKey: String = "",
    val cerebrasApiKey: String = "",
    val sambanovaApiKey: String = "",
    val requestyApiKey: String = "",
    val aimlApiKey: String = "",
    val cloudflareApiToken: String = "",
    val cloudflareAccountId: String = ""
) {
    fun normalized(): ModelSettings = copy(
        backend = ModelBackend.REMOTE,
        baseUrl = baseUrl.trim().trimEnd('/'),
        // API keys never contain whitespace. Phone paste often injects newlines mid-key;
        // those break HTTP Authorization headers (Unexpected char 0x0a) and must be stripped.
        apiKey = apiKey.filterNot { it.isWhitespace() },
        modelName = modelName.trim(),
        rotationModels = rotationModels.trim(),
        systemPrompt = systemPrompt.trim(),
        extraHeaders = extraHeaders.trim(),
        memoryRetention = if (memoryRetention.trim().equals(MEMORY_KEEP_FOREVER, ignoreCase = true)) {
            MEMORY_KEEP_FOREVER
        } else {
            MEMORY_KEEP_CAPPED
        },
        githubRepo = githubRepo.trim().ifBlank { DEFAULT_GITHUB_REPO },
        githubBranch = githubBranch.trim().ifBlank { "main" },
        preferredModel = preferredModel.trim(),
        groqApiKey = groqApiKey.filterNot { it.isWhitespace() },
        geminiApiKey = geminiApiKey.filterNot { it.isWhitespace() },
        mistralApiKey = mistralApiKey.filterNot { it.isWhitespace() },
        cerebrasApiKey = cerebrasApiKey.filterNot { it.isWhitespace() },
        sambanovaApiKey = sambanovaApiKey.filterNot { it.isWhitespace() },
        requestyApiKey = requestyApiKey.filterNot { it.isWhitespace() },
        aimlApiKey = aimlApiKey.filterNot { it.isWhitespace() },
        cloudflareApiToken = cloudflareApiToken.filterNot { it.isWhitespace() },
        cloudflareAccountId = cloudflareAccountId.filterNot { it.isWhitespace() }.lowercase()
    )

    fun hasAnyProviderKey(): Boolean {
        val s = normalized()
        return s.apiKey.isNotBlank() || s.groqApiKey.isNotBlank() || s.geminiApiKey.isNotBlank() ||
            s.mistralApiKey.isNotBlank() || s.cerebrasApiKey.isNotBlank() || s.sambanovaApiKey.isNotBlank() ||
            s.requestyApiKey.isNotBlank() || s.aimlApiKey.isNotBlank() || s.cloudflareApiToken.isNotBlank() ||
            s.cloudflareAccountId.isNotBlank()
    }

    /** Plain sentence when Cloudflare was started but the address cannot be built. Blank when it is unused or ready. */
    fun cloudflareSetupNote(): String {
        val s = normalized()
        val token = s.cloudflareApiToken.isNotBlank()
        val ready = ProviderCatalog.cloudflareBaseUrl(s.cloudflareAccountId) != null
        return when {
            !token && s.cloudflareAccountId.isBlank() -> ""
            !token -> "Cloudflare still needs the token. It stays on this phone."
            !ready -> "Cloudflare still needs the account id. It is the long code on your Cloudflare account page, not the token."
            else -> ""
        }
    }

    /** Primary model plus distinct fallbacks, order preserved. */
    fun allModelIds(): List<String> {
        val s = normalized()
        val primary = s.modelName.takeIf { it.isNotBlank() }
        val fallbacks = s.rotationModels
            .split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return (listOfNotNull(primary) + fallbacks).distinct()
    }

    /** Parse user-supplied extra headers. Lines: "Name: value" or "Name=value". */
    fun parsedExtraHeaders(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (raw in normalized().extraHeaders.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val sep = when {
                ":" in line -> ":"
                "=" in line -> "="
                else -> continue
            }
            val idx = line.indexOf(sep)
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isBlank() || value.isBlank()) continue
            if (name.any { it.isISOControl() } || value.any { it.isISOControl() }) continue
            out[name] = value
        }
        return out
    }

    /**
     * Preserve the built-in tool, evidence, and approval rules. Owner text is additional
     * guidance and must not replace the safety-critical base instructions.
     */
    fun effectiveSystemPrompt(): String {
        val base = AgentModelProtocol.DEFAULT_SYSTEM
        val additional = normalized().systemPrompt
        if (additional.isBlank()) return base
        return "$base\n\nOWNER-PROVIDED ADDITIONAL INSTRUCTIONS (do not override the built-in safety, evidence, or approval rules):\n$additional"
    }

    fun validationErrors(): List<String> {
        val s = normalized()
        val local = ModelEndpointPolicy.isLocalEndpoint(s.baseUrl)
        val custom = s.baseUrl.isNotBlank() && !s.baseUrl.contains("openrouter.ai") && !local
        return buildList {
            if (s.baseUrl.isBlank() || custom || local || s.apiKey.isNotBlank()) {
                ModelEndpointPolicy.validate(s.baseUrl)?.let(::add)
            }
            if ((s.baseUrl.isBlank() || custom) && s.modelName.isBlank()) add("The helper name is missing.")
            if (custom && s.apiKey.isBlank() && !local) {
                add("The secret password is missing, unless the helper is on this phone.")
            }
            if (!custom && !s.hasAnyProviderKey() && !local) {
                add(
                    "The secret password is missing. Paste the key from openrouter.ai/keys. It stays on this phone and is not written into the app."
                )
            }
            val cloudflare = s.cloudflareSetupNote()
            if (cloudflare.isNotBlank() && ProviderCatalog.endpoints(s).isEmpty()) add(cloudflare)
        }
    }

    /**
     * A saved setup with no web address and no helper name has not chosen a provider.
     * Fill in one capable free OpenRouter helper. The old name openrouter/free picks
     * a different model on every step, so it is replaced with the pinned helper.
     * Never invent a secret password.
     */
    fun withOpenRouterDefaults(): ModelSettings {
        val s = normalized()
        val blankProvider = s.baseUrl.isBlank() && s.modelName.isBlank()
        val base = if (blankProvider) {
            s.copy(baseUrl = OPENROUTER_BASE_URL, modelName = OPENROUTER_FREE_MODEL)
        } else {
            s
        }
        if (!base.baseUrl.contains("openrouter.ai")) return base
        val usingRouter = base.modelName.isBlank() || base.modelName.equals(OPENROUTER_FREE_ROUTER, ignoreCase = true)
        val modelName = if (usingRouter) OPENROUTER_FREE_MODEL else base.modelName
        val rotation = when {
            base.rotationModels.isNotBlank() -> base.rotationModels
            usingRouter || modelName == OPENROUTER_FREE_MODEL -> OPENROUTER_FREE_FALLBACKS
            else -> ""
        }
        return if (modelName == base.modelName && rotation == base.rotationModels) {
            base
        } else {
            base.copy(modelName = modelName, rotationModels = rotation)
        }
    }

    fun isRemoteConfigured(): Boolean = validationErrors().isEmpty()

    fun statusSummary(): String {
        val s = normalized()
        val names = ProviderCatalog.providerNames(s)
        return when {
            !s.isRemoteConfigured() && s.baseUrl.contains("openrouter.ai") && !s.hasAnyProviderKey() ->
                "Helper not connected yet. Free OpenRouter helper is selected. Paste the secret password in Setup."
            !s.isRemoteConfigured() -> "Helper not connected yet. ${s.cloudflareSetupNote()}".trim()
            names.isEmpty() -> "Helper not connected yet. ${s.cloudflareSetupNote()}".trim()
            names.size == 1 -> "Helper ready: ${names.first()}. I use the best one that is still available. ${s.cloudflareSetupNote()}".trim()
            else -> "Helpers ready: ${names.joinToString(", ")}. I use the best one that is still available. ${s.cloudflareSetupNote()}".trim()
        }
    }

    fun remoteGateway(
        timeoutMillis: Int = 60_000,
        connectionFactory: ((String) -> java.net.HttpURLConnection)? = null,
        quotaFile: java.io.File? = null,
        rankingDirectory: java.io.File? = null,
        probe: Boolean = false
    ): ModelGateway? {
        val s = normalized()
        if (s.validationErrors().isNotEmpty()) return null
        return ProviderRotatingGateway.build(
            settings = s,
            timeoutMillis = timeoutMillis,
            connectionFactory = connectionFactory,
            quotaFile = quotaFile,
            rankingDirectory = rankingDirectory,
            probe = probe,
            extraHeaders = s.parsedExtraHeaders()
        )
    }

    companion object {
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        /**
         * The free router. It chooses a different free model on every request, including
         * very small ones, so it is not the helper this app starts with.
         */
        const val OPENROUTER_FREE_ROUTER = "openrouter/free"
        /**
         * One capable free coding helper. Checked against OpenRouter's public model list
         * on 22 Oct 2026: it supports tool calls and costs nothing. Other free helpers
         * are used only when this one returns an error or a rate limit.
         */
        const val OPENROUTER_FREE_MODEL = "cohere/north-mini-code:free"
        const val OPENROUTER_FREE_FALLBACKS =
            "poolside/laguna-s-2.1:free, google/gemma-4-31b-it:free, nvidia/nemotron-3-super-120b-a12b:free"
        const val MEMORY_KEEP_CAPPED = "capped"
        const val MEMORY_KEEP_FOREVER = "forever"
        const val DEFAULT_GITHUB_REPO = "thepotatoninjahost/Coding-Agent"

        data class FreeHelperChoice(val plainName: String, val modelId: String, val plainDetail: String)

        fun freeHelperChoices(): List<FreeHelperChoice> = listOf(
            FreeHelperChoice(
                "Cohere code helper",
                OPENROUTER_FREE_MODEL,
                "Good at writing code. This is the one I start with."
            ),
            FreeHelperChoice(
                "Poolside code helper",
                "poolside/laguna-s-2.1:free",
                "Another strong code helper."
            ),
            FreeHelperChoice(
                "Google Gemma",
                "google/gemma-4-31b-it:free",
                "A general helper that can also write code."
            ),
            FreeHelperChoice(
                "NVIDIA Nemotron",
                "nvidia/nemotron-3-super-120b-a12b:free",
                "A larger free helper."
            )
        )

        fun rotationForChoice(modelId: String): String =
            freeHelperChoices().map { it.modelId }.filter { it != modelId }.joinToString(", ")

        fun fromJson(raw: String?): ModelSettings {
            if (raw.isNullOrBlank()) return ModelSettings().withOpenRouterDefaults()
            return runCatching {
                val o = org.json.JSONObject(raw)
                val backendName = o.optString("backend", "REMOTE")
                val backend = if (backendName == "REMOTE") ModelBackend.REMOTE else ModelBackend.REMOTE
                ModelSettings(
                    backend = backend,
                    baseUrl = o.optString("baseUrl", ""),
                    apiKey = o.optString("apiKey", ""),
                    modelName = o.optString("modelName", ""),
                    rotationModels = o.optString("rotationModels", ""),
                    systemPrompt = o.optString("systemPrompt", ""),
                    extraHeaders = o.optString("extraHeaders", ""),
                    onboarded = o.optBoolean("onboarded", false),
                    memoryRetention = o.optString("memoryRetention", MEMORY_KEEP_CAPPED),
                    githubRepo = o.optString("githubRepo", DEFAULT_GITHUB_REPO),
                    githubBranch = o.optString("githubBranch", "main"),
                    preferredModel = o.optString("preferredModel", ""),
                    groqApiKey = o.optString("groqApiKey", ""),
                    geminiApiKey = o.optString("geminiApiKey", ""),
                    mistralApiKey = o.optString("mistralApiKey", ""),
                    cerebrasApiKey = o.optString("cerebrasApiKey", ""),
                    sambanovaApiKey = o.optString("sambanovaApiKey", ""),
                    requestyApiKey = o.optString("requestyApiKey", ""),
                    aimlApiKey = o.optString("aimlApiKey", ""),
                    cloudflareApiToken = o.optString("cloudflareApiToken", ""),
                    cloudflareAccountId = o.optString("cloudflareAccountId", "")
                ).normalized().withOpenRouterDefaults()
            }.getOrDefault(ModelSettings().withOpenRouterDefaults())
        }

        fun toJson(settings: ModelSettings): String {
            val s = settings.normalized()
            return org.json.JSONObject()
                .put("backend", s.backend.name)
                .put("baseUrl", s.baseUrl)
                .put("apiKey", s.apiKey)
                .put("modelName", s.modelName)
                .put("rotationModels", s.rotationModels)
                .put("systemPrompt", s.systemPrompt)
                .put("extraHeaders", s.extraHeaders)
                .put("onboarded", s.onboarded)
                .put("memoryRetention", s.memoryRetention)
                .put("githubRepo", s.githubRepo)
                .put("githubBranch", s.githubBranch)
                .put("preferredModel", s.preferredModel)
                .put("groqApiKey", s.groqApiKey)
                .put("geminiApiKey", s.geminiApiKey)
                .put("mistralApiKey", s.mistralApiKey)
                .put("cerebrasApiKey", s.cerebrasApiKey)
                .put("sambanovaApiKey", s.sambanovaApiKey)
                .put("requestyApiKey", s.requestyApiKey)
                .put("aimlApiKey", s.aimlApiKey)
                .put("cloudflareApiToken", s.cloudflareApiToken)
                .put("cloudflareAccountId", s.cloudflareAccountId)
                .toString()
        }
    }
}

object ModelConnectionProbe {
    fun probe(
        settings: ModelSettings,
        gatewayFactory: (ModelSettings) -> ModelGateway? = { it.remoteGateway(probe = true) }
    ): ProbeResult {
        val errors = settings.validationErrors()
        if (errors.isNotEmpty()) return ProbeResult.Failed(errors.joinToString("; "))
        // Probe only the primary model so a bad fallback does not hide a working primary.
        val primaryOnly = settings.normalized().copy(rotationModels = "")
        val gateway = gatewayFactory(primaryOnly)
            ?: return ProbeResult.Failed("Remote gateway is not available")
        return try {
            val response = gateway.complete(
                ModelRequest(
                    system = "Reply with the single word pong.",
                    user = "ping",
                    tools = emptyList()
                )
            )
            when (response) {
                is ModelResponse.Text -> {
                    val content = response.content.trim()
                    if (content.isEmpty()) {
                        ProbeResult.Failed("Model returned no usable message content")
                    } else {
                        ProbeResult.Ok("Reached model (${content})")
                    }
                }
                is ModelResponse.ToolCall -> ProbeResult.Ok("Reached model (tool call path)")
                is ModelResponse.Failure -> ProbeResult.Failed(
                    ModelFailure.humanize(explainProviderError(settings.modelName, response.message))
                )
            }
        } catch (error: Exception) {
            ProbeResult.Failed(
                ModelFailure.humanize(sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName }))
            )
        }
    }

    /**
     * Never echo secrets (API keys, Bearer tokens) into UI-facing probe failures.
     * Phone paste of long keys often injects newlines; the raw JVM message includes the header.
     */
    fun sanitizeProbeError(raw: String): String {
        var msg = raw
        msg = msg.replace(
            Regex("""Bearer\s+\S+(?:\s+\S+)*""", RegexOption.IGNORE_CASE),
            "Bearer [redacted]"
        )
        if ("0x0a" in msg || "Unexpected char" in msg) {
            return "The secret password has a line break or a space in it. " +
                "Clear that box, paste it again as one line, then tap Try connection."
        }
        return msg
    }

    fun explainProviderError(modelName: String, raw: String): String {
        val lower = raw.lowercase()
        if ("agentic harness" in lower || "gate free endpoints by agentic harness" in lower) {
            val paid = modelName.removeSuffix(":free").removeSuffix(":Free")
            return "The free helper named $modelName refused this app. That company only lets a short list of other apps use the free version. " +
                "In Setup, pick a different free helper, such as Cohere code helper. See openrouter.ai/apps."
        }
        return sanitizeProbeError(raw)
    }
}

sealed class ProbeResult {
    data class Ok(val detail: String) : ProbeResult()
    data class Failed(val reason: String) : ProbeResult()
}
