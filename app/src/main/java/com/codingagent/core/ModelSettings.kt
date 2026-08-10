package com.codingagent.core

/**
 * User-facing model gateway configuration.
 * Pure data so unit tests do not need Android; persistence lives in [LocalStore].
 *
 * Remote path only: user supplies base URL, model id, and API key. Nothing is
 * product-hardcoded for a specific host or model. On-device NPU backends have
 * been removed; the product uses OpenAI-compatible remote gateways.
 */
enum class ModelBackend {
    /** Remote HTTP model endpoint (tools + optional SSE). */
    REMOTE
}

data class ModelSettings(
    val backend: ModelBackend = ModelBackend.REMOTE,
    /** User-supplied base URL. Empty until set. */
    val baseUrl: String = "",
    val apiKey: String = "",
    /** User-chosen model id. Empty until the user sets it. */
    val modelName: String = "",
    /**
     * Owner-written instructions sent to the model *before* each user message.
     * Empty means use [AgentModelProtocol.DEFAULT_SYSTEM]. Never filled by an external assistant.
     */
    val systemPrompt: String = "",
    /** False until the user finishes first-run onboarding (or saves settings once). */
    val onboarded: Boolean = false
) {
    fun normalized(): ModelSettings = copy(
        backend = ModelBackend.REMOTE,
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        modelName = modelName.trim(),
        systemPrompt = systemPrompt.trim()
    )

    /** What the model actually receives as the system message. */
    fun effectiveSystemPrompt(): String =
        normalized().systemPrompt.ifBlank { AgentModelProtocol.DEFAULT_SYSTEM }

    fun validationErrors(): List<String> {
        val s = normalized()
        return buildList {
            if (s.baseUrl.isBlank()) add("Base URL is required for remote models")
            else if (!s.baseUrl.startsWith("http://") && !s.baseUrl.startsWith("https://")) {
                add("Base URL must start with http:// or https://")
            }
            if (s.modelName.isBlank()) add("Model name is required")
            val local = s.baseUrl.startsWith("http://127.0.0.1") || s.baseUrl.startsWith("http://localhost")
            if (s.apiKey.isBlank() && !local) add("API key is required (except localhost endpoints)")
        }
    }

    fun isRemoteConfigured(): Boolean = validationErrors().isEmpty()

    /** Human-readable line for the status bar (never includes the API key). */
    fun statusSummary(): String {
        val host = runCatching {
            java.net.URI(normalized().baseUrl).host ?: normalized().baseUrl
        }.getOrDefault(normalized().baseUrl).ifBlank { "…" }
        return when {
            isRemoteConfigured() -> "Remote · $modelName @ $host"
            else -> "Remote · set base URL, model, and API key"
        }
    }

    /**
     * Build a remote gateway when settings are valid.
     * Returns null when configuration is incomplete.
     */
    fun remoteGateway(
        timeoutMillis: Int = 60_000,
        connectionFactory: ((String) -> java.net.HttpURLConnection)? = null
    ): RemoteHttpGateway? {
        val s = normalized()
        if (s.validationErrors().isNotEmpty()) return null
        return if (connectionFactory != null) {
            RemoteHttpGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis, connectionFactory)
        } else {
            RemoteHttpGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis)
        }
    }

    companion object {
        fun fromJson(raw: String?): ModelSettings {
            if (raw.isNullOrBlank()) return ModelSettings()
            return runCatching {
                val o = org.json.JSONObject(raw)
                // Legacy persisted values may still say LOCAL_NEXA; always map to REMOTE.
                val backendName = o.optString("backend", "REMOTE")
                val backend = if (backendName == "REMOTE") ModelBackend.REMOTE else ModelBackend.REMOTE
                ModelSettings(
                    backend = backend,
                    baseUrl = o.optString("baseUrl", ""),
                    apiKey = o.optString("apiKey", ""),
                    modelName = o.optString("modelName", ""),
                    systemPrompt = o.optString("systemPrompt", ""),
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
                .put("systemPrompt", s.systemPrompt)
                .put("onboarded", s.onboarded)
                .toString()
        }
    }
}

/** Probe a remote endpoint with a minimal non-tool completion. */
object ModelConnectionProbe {
    fun probe(settings: ModelSettings): ProbeResult {
        val errors = settings.validationErrors()
        if (errors.isNotEmpty()) return ProbeResult.Failed(errors.joinToString("; "))
        val gateway = settings.remoteGateway()
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
