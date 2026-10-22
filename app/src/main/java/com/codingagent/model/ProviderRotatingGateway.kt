package com.codingagent.model

import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Calls the best helper that is still available. One rate limit, daily limit, or error
 * moves the same request to the next helper. A used-up helper is skipped until its reset time.
 * The task transcript stays with the caller, so a switch does not drop work already done.
 */
class ProviderRotatingGateway(
    private val endpoints: List<ProviderEndpoint>,
    val quota: ProviderQuotaBook,
    private val ranking: () -> List<String> = { ModelRanking.bundledIds() },
    private val preferredModel: String = "",
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val connectionFactory: ((String) -> HttpURLConnection)? = null,
    private val timeoutMillis: Int = 60_000,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val installed: Map<String, ModelGateway> = emptyMap()
) : ModelGateway {
    private val cancelGeneration = AtomicLong(0L)
    private val gateways = ConcurrentHashMap<String, RemoteHttpGateway>()
    private val avoidModelId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastTriedModelId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    init {
        require(endpoints.isNotEmpty()) { "At least one helper is required" }
    }

    fun modelIds(): List<String> = ordered().map { it.modelId }

    override fun complete(request: ModelRequest): ModelResponse = stream(request) {}

    override fun preferDifferentModel() {
        avoidModelId.set(lastTriedModelId.get())
    }

    override fun stream(request: ModelRequest, onDelta: (String) -> Unit): ModelResponse {
        val generation = cancelGeneration.get()
        val now = clock()
        val order = ordered()
        val available = order.filter { quota.isAvailable(it.providerId, it.modelId, now) }
        if (available.isEmpty()) return exhausted(order, now)
        val skip = avoidModelId.getAndSet(null)
        val candidates = if (skip.isNullOrBlank()) {
            available
        } else {
            available.filter { it.modelId != skip }.ifEmpty { available }
        }
        var last: ModelResponse.Failure? = null
        for (endpoint in candidates) {
            if (cancelGeneration.get() != generation) return ModelResponse.Failure("Cancelled")
            if (!quota.isAvailable(endpoint.providerId, endpoint.modelId, clock())) continue
            lastTriedModelId.set(endpoint.modelId)
            val response = gatewayFor(endpoint).stream(request, onDelta)
            if (cancelGeneration.get() != generation) return ModelResponse.Failure("Cancelled")
            if (response !is ModelResponse.Failure) {
                quota.noteSuccess(endpoint.providerId, endpoint.modelId)
                return response
            }
            if (response.message == "Cancelled") return response
            val safe = redact(response.message, endpoint)
            quota.noteFailure(endpoint.providerId, endpoint.modelId, safe, clock())
            last = ModelResponse.Failure(safe)
        }
        val after = clock()
        if (order.none { quota.isAvailable(it.providerId, it.modelId, after) }) {
            return exhausted(order, after)
        }
        return last ?: ModelResponse.Failure("Every helper failed this step.")
    }

    override fun cancel() {
        cancelGeneration.incrementAndGet()
        gateways.values.forEach { it.cancel() }
    }

    private fun exhausted(order: List<ProviderEndpoint>, now: Long): ModelResponse.Failure {
        val resume = quota.earliestResumeAt(order, now)
        val rejected = order.all { quota.isKeyRejected(it.providerId) }
        val text = buildString {
            append("ALL_HELPERS_EXHAUSTED")
            if (rejected || resume == null) append("\nno-auto-resume")
            if (resume != null) append("\nresume-at-ms:").append(resume)
            if (order.any { it.providerId == "openrouter" }) append("\nopenrouter")
        }
        return ModelResponse.Failure(text)
    }

    private fun ordered(): List<ProviderEndpoint> {
        val rank = ranking().withIndex().associate { it.value to it.index }
        return endpoints.sortedWith(
            compareBy<ProviderEndpoint>(
                { if (preferredModel.isNotBlank() && it.modelId == preferredModel) 0 else 1 },
                { rank[it.modelId] ?: 10_000 },
                { it.plainName }
            )
        )
    }

    private fun gatewayFor(endpoint: ProviderEndpoint): ModelGateway {
        val cacheKey = endpoint.providerId + "\n" + endpoint.modelId
        installed[cacheKey]?.let { return it }
        return gateways.getOrPut(cacheKey) {
            val listener: (Int, Int?, Long?) -> Unit = { code, remaining, reset ->
                quota.noteHttp(endpoint.providerId, endpoint.modelId, code, remaining, reset)
            }
            if (connectionFactory != null) {
                RemoteHttpGateway(
                    endpoint.baseUrl,
                    endpoint.apiKey,
                    endpoint.modelId,
                    timeoutMillis,
                    connectionFactory,
                    extraHeaders,
                    onQuota = listener
                )
            } else {
                RemoteHttpGateway(
                    endpoint.baseUrl,
                    endpoint.apiKey,
                    endpoint.modelId,
                    timeoutMillis = timeoutMillis,
                    extraHeaders = extraHeaders,
                    onQuota = listener
                )
            }
        }
    }

    private fun redact(text: String, endpoint: ProviderEndpoint): String {
        var safe = text
        if (endpoint.apiKey.isNotBlank()) safe = safe.replace(endpoint.apiKey, "[redacted]")
        safe = safe.replace(Regex("""/accounts/[a-fA-F0-9]{32}"""), "/accounts/[account]")
        return safe
    }

    companion object {
        fun build(
            settings: ModelSettings,
            timeoutMillis: Int = 60_000,
            connectionFactory: ((String) -> HttpURLConnection)? = null,
            quotaFile: File? = null,
            rankingDirectory: File? = null,
            clock: () -> Long = { System.currentTimeMillis() },
            probe: Boolean = false,
            extraHeaders: Map<String, String> = emptyMap()
        ): ModelGateway? {
            val connect: (String) -> HttpURLConnection = { url ->
                if (connectionFactory != null) connectionFactory(url) else java.net.URL(url).openConnection() as HttpURLConnection
            }
            val expanded = OpenRouterFreeModels.apply(ProviderCatalog.endpoints(settings), connect, clock())
            val endpoints = ModelIdCheck.filter(expanded, connect = connect)
            if (endpoints.isEmpty()) return null
            if (endpoints.size == 1 && endpoints.single().providerId == "custom") {
                val endpoint = endpoints.single()
                return if (connectionFactory != null) {
                    RemoteHttpGateway(endpoint.baseUrl, endpoint.apiKey, endpoint.modelId, timeoutMillis, connectionFactory, extraHeaders)
                } else {
                    RemoteHttpGateway(endpoint.baseUrl, endpoint.apiKey, endpoint.modelId, timeoutMillis = timeoutMillis, extraHeaders = extraHeaders)
                }
            }
            // Setup and ordinary work both try every helper. probe no longer stops after the first one.
            val rotation = ProviderRotatingGateway(
                endpoints = endpoints,
                quota = ProviderQuotaBook(quotaFile),
                ranking = { ModelRanking.load(rankingDirectory) },
                preferredModel = settings.normalized().preferredModel,
                clock = clock,
                connectionFactory = connectionFactory,
                timeoutMillis = timeoutMillis,
                extraHeaders = extraHeaders
            )
            return if (probe) rotation else rotation
        }
    }
}
