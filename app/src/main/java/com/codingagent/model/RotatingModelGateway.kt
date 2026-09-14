package com.codingagent.model

import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE JOB: Try the next configured model when the current one is rate-limited,
 * at capacity, or otherwise overloaded. Same base URL + API key; only the model
 * id changes. Sticks on the first model that succeeds so subsequent turns stay
 * on a working endpoint until it fails again.
 */
class RotatingModelGateway(
    private val entries: List<Entry>,
    private val onRotated: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null
) : ModelGateway {

    data class Entry(val modelId: String, val gateway: ModelGateway)

    private val index = AtomicInteger(0)

    init {
        require(entries.isNotEmpty()) { "RotatingModelGateway requires at least one model entry" }
    }

    fun currentModelId(): String = entries[index.get().coerceIn(0, entries.lastIndex)].modelId

    fun modelIds(): List<String> = entries.map { it.modelId }

    override fun complete(request: ModelRequest): ModelResponse =
        runWithRotation { it.complete(request) }

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse =
        runWithRotation { it.stream(request, onDelta) }

    private fun runWithRotation(call: (ModelGateway) -> ModelResponse): ModelResponse {
        val start = index.get().coerceIn(0, entries.lastIndex)
        var lastFailure: ModelResponse.Failure? = null

        for (offset in entries.indices) {
            val idx = (start + offset) % entries.size
            val entry = entries[idx]
            val response = call(entry.gateway)

            if (response !is ModelResponse.Failure) {
                // Stick on the model that worked so the next turn does not bounce.
                if (idx != start) {
                    index.set(idx)
                }
                return response
            }

            lastFailure = response
            if (!isRotatableFailure(response.message)) {
                // Auth / bad request / etc. — do not burn the rest of the list.
                return response
            }

            if (offset < entries.lastIndex) {
                val next = entries[(idx + 1) % entries.size]
                onRotated?.invoke(entry.modelId, next.modelId, response.message.take(160))
                // Brief pause so a shared free-tier bucket has a chance to recover
                // between consecutive model switches on the same key.
                try {
                    Thread.sleep(400L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return response
                }
            }
        }

        return lastFailure ?: ModelResponse.Failure("All rotation models failed")
    }

    companion object {
        /**
         * Same signals [com.codingagent.agent.ModelFailure] uses for rate/capacity,
         * kept local so the model package does not depend on the agent package.
         */
        fun isRotatableFailure(message: String): Boolean {
            val lower = message.lowercase()
            return "429" in lower ||
                "rate_limit" in lower ||
                "rate limit" in lower ||
                "too many requests" in lower ||
                "tokens per minute" in lower ||
                "tpm" in lower ||
                "quota" in lower ||
                "resourceexhausted" in lower ||
                "resource exhausted" in lower ||
                "overloaded" in lower ||
                "capacity" in lower ||
                "request limit reached" in lower ||
                "worker local" in lower ||
                "503" in lower ||
                "service unavailable" in lower ||
                "overfill" in lower ||
                "provider at capacity" in lower
        }

        fun build(
            baseUrl: String,
            apiKey: String,
            modelIds: List<String>,
            timeoutMillis: Int = 60_000,
            extraHeaders: Map<String, String> = emptyMap(),
            connectionFactory: ((String) -> java.net.HttpURLConnection)? = null,
            onRotated: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null
        ): ModelGateway {
            val unique = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(unique.isNotEmpty()) { "At least one model id is required" }
            val entries = unique.map { id ->
                val gw = if (connectionFactory != null) {
                    RemoteHttpGateway(baseUrl, apiKey, id, timeoutMillis, connectionFactory, extraHeaders)
                } else {
                    RemoteHttpGateway(baseUrl, apiKey, id, timeoutMillis, extraHeaders = extraHeaders)
                }
                Entry(id, gw)
            }
            return if (entries.size == 1) entries.single().gateway
            else RotatingModelGateway(entries, onRotated)
        }
    }
}
