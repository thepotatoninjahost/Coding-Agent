package com.codingagent.model

/**
 * User-facing model gateway configuration.
 * Pure data so unit tests do not need Android; persistence lives in [LocalStore].
 */
enum class ModelBackend {
    /** On-device Nexa NPU (Qwen3-4B mobile package). */
    LOCAL_NEXA,
    /** Any OpenAI-compatible HTTP endpoint (tools + optional SSE). */
    REMOTE_OPENAI
}

data class ModelSettings(
    val backend: ModelBackend = ModelBackend.LOCAL_NEXA,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val apiKeyRef: String = DEFAULT_API_KEY_REF,
    val modelName: String = DEFAULT_REMOTE_MODEL,
    /** False until the user finishes first-run onboarding (or saves settings once). */
    val onboarded: Boolean = false
) {
    fun normalized(): ModelSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        apiKeyRef = apiKeyRef.trim().ifBlank { DEFAULT_API_KEY_REF },
        modelName = modelName.trim()
    )

    fun withoutSecret(): ModelSettings = normalized().copy(apiKey = "")

    fun validationErrors(): List<String> {
        val s = normalized()
        return when (s.backend) {
            ModelBackend.LOCAL_NEXA -> emptyList()
            ModelBackend.REMOTE_OPENAI -> buildList {
                val endpoint = endpointUri(s.baseUrl)
                if (s.baseUrl.isBlank()) {
                    add("Base URL is required for remote models")
                } else if (endpoint == null) {
                    add("Base URL must be a valid HTTPS URL; HTTP is allowed only for localhost")
                } else {
                    val local = endpoint.host.equals("localhost", ignoreCase = true) || endpoint.host == "127.0.0.1" || endpoint.host == "[::1]" || endpoint.host == "::1"
                    if (endpoint.scheme != "https" && !(endpoint.scheme == "http" && local)) add("Remote model endpoints must use HTTPS; HTTP is allowed only for loopback")
                    if (endpoint.userInfo != null || endpoint.query != null || endpoint.fragment != null) add("Base URL must not contain credentials or query parameters")
                }
                if (s.modelName.isBlank()) add("Model name is required")
                if (!s.apiKeyRef.matches(SECRET_REF_PATTERN)) add("Secret reference is invalid")
                val local = endpoint?.host?.let { host -> host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "[::1]" || host == "::1" } == true
                if (s.apiKey.isBlank() && !local) add("API key is required (except localhost endpoints)")
            }
        }
    }

    fun isRemoteConfigured(): Boolean =
        backend == ModelBackend.REMOTE_OPENAI && validationErrors().isEmpty()

    /** Human-readable line for the status bar (never includes the API key). */
    fun statusSummary(localActive: Boolean = false, localError: String? = null): String = when (backend) {
        ModelBackend.LOCAL_NEXA -> when {
            localError != null -> "Local NPU · error"
            localActive -> "Local NPU · Qwen3-4B active"
            else -> "Local NPU · loading"
        }
        ModelBackend.REMOTE_OPENAI -> {
            val host = runCatching {
                java.net.URI(normalized().baseUrl).host ?: normalized().baseUrl
            }.getOrDefault(normalized().baseUrl)
            if (isRemoteConfigured()) "Remote · $modelName @ $host"
            else "Remote · incomplete settings"
        }
    }

    /**
     * Build a remote gateway when backend is REMOTE and settings are valid.
     * Returns null when backend is local or configuration is incomplete.
     */
    fun remoteGateway(
        timeoutMillis: Int = 60_000,
        connectionFactory: ((String) -> java.net.HttpURLConnection)? = null
    ): OpenAiCompatibleGateway? {
        val s = normalized()
        if (s.backend != ModelBackend.REMOTE_OPENAI) return null
        if (s.validationErrors().isNotEmpty()) return null
        return if (connectionFactory != null) {
            OpenAiCompatibleGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis, connectionFactory)
        } else {
            OpenAiCompatibleGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_REMOTE_MODEL = "gpt-4o-mini"
        const val DEFAULT_API_KEY_REF = "CODING_AGENT_MODEL_API_KEY"
        private val SECRET_REF_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{2,63}")

        fun fromJson(raw: String?): ModelSettings {
            if (raw.isNullOrBlank()) return ModelSettings()
            return runCatching {
                val o = org.json.JSONObject(raw)
                ModelSettings(
                    backend = runCatching { ModelBackend.valueOf(o.getString("backend")) }
                        .getOrDefault(ModelBackend.LOCAL_NEXA),
                    baseUrl = o.optString("baseUrl", DEFAULT_BASE_URL),
                    apiKey = o.optString("apiKey", ""),
                    apiKeyRef = o.optString("apiKeyRef", DEFAULT_API_KEY_REF),
                    modelName = o.optString("modelName", DEFAULT_REMOTE_MODEL),
                    onboarded = o.optBoolean("onboarded", false)
                ).normalized()
            }.getOrDefault(ModelSettings())
        }

        fun toJson(settings: ModelSettings): String {
            val s = settings.normalized()
            return org.json.JSONObject()
                .put("backend", s.backend.name)
                .put("baseUrl", s.baseUrl)
                .put("apiKey", "")
                .put("apiKeyRef", s.apiKeyRef)
                .put("modelName", s.modelName)
                .put("onboarded", s.onboarded)
                .toString()
        }
    }
}

private fun endpointUri(value: String): java.net.URI? = runCatching { java.net.URI(value).takeIf { it.scheme != null && it.host != null } }.getOrNull()

/** Probe a remote OpenAI-compatible endpoint with a minimal non-tool completion. */
object ModelConnectionProbe {
    fun probe(settings: ModelSettings): ProbeResult {
        val errors = settings.validationErrors()
        if (errors.isNotEmpty()) return ProbeResult.Failed(errors.joinToString("; "))
        val gateway = settings.remoteGateway()
            ?: return ProbeResult.Failed("Remote gateway is not available for this backend")
        return try {
            val response = gateway.complete(
                ModelRequest(
                    system = "Reply with the single word pong.",
                    user = "ping",
                    tools = emptyList()
                )
            )
            when (response) {
                is ModelResponse.Text -> ProbeResult.Ok("Reached model (${response.content.take(80).ifBlank { "empty body" }})")
                is ModelResponse.ToolCall -> ProbeResult.Ok("Reached model (tool call path)")
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
