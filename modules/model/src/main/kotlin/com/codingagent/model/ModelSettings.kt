package com.codingagent.model

enum class ModelBackend {
    HTTP_CHAT
}

data class ModelSettings(
    val backend: ModelBackend = ModelBackend.HTTP_CHAT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeyRef: String = DEFAULT_API_KEY_REF,
    val modelName: String = "",
    val onboarded: Boolean = false,
    val authHeaderName: String = "Authorization",
    val authHeaderPrefix: String = "Bearer ",
    val extraHeaders: Map<String, String> = emptyMap()
) {
    fun normalized(): ModelSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        apiKeyRef = apiKeyRef.trim().ifBlank { DEFAULT_API_KEY_REF },
        modelName = modelName.trim(),
        authHeaderName = authHeaderName.trim(),
        authHeaderPrefix = authHeaderPrefix,
        extraHeaders = extraHeaders.mapKeys { it.key.trim() }.mapValues { it.value.trim() }.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
    )

    fun withoutSecret(): ModelSettings = normalized().copy(apiKey = "")

    fun validationErrors(): List<String> {
        val s = normalized()
        return buildList {
            val endpoint = endpointUri(s.baseUrl)
            if (s.baseUrl.isBlank()) {
                add("API base URL is required")
            } else if (endpoint == null) {
                add("API base URL must be a valid HTTPS URL; HTTP is allowed only for localhost")
            } else {
                val local = isLoopback(endpoint.host)
                if (endpoint.scheme != "https" && !(endpoint.scheme == "http" && local)) {
                    add("API endpoints must use HTTPS; HTTP is allowed only for loopback")
                }
                if (endpoint.userInfo != null || endpoint.query != null || endpoint.fragment != null) {
                    add("API base URL must not contain credentials, query parameters, or fragments")
                }
            }
            if (s.modelName.isBlank()) add("Model name is required")
            if (!s.apiKeyRef.matches(SECRET_REF_PATTERN)) add("Secret reference is invalid")
            if (s.authHeaderName.isBlank() && s.apiKey.isNotBlank()) add("Auth header is required when an API key is provided")
            if (s.authHeaderName.isNotBlank() && !s.authHeaderName.matches(HEADER_NAME_PATTERN)) add("Auth header name is invalid")
            if (s.extraHeaders.keys.any { !it.matches(HEADER_NAME_PATTERN) }) add("Additional header name is invalid")
        }
    }

    fun isConfigured(): Boolean = validationErrors().isEmpty()

    fun statusSummary(): String {
        val host = endpointUri(normalized().baseUrl)?.host ?: "not configured"
        return if (isConfigured()) "Remote API · $modelName @ $host" else "Remote API · setup required"
    }

    fun remoteGateway(
        timeoutMillis: Int = 60_000,
        connectionFactory: ((String) -> java.net.HttpURLConnection)? = null
    ): HttpChatModelGateway? {
        val s = normalized()
        if (s.validationErrors().isNotEmpty()) return null
        return if (connectionFactory != null) {
            HttpChatModelGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis, s.authHeaderName, s.authHeaderPrefix, s.extraHeaders, connectionFactory)
        } else {
            HttpChatModelGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis, s.authHeaderName, s.authHeaderPrefix, s.extraHeaders)
        }
    }

    companion object {
        const val DEFAULT_API_KEY_REF = "CODING_AGENT_MODEL_API_KEY"
        private val SECRET_REF_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{2,63}")
        private val HEADER_NAME_PATTERN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")

        fun fromJson(raw: String?): ModelSettings {
            if (raw.isNullOrBlank()) return ModelSettings()
            return runCatching {
                val o = org.json.JSONObject(raw)
                val storedBackend = o.optString("backend")
                val headers = buildMap {
                    val serialized = o.optJSONArray("extraHeaders") ?: return@buildMap
                    for (index in 0 until serialized.length()) {
                        val item = serialized.optJSONObject(index) ?: continue
                        val name = item.optString("name").trim()
                        val value = item.optString("value")
                        if (name.isNotBlank() && value.isNotBlank()) put(name, value)
                    }
                }
                ModelSettings(
                    backend = ModelBackend.HTTP_CHAT,
                    baseUrl = o.optString("baseUrl", ""),
                    apiKey = "",
                    apiKeyRef = o.optString("apiKeyRef", DEFAULT_API_KEY_REF),
                    modelName = o.optString("modelName", ""),
                    onboarded = storedBackend == ModelBackend.HTTP_CHAT.name && o.optBoolean("onboarded", false),
                    authHeaderName = o.optString("authHeaderName", "Authorization"),
                    authHeaderPrefix = o.optString("authHeaderPrefix", "Bearer "),
                    extraHeaders = headers
                ).normalized()
            }.getOrDefault(ModelSettings())
        }

        fun toJson(settings: ModelSettings): String {
            val s = settings.normalized()
            return org.json.JSONObject()
                .put("backend", ModelBackend.HTTP_CHAT.name)
                .put("baseUrl", s.baseUrl)
                .put("apiKey", "")
                .put("apiKeyRef", s.apiKeyRef)
                .put("modelName", s.modelName)
                .put("onboarded", s.onboarded)
                .put("authHeaderName", s.authHeaderName)
                .put("authHeaderPrefix", s.authHeaderPrefix)
                .put("extraHeaders", org.json.JSONArray().apply { s.extraHeaders.forEach { (name, value) -> put(org.json.JSONObject().put("name", name).put("value", value)) } })
                .toString()
        }
    }
}

private fun endpointUri(value: String): java.net.URI? = runCatching {
    java.net.URI(value).takeIf { it.scheme != null && it.host != null }
}.getOrNull()

private fun isLoopback(host: String): Boolean = host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "[::1]" || host == "::1"

object ModelConnectionProbe {
    fun probe(settings: ModelSettings): ProbeResult {
        val errors = settings.validationErrors()
        if (errors.isNotEmpty()) return ProbeResult.Failed(errors.joinToString("; "))
        val gateway = settings.remoteGateway() ?: return ProbeResult.Failed("Remote gateway is not configured")
        return try {
            when (val response = gateway.complete(ModelRequest("Reply with the single word pong.", "ping", emptyList()))) {
                is ModelResponse.Text -> ProbeResult.Ok("Reached model: ${response.content.take(80).ifBlank { "empty response" }}")
                is ModelResponse.ToolCall -> ProbeResult.Ok("Reached model through the tool-call endpoint")
                is ModelResponse.Failure -> ProbeResult.Failed(response.message)
            }
        } catch (error: Exception) {
            ProbeResult.Failed(error.message.orEmpty().ifBlank { error.javaClass.simpleName })
        }
    }
}

sealed class ProbeResult {
    data class Ok(val detail: String) : ProbeResult()
    data class Failed(val reason: String) : ProbeResult()
}
