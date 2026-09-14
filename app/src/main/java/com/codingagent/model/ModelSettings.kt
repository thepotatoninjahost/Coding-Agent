package com.codingagent.model

import com.codingagent.core.LocalStore

/**
 * ONE JOB: User-facing model gateway configuration (base URL, model id, API key,
 * optional rotation fallbacks). Pure data so unit tests do not need Android;
 * persistence lives in [LocalStore].
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
        apiKey = apiKey.trim(),
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
        val ids = allModelIds()
        return when {
            !isRemoteConfigured() -> "Remote · set base URL, model, and API key"
            ids.size <= 1 -> "Remote · ${ids.firstOrNull() ?: modelName} @ $host"
            else -> "Remote · ${ids.first()} + ${ids.size - 1} fallback(s) @ $host"
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
    fun probe(settings: ModelSettings): ProbeResult {
        val errors = settings.validationErrors()
        if (errors.isNotEmpty()) return ProbeResult.Failed(errors.joinToString("; "))
        // Probe only the primary model so a bad fallback does not hide a working primary.
        val primaryOnly = settings.normalized().copy(rotationModels = "")
        val gateway = primaryOnly.remoteGateway()
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
                is ModelResponse.Failure -> ProbeResult.Failed(explainProviderError(settings.modelName, response.message))
            }
        } catch (error: Exception) {
            ProbeResult.Failed(error.message.orEmpty().ifBlank { error.javaClass.simpleName })
        }
    }

    fun explainProviderError(modelName: String, raw: String): String {
        val lower = raw.lowercase()
        if ("agentic harness" in lower || "gate free endpoints by agentic harness" in lower) {
            val paid = modelName.removeSuffix(":free").removeSuffix(":Free")
            return "OpenRouter blocked $modelName because that FREE endpoint is allowlisted " +
                "to a short list of published harnesses (Claude Code, Codex, Hermes Agent, Cline, …). " +
                "This app is a coding agent; OpenRouter still does not treat custom HTTP clients as that list. " +
                "Use the paid id `$paid` (same model, billed), or pick another :free model that is not harness-gated " +
                "(for example openrouter/free or qwen/qwen3-coder:free). See openrouter.ai/apps."
        }
        return raw
    }
}

sealed class ProbeResult {
    data class Ok(val detail: String) : ProbeResult()
    data class Failed(val reason: String) : ProbeResult()
}
