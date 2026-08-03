package com.codingagent.core

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
    val modelName: String = DEFAULT_REMOTE_MODEL,
    /** False until the user finishes first-run onboarding (or saves settings once). */
    val onboarded: Boolean = false
) {
    fun normalized(): ModelSettings = copy(
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        modelName = modelName.trim()
    )

    fun validationErrors(): List<String> {
        val s = normalized()
        return when (s.backend) {
            ModelBackend.LOCAL_NEXA -> emptyList()
            ModelBackend.REMOTE_OPENAI -> buildList {
                if (s.baseUrl.isBlank()) add("Base URL is required for remote models")
                else if (!s.baseUrl.startsWith("http://") && !s.baseUrl.startsWith("https://")) {
                    add("Base URL must start with http:// or https://")
                }
                if (s.modelName.isBlank()) add("Model name is required")
                val local = s.baseUrl.startsWith("http://127.0.0.1") || s.baseUrl.startsWith("http://localhost")
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

        fun fromJson(raw: String?): ModelSettings {
            if (raw.isNullOrBlank()) return ModelSettings()
            return runCatching {
                val o = org.json.JSONObject(raw)
                ModelSettings(
                    backend = runCatching { ModelBackend.valueOf(o.getString("backend")) }
                        .getOrDefault(ModelBackend.LOCAL_NEXA),
                    baseUrl = o.optString("baseUrl", DEFAULT_BASE_URL),
                    apiKey = o.optString("apiKey", ""),
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
                .put("apiKey", s.apiKey)
                .put("modelName", s.modelName)
                .put("onboarded", s.onboarded)
                .toString()
        }
    }
}

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
