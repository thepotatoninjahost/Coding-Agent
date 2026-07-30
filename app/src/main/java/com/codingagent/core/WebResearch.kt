package com.codingagent.core

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

interface WebResearchProvider {
    fun search(query: String, limit: Int = 6): ResearchResult
}

class DuckDuckGoResearchProvider(
    private val timeoutMillis: Int = 10_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val instant = fetchJson("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        if (instant.hits.isNotEmpty()) return instant.copy(query = query, hits = instant.hits.take(limit))
        val html = fetchHtml("https://html.duckduckgo.com/html/?q=$encoded")
        if (html.error != null) return ResearchResult(query, instant.hits.take(limit), html.error)
        val hits = HtmlResearchParser.parse(html.body).take(limit)
        return if (hits.isEmpty()) ResearchResult(query, instant.hits.take(limit), "No technical sources found") else ResearchResult(query, hits)
    }

    private fun fetchJson(url: String): ResearchResult {
        val connection = connectionFactory(url)
        return try {
            configure(connection, "application/json")
            if (connection.responseCode !in 200..299) return ResearchResult(url, emptyList(), "HTTP ${connection.responseCode}")
            val json = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val hits = mutableListOf<ResearchHit>()
            json.optString("AbstractText").takeIf { it.isNotBlank() }?.let { hits += ResearchHit(json.optString("Heading", "DuckDuckGo result"), json.optString("AbstractURL"), it) }
            addTopics(json.optJSONArray("RelatedTopics"), hits, 12)
            ResearchResult(url, hits)
        } catch (error: Exception) {
            ResearchResult(url, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchHtml(url: String): HtmlFetch {
        val connection = connectionFactory(url)
        return try {
            configure(connection, "text/html")
            if (connection.responseCode !in 200..299) return HtmlFetch("", "HTTP ${connection.responseCode}")
            HtmlFetch(connection.inputStream.bufferedReader().use { it.readText() }, null)
        } catch (error: Exception) {
            HtmlFetch("", error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(connection: HttpURLConnection, accept: String) {
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "CodingAgent/0.2")
        connection.setRequestProperty("Accept", accept)
    }

    private fun addTopics(topics: JSONArray?, hits: MutableList<ResearchHit>, limit: Int) {
        if (topics == null || hits.size >= limit) return
        for (index in 0 until topics.length()) {
            if (hits.size >= limit) break
            val item = topics.optJSONObject(index) ?: continue
            val text = item.optString("Text")
            val url = item.optString("FirstURL")
            if (text.isNotBlank() && url.isNotBlank()) hits += ResearchHit(text.substringBefore(" - "), url, text)
            addTopics(item.optJSONArray("Topics"), hits, limit)
        }
    }
}

data class HtmlFetch(val body: String, val error: String?)

object HtmlResearchParser {
    private val resultPattern = Pattern.compile("<a[^>]+class=\\\"result__a\\\"[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>.*?<a[^>]+class=\\\"result__snippet\\\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

    fun parse(html: String): List<ResearchHit> {
        val matcher = resultPattern.matcher(html)
        val hits = mutableListOf<ResearchHit>()
        while (matcher.find()) {
            val url = decodeUrl(matcher.group(1).orEmpty())
            val title = clean(matcher.group(2).orEmpty())
            val excerpt = clean(matcher.group(3).orEmpty())
            if (url.isNotBlank() && title.isNotBlank()) hits += ResearchHit(title, url, excerpt)
        }
        return hits
    }

    private fun decodeUrl(raw: String): String {
        val value = raw.replace("&amp;", "&")
        return runCatching {
            URI(value).getQuery()?.split('&')?.firstOrNull { it.startsWith("uddg=") }?.substringAfter("uddg=")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: value
        }.getOrDefault(value)
    }

    private fun clean(raw: String): String = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").replace("&amp;", "&").replace("&#x27;", "'").trim()
}
