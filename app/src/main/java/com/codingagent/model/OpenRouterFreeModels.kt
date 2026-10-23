package com.codingagent.model

import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * ONE JOB: Choose free OpenRouter helpers from the live model list.
 * A price of zero for both the question and the answer counts as free.
 * Helpers that can use tools come first. The saved list is only the offline fallback.
 */
object OpenRouterFreeModels {
    const val RETIRED_ID = "minimax/minimax-m3.1-flash:free"
    private const val CACHE_MS = 30L * 60L * 1000L
    private const val LIMIT = 8
    private val lock = Any()
    private var cachedBase: String = ""
    private var cachedAt: Long = 0L
    private var cachedIds: List<String> = emptyList()

    fun clearCache() {
        synchronized(lock) {
            cachedBase = ""
            cachedAt = 0L
            cachedIds = emptyList()
        }
    }

    fun isRetired(modelId: String): Boolean = modelId.trim().equals(RETIRED_ID, ignoreCase = true)

    fun apply(
        endpoints: List<ProviderEndpoint>,
        connect: (String) -> HttpURLConnection,
        now: Long = System.currentTimeMillis()
    ): List<ProviderEndpoint> {
        val kept = endpoints.filterNot { isRetired(it.modelId) }
        val openRouter = kept.filter { it.providerId == "openrouter" }
        if (openRouter.isEmpty()) return kept
        val sample = openRouter.first()
        val live = rankedIds(sample.baseUrl, sample.apiKey, connect, now)
        if (live.isEmpty()) return kept
        val chosen = live.map { id ->
            ProviderEndpoint("openrouter", id.substringAfter('/').substringBefore(':'), id, sample.baseUrl, sample.apiKey)
        }
        val ownerPick = openRouter.filter { endpoint ->
            endpoint.plainName == "Chosen helper" && chosen.none { it.modelId == endpoint.modelId }
        }
        val others = kept.filter { it.providerId != "openrouter" }
        return (chosen + ownerPick + others).distinctBy { it.providerId + "\n" + it.modelId }
    }

    fun rankJson(json: String, preferredOrder: List<String>): List<String> {
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val models = mutableListOf<FreeModel>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank() || isRetired(id)) continue
            if (!isFreePrice(item.optJSONObject("pricing"))) continue
            val tools = supportsTools(item)
            val context = item.optInt("context_length", 0)
            models += FreeModel(id, tools, context)
        }
        if (models.isEmpty()) return emptyList()
        val withTools = models.filter { it.tools }
        val withoutTools = models.filter { !it.tools }
        return (order(withTools, preferredOrder) + order(withoutTools, preferredOrder))
            .distinct()
            .take(LIMIT)
    }

    private fun rankedIds(
        base: String,
        apiKey: String,
        connect: (String) -> HttpURLConnection,
        now: Long
    ): List<String> {
        synchronized(lock) {
            if (cachedIds.isNotEmpty() && cachedBase == base && now - cachedAt < CACHE_MS) return cachedIds
        }
        val json = fetch(base, apiKey, connect) ?: return emptyList()
        val preferred = ProviderCatalog.bundled.filter { it.providerId == "openrouter" }.map { it.modelId }
        val ids = rankJson(json, preferred)
        if (ids.isEmpty()) return emptyList()
        synchronized(lock) {
            cachedBase = base
            cachedAt = now
            cachedIds = ids
        }
        return ids
    }

    private fun fetch(base: String, apiKey: String, connect: (String) -> HttpURLConnection): String? {
        val connection = try {
            connect(base.trimEnd('/') + "/models")
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", RemoteHttpGateway.USER_AGENT)
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun order(models: List<FreeModel>, preferredOrder: List<String>): List<String> {
        val ids = models.map { it.id }.toSet()
        val known = preferredOrder.filter { it in ids }
        val rest = models.filter { it.id !in known }.sortedByDescending { it.context }.map { it.id }
        return known + rest
    }

    private fun isFreePrice(pricing: JSONObject?): Boolean {
        if (pricing == null) return false
        return priceIsZero(pricing.opt("prompt")) && priceIsZero(pricing.opt("completion"))
    }

    private fun priceIsZero(value: Any?): Boolean {
        val text = value?.toString()?.trim().orEmpty()
        val number = text.toDoubleOrNull() ?: return false
        return number == 0.0
    }

    private fun supportsTools(item: JSONObject): Boolean {
        val parameters = item.optJSONArray("supported_parameters") ?: return false
        for (i in 0 until parameters.length()) {
            if (parameters.optString(i).equals("tools", ignoreCase = true)) return true
        }
        return false
    }

    private data class FreeModel(val id: String, val tools: Boolean, val context: Int)
}
