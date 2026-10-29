package com.codingagent.model

import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * ONE JOB: Drop helper names the provider does not list.
 * A failed or timed-out lookup keeps the names, so a dead network does not wipe every helper.
 */
object ModelIdCheck {
    fun filter(
        endpoints: List<ProviderEndpoint>,
        timeoutMillis: Int = 800,
        connect: (String) -> HttpURLConnection
    ): List<ProviderEndpoint> {
        if (endpoints.isEmpty()) return endpoints
        val listed = mutableMapOf<String, Set<String>?>()
        val kept = endpoints.filter { endpoint ->
            val base = endpoint.baseUrl.trimEnd('/')
            val ids = listed.getOrPut(base) { fetchIds(base, endpoint.apiKey, connect, timeoutMillis) }
            ids == null || endpoint.modelId in ids
        }
        return kept
    }

    private fun fetchIds(
        base: String,
        apiKey: String,
        connect: (String) -> HttpURLConnection,
        timeoutMillis: Int
    ): Set<String>? {
        val connection = try {
            connect("$base/models")
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", RemoteHttpGateway.USER_AGENT)
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(text).optJSONArray("data") ?: return null
            buildSet {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
