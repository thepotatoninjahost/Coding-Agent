package com.codingagent.model

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
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    /**
     * Optional ordered fallback model IDs that share the same base URL and API key.
     * Comma, semicolon, or newline separated. When the primary model returns a
     * rate-limit / capacity / overfill error, [RotatingModelGateway] automatically
     * tries the next id in this list.
     */
    val rotationModels: String = "",
    val systemPrompt: String = "",
    /** Optional extra HTTP headers, one per line: "Header-Name: value" */
    val extraHeaders: String = "",
    val onboarded: Boolean = false
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
        extraHeaders = extraHeaders.trim()
    )

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
        return buildList {
            ModelEndpointPolicy.validate(s.baseUrl)?.let(::add)
            if (s.modelName.isBlank()) add("The helper name is missing.")
            val local = ModelEndpointPolicy.isLocalEndpoint(s.baseUrl)
            if (s.apiKey.isBlank() && !local) add("The secret password is missing, unless the helper is on this phone.")
        }
    }

    fun isRemoteConfigured(): Boolean = validationErrors().isEmpty()

    fun statusSummary(): String {
        val host = runCatching {
            java.net.URI(normalized().baseUrl).host ?: normalized().baseUrl
        }.getOrDefault(normalized().baseUrl).ifBlank { "…" }
        val ids = allModelIds()
        return when {
            !isRemoteConfigured() -> "Helper not connected yet"
            ids.size <= 1 -> "Helper · ${ids.firstOrNull() ?: modelName} at $host"
            else -> "Helper · ${ids.first()} and ${ids.size - 1} backups at $host"
        }
    }

    fun remoteGateway(
        timeoutMillis: Int = 60_000,
        connectionFactory: ((String) -> java.net.HttpURLConnection)? = null,
        onRotated: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null
    ): ModelGateway? {
        val s = normalized()
        if (s.validationErrors().isNotEmpty()) return null
        val ids = allModelIds()
        if (ids.isEmpty()) return null
        val headers = s.parsedExtraHeaders()
        return RotatingModelGateway.build(
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            modelIds = ids,
            timeoutMillis = timeoutMillis,
            extraHeaders = headers,
            connectionFactory = connectionFactory,
            onRotated = onRotated
        )
    }

    companion object {
        fun fromJson(raw: String?): ModelSettings {
            if (raw.isNullOrBlank()) return ModelSettings()
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
                    onboarded = o.optBoolean("onboarded", false)
                ).normalized()
            }.getOrDefault(ModelSettings())
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
                .toString()
        }
    }
}

object ModelConnectionProbe {
    fun probe(
        settings: ModelSettings,
        gatewayFactory: (ModelSettings) -> ModelGateway? = { it.remoteGateway() }
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
                is ModelResponse.Failure -> ProbeResult.Failed(explainProviderError(settings.modelName, response.message))
            }
        } catch (error: Exception) {
            ProbeResult.Failed(sanitizeProbeError(error.message.orEmpty().ifBlank { error.javaClass.simpleName }))
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
                "Try the paid name `$paid` (the same helper, but you pay), or pick a different free helper that is not locked, " +
                "such as openrouter/free or qwen/qwen3-coder:free. See openrouter.ai/apps."
        }
        return sanitizeProbeError(raw)
    }
}

sealed class ProbeResult {
    data class Ok(val detail: String) : ProbeResult()
    data class Failed(val reason: String) : ProbeResult()
}
