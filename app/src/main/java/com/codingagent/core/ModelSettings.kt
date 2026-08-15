package com.codingagent.core

/**
 * ONE JOB: User-facing model gateway configuration (base URL, model id, API key).
 * Pure data so unit tests do not need Android; persistence lives in [LocalStore].
 */
enum class ModelBackend {
    /** Remote HTTP model endpoint (tools + optional SSE). */
    REMOTE
}

data class ModelSettings(
    val backend: ModelBackend = ModelBackend.REMOTE,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val systemPrompt: String = "",
    /** Optional extra HTTP headers, one per line: "Header-Name: value" */
    val extraHeaders: String = "",
    val onboarded: Boolean = false
) {
    fun normalized(): ModelSettings = copy(
        backend = ModelBackend.REMOTE,
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        modelName = modelName.trim(),
        systemPrompt = systemPrompt.trim(),
        extraHeaders = extraHeaders.trim()
    )

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
            if (name.isNotBlank() && value.isNotBlank()) out[name] = value
        }
        return out
    }

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

    fun statusSummary(): String {
        val host = runCatching {
            java.net.URI(normalized().baseUrl).host ?: normalized().baseUrl
        }.getOrDefault(normalized().baseUrl).ifBlank { "…" }
        return when {
            isRemoteConfigured() -> "Remote · $modelName @ $host"
            else -> "Remote · set base URL, model, and API key"
        }
    }

    fun remoteGateway(
        timeoutMillis: Int = 60_000,
        connectionFactory: ((String) -> java.net.HttpURLConnection)? = null
    ): RemoteHttpGateway? {
        val s = normalized()
        if (s.validationErrors().isNotEmpty()) return null
        val headers = s.parsedExtraHeaders()
        return if (connectionFactory != null) {
            RemoteHttpGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis, connectionFactory, headers)
        } else {
            RemoteHttpGateway(s.baseUrl, s.apiKey, s.modelName, timeoutMillis, extraHeaders = headers)
        }
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
                .put("systemPrompt", s.systemPrompt)
                .put("extraHeaders", s.extraHeaders)
                .put("onboarded", s.onboarded)
                .toString()
        }
    }
}

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
