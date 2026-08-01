package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.regex.Pattern

interface WebResearchProvider {
    fun search(query: String, limit: Int = 6): ResearchResult
}

/**
 * Aggregates multiple free search backends. DuckDuckGo alone is no longer reliable:
 * the JSON instant API is often empty for technical queries, and the HTML endpoint
 * frequently serves bot-challenge pages instead of results.
 */
class CompositeWebResearchProvider(
    private val providers: List<WebResearchProvider> = listOf(
        GitHubResearchProvider(),
        StackOverflowResearchProvider(),
        DuckDuckGoResearchProvider(),
        WikipediaResearchProvider()
    )
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val merged = LinkedHashMap<String, ResearchHit>()
        val errors = mutableListOf<String>()
        for (provider in providers) {
            val result = runCatching { provider.search(normalized, limit.coerceAtLeast(8)) }
                .getOrElse { ResearchResult(normalized, emptyList(), it.message ?: it.javaClass.simpleName) }
            if (result.error != null && result.hits.isEmpty()) {
                errors += "${provider.javaClass.simpleName}: ${result.error}"
            }
            result.hits.forEach { hit ->
                val key = hit.url.substringBefore('#').trim().lowercase()
                if (key.isNotBlank()) merged.putIfAbsent(key, hit)
            }
            if (merged.size >= limit) break
        }
        val hits = merged.values.take(limit)
        val error = when {
            hits.isNotEmpty() -> null
            errors.isNotEmpty() -> errors.joinToString(" | ")
            else -> "No technical sources found"
        }
        return ResearchResult(query, hits, error)
    }
}

class DuckDuckGoResearchProvider(
    private val timeoutMillis: Int = 12_000,
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
        if (html.body.contains("anomaly-modal", ignoreCase = true) || html.body.contains("challenge", ignoreCase = true)) {
            return ResearchResult(query, instant.hits.take(limit), "DuckDuckGo HTML blocked by bot challenge")
        }
        val hits = HtmlResearchParser.parse(html.body).take(limit)
        return if (hits.isEmpty()) ResearchResult(query, instant.hits.take(limit), "DuckDuckGo returned no technical sources") else ResearchResult(query, hits)
    }

    private fun fetchJson(url: String): ResearchResult {
        val connection = connectionFactory(url)
        return try {
            configure(connection, "application/json")
            if (connection.responseCode !in 200..299) return ResearchResult(url, emptyList(), "HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val hits = mutableListOf<ResearchHit>()
            json.optString("AbstractText").takeIf { it.isNotBlank() }?.let {
                hits += ResearchHit(json.optString("Heading", "DuckDuckGo result"), json.optString("AbstractURL"), it)
            }
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
        connection.setRequestProperty("User-Agent", BROWSER_UA)
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
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

    companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

class WikipediaResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=${limit.coerceIn(1, 20)}"
        val connection = connectionFactory(url)
        return try {
            configure(connection)
            if (connection.responseCode !in 200..299) return ResearchResult(query, emptyList(), "Wikipedia HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val search = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            val hits = mutableListOf<ResearchHit>()
            for (i in 0 until search.length()) {
                val item = search.optJSONObject(i) ?: continue
                val title = item.optString("title")
                if (title.isBlank()) continue
                if (title.contains("Talk:", ignoreCase = true) || title.contains("Disambiguation", ignoreCase = true) || title.startsWith("User:", ignoreCase = true) || title.startsWith("Wikipedia:", ignoreCase = true)) continue
                val pageUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8.toString()).replace("+", "%20")
                val snippet = item.optString("snippet").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                if (snippet.contains("please help improve", ignoreCase = true) || snippet.contains("this article needs", ignoreCase = true)) continue
                hits += ResearchHit(title, pageUrl, snippet.ifBlank { "Wikipedia article: $title" })
            }
            if (hits.isEmpty()) ResearchResult(query, emptyList(), "Wikipedia returned no results") else ResearchResult(query, hits.take(limit))
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(connection: HttpURLConnection) {
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "CodingAgent/0.3 (Android research; +https://github.com/codingagent)")
        connection.setRequestProperty("Accept", "application/json")
    }
}

class GitHubResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val repoUrl = "https://api.github.com/search/repositories?q=$encoded&per_page=${limit.coerceIn(1, 10)}&sort=stars"
        val codeUrl = "https://api.github.com/search/code?q=$encoded+in:file&per_page=${limit.coerceIn(1, 5)}"
        val hits = mutableListOf<ResearchHit>()
        val errors = mutableListOf<String>()
        fetchJson(repoUrl)?.let { json ->
            val items = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("full_name")
                val html = item.optString("html_url")
                val desc = item.optString("description")
                if (name.isNotBlank() && html.isNotBlank()) {
                    hits += ResearchHit(name, html, desc.ifBlank { "GitHub repository: $name" })
                }
            }
        } ?: errors.add("GitHub repo search failed")
        runCatching {
            fetchJson(codeUrl)?.optJSONArray("items")?.let { items ->
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val path = item.optString("path")
                    val html = item.optString("html_url")
                    val repo = item.optJSONObject("repository")?.optString("full_name").orEmpty()
                    if (html.isNotBlank()) hits += ResearchHit("$repo/$path", html, "GitHub code match: $path")
                }
            }
        }
        return if (hits.isEmpty()) ResearchResult(query, emptyList(), errors.joinToString(" | ").ifBlank { "GitHub returned no results" })
        else ResearchResult(query, hits.distinctBy { it.url }.take(limit))
    }

    private fun fetchJson(url: String): JSONObject? {
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "CodingAgent/0.3")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode !in 200..299) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

class StackOverflowResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val url = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=$encoded&site=stackoverflow&pagesize=${limit.coerceIn(1, 10)}&filter=default"
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "CodingAgent/0.3")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode !in 200..299) return ResearchResult(query, emptyList(), "StackOverflow HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = json.optJSONArray("items") ?: JSONArray()
            val hits = mutableListOf<ResearchHit>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("title").replace(Regex("&#\\d+;"), " ").replace("&" + "quot;", "\"")
                val link = item.optString("link")
                val tags = item.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                if (title.isNotBlank() && link.isNotBlank()) {
                    hits += ResearchHit(title, link, "Stack Overflow · ${tags.take(4).joinToString()}")
                }
            }
            if (hits.isEmpty()) ResearchResult(query, emptyList(), "Stack Overflow returned no results")
            else ResearchResult(query, hits.take(limit))
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}

data class HtmlFetch(val body: String, val error: String?)

object HtmlResearchParser {
    private val resultPatterns = listOf(
        Pattern.compile(
            "<a[^>]+class=\\\"result__a\\\"[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>.*?<a[^>]+class=\\\"result__snippet\\\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        ),
        Pattern.compile(
            "<a[^>]+class=\\\"[^\\\"]*result__a[^\\\"]*\\\"[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        ),
        Pattern.compile(
            "<a[^>]+rel=\\\"nofollow\\\"[^>]+class=\\\"[^\\\"]*result[^\\\"]*\\\"[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
    )

    fun parse(html: String): List<ResearchHit> {
        val hits = mutableListOf<ResearchHit>()
        for (pattern in resultPatterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val url = decodeUrl(matcher.group(1).orEmpty())
                val title = clean(matcher.group(2).orEmpty())
                val excerpt = if (matcher.groupCount() >= 3) clean(matcher.group(3).orEmpty()) else title
                if (url.isNotBlank() && title.isNotBlank() && !url.contains("duckduckgo.com")) {
                    hits += ResearchHit(title, url, excerpt)
                }
            }
            if (hits.isNotEmpty()) break
        }
        return hits.distinctBy { it.url }
    }

    private fun decodeUrl(raw: String): String {
        val value = raw.replace("&" + "amp;", "&")
        return runCatching {
            URI(value).query?.split('&')?.firstOrNull { it.startsWith("uddg=") }?.substringAfter("uddg=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: value
        }.getOrDefault(value)
    }

    private fun clean(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").replace("&" + "amp;", "&").replace("&#x27;", "'").trim()
}
