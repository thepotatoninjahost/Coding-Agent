package com.codingagent.model

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ONE JOB: Read how many free OpenRouter requests are left today.
 * The number comes from OpenRouter's key page. A failed lookup is ignored.
 */
object OpenRouterDailyBudget {
    @Volatile
    private var remainingRequests: Int? = null

    fun clear() {
        remainingRequests = null
    }

    fun remaining(): Int? = remainingRequests

    fun noteRemaining(left: Int?) {
        remainingRequests = left
    }

    fun sentence(): String? {
        val left = remainingRequests ?: return null
        return " $left free requests left today."
    }

    fun refresh(
        baseUrl: String,
        apiKey: String,
        connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
    ) {
        if (!baseUrl.contains("openrouter.ai") || apiKey.isBlank()) return
        val root = baseUrl.trim().trimEnd('/')
        val url = if (root.endsWith("/v1")) "$root/key" else "$root/api/v1/key"
        val connection = connect(url)
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", RemoteHttpGateway.USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).optJSONObject("data") ?: return
            val nested = data.optJSONObject("free_tier")?.optJSONObject("requests")?.optInt("remaining", -1)
                ?: -1
            val flat = data.optJSONObject("free_model_daily_requests")?.optInt("remaining", -1) ?: -1
            val left = listOf(nested, flat).firstOrNull { it >= 0 } ?: return
            remainingRequests = left
        } catch (_: Exception) {
            // A missing daily count must not stop the helper from answering.
        } finally {
            connection.disconnect()
        }
    }
}
