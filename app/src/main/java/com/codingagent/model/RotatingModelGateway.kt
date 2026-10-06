package com.codingagent.model

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ONE JOB: Try the next configured model when the current one is rate-limited,
 * at capacity, or otherwise overloaded.
 */
class RotatingModelGateway(
    private val entries: List<Entry>,
    private val onRotated: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null
) : ModelGateway {

    data class Entry(val modelId: String, val gateway: ModelGateway)

    private val index = AtomicInteger(0)
    private val cancellationGeneration = AtomicLong(0L)

    init {
        require(entries.isNotEmpty()) { "RotatingModelGateway requires at least one model entry" }
    }

    fun currentModelId(): String = entries[index.get().coerceIn(0, entries.lastIndex)].modelId
    fun modelIds(): List<String> = entries.map { it.modelId }

    override fun complete(request: ModelRequest): ModelResponse = runWithRotation { it.complete(request) }
    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse =
        runWithRotation { it.stream(request, onDelta) }

    override fun cancel() {
        cancellationGeneration.incrementAndGet()
        entries.forEach { it.gateway.cancel() }
    }

    private fun runWithRotation(call: (ModelGateway) -> ModelResponse): ModelResponse {
        val generation = cancellationGeneration.get()
        val start = index.get().coerceIn(0, entries.lastIndex)
        if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")

        var lastFailure: ModelResponse.Failure? = null
        for (offset in entries.indices) {
            if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
            val idx = (start + offset) % entries.size
            val response = call(entries[idx].gateway)
            if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")

            if (response !is ModelResponse.Failure) {
                if (idx != start) index.set(idx)
                return response
            }

            lastFailure = response
            if (!isRotatableFailure(response.message)) return response

            if (offset < entries.lastIndex) {
                if (isCancelled(generation)) return ModelResponse.Failure("Cancelled")
                val next = entries[(idx + 1) % entries.size]
                onRotated?.invoke(entries[idx].modelId, next.modelId, response.message.take(160))
                try {
                    Thread.sleep(400L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return if (isCancelled(generation)) ModelResponse.Failure("Cancelled") else response
                }
            }
        }
        return lastFailure ?: ModelResponse.Failure("All rotation models failed")
    }

    private fun isCancelled(generation: Long): Boolean = cancellationGeneration.get() != generation

    companion object {
        fun isRotatableFailure(message: String): Boolean {
            val lower = message.lowercase()
            return "429" in lower || "rate_limit" in lower || "rate limit" in lower ||
                "too many requests" in lower || "tokens per minute" in lower || "tpm" in lower ||
                "quota" in lower || "resourceexhausted" in lower || "resource exhausted" in lower ||
                "overloaded" in lower || "capacity" in lower || "request limit reached" in lower ||
                "worker local" in lower || "503" in lower || "service unavailable" in lower ||
                "overfill" in lower || "provider at capacity" in lower
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
            return if (entries.size == 1) entries.single().gateway else RotatingModelGateway(entries, onRotated)
        }
    }
}
