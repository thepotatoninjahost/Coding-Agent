package com.codingagent.research

import com.codingagent.domain.ResearchHit
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface ResearchProvider {
    fun search(query: String, limit: Int = 10): List<ResearchHit>
}

class DuckDuckGoProvider(
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val timeoutMillis: Int = 12_000
) : ResearchProvider {
    override fun search(query: String, limit: Int): List<ResearchHit> {
        require(query.isNotBlank()) { "A research query is required" }
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        val connection = connectionFactory("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val abstractText = Regex("\"AbstractText\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
            val abstractUrl = Regex("\"AbstractURL\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1).orEmpty()
            if (abstractText.isBlank() || abstractUrl.isBlank()) emptyList() else listOf(ResearchHit(query, abstractUrl, abstractText)).take(limit)
        } finally {
            connection.disconnect()
        }
    }
}

class CompositeResearchProvider(private val providers: List<ResearchProvider> = listOf(DuckDuckGoProvider())) : ResearchProvider {
    override fun search(query: String, limit: Int): List<ResearchHit> {
        require(query.isNotBlank()) { "A research query is required" }
        val seen = linkedMapOf<String, ResearchHit>()
        providers.forEach { provider ->
            runCatching { provider.search(query, limit) }.getOrDefault(emptyList()).forEach { hit ->
                val key = hit.url.substringBefore('#').lowercase()
                if (key.isNotBlank()) seen.putIfAbsent(key, hit)
            }
            if (seen.size >= limit) return@forEach
        }
        return seen.values.take(limit)
    }
}
