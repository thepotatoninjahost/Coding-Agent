package com.codingagent.research

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.regex.Pattern
import com.codingagent.workspace.ResearchHit
import com.codingagent.workspace.ResearchResult

/**
 * ONE JOB: Web search and page fetch for external evidence.
 */
interface WebResearchProvider {
    fun search(query: String, limit: Int = 6): ResearchResult
}

internal fun HttpURLConnection.disallowRedirects() {
    instanceFollowRedirects = false
}

internal fun InputStream.readCapped(maxBytes: Int = 1_048_576): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) error("Research response exceeded $maxBytes bytes")
            out.write(buffer, 0, count)
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

/**
 * Free-only search stack. No paid APIs.
 *
 * Priority:
 *  1. GitHub releases and official docs pages when the question matches
 *  2. Wikipedia and Hacker News (no key)
 *  3. GitHub repository search and Stack Overflow
 *  4. Searx, MDN, then DuckDuckGo
 *
 * A source that fails is explained in plain English. The search keeps going.
 */
class CompositeWebResearchProvider(
    private val providers: List<WebResearchProvider> = listOf(
        GitHubReleasesResearchProvider(),
        OfficialDocsResearchProvider(),
        WikipediaResearchProvider(),
        HackerNewsResearchProvider(),
        GitHubResearchProvider(),
        StackOverflowResearchProvider(),
        BingRssResearchProvider(),
        SearxResearchProvider(),
        MdnResearchProvider(),
        DuckDuckGoResearchProvider()
    )
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val terms = SourceQuality.queryTerms(normalized)
        val merged = LinkedHashMap<String, ResearchHit>()
        val errors = mutableListOf<String>()
        for (provider in providers) {
            val result = runCatching { provider.search(normalized, (limit * 2).coerceAtLeast(8)) }
                .getOrElse { ResearchResult(normalized, emptyList(), it.message ?: it.javaClass.simpleName) }
            if (result.error != null && result.hits.isEmpty()) {
                errors += ResearchErrors.plain(result.error)
            }
            result.hits
                .filter { SourceQuality.isAcceptable(it.url, it.title, it.excerpt, normalized) }
                .filter { terms.isEmpty() || SourceQuality.hasQueryRelevance(terms, it.title, it.excerpt, it.url) }
                .forEach { hit ->
                    val key = hit.url.substringBefore('#').trim().lowercase()
                    if (key.isNotBlank()) merged.putIfAbsent(key, hit)
                }
            if (merged.size >= limit && provider !is DuckDuckGoResearchProvider) break
        }
        val hits = merged.values
            .sortedByDescending {
                val score = if (terms.isEmpty()) 0 else SourceQuality.relevanceScore(terms, it)
                score + SourceQuality.rankBoost(it.url)
            }
            .take(limit)
        val error = when {
            hits.isNotEmpty() -> null
            errors.isNotEmpty() -> errors.joinToString(" | ")
            else -> "No technical sources found"
        }
        return ResearchResult(query, hits, error)
    }
}

/** Bing's public RSS search. No key. Structured lists are tried first. */
class BingRssResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val connection = connectionFactory("https://www.bing.com/search?q=$encoded&format=rss")
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            if (connection.responseCode !in 200..299) {
                return ResearchResult(query, emptyList(), "Bing search did not answer.")
            }
            val body = connection.inputStream.readCapped()
            val cap = limit.coerceIn(1, 5)
            val hits = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE).findAll(body).mapNotNull { match ->
                val item = match.groupValues[1]
                val title = tag(item, "title")
                val link = tag(item, "link")
                if (title.isBlank() || link.isBlank()) return@mapNotNull null
                val excerpt = StructuredSources.readableText(tag(item, "description")).take(1500)
                ResearchHit(title, link, excerpt.ifBlank { title })
            }.take(cap).toList()
            if (hits.isEmpty()) ResearchResult(query, emptyList(), "Bing search returned nothing.") else ResearchResult(query, hits)
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun tag(item: String, name: String): String =
        Regex("<$name[^>]*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:]]>)?</$name>", RegexOption.IGNORE_CASE)
            .find(item)?.groupValues?.get(1)?.trim().orEmpty()
}

/** Public Searx/SearxNG instances — free meta-search. No API key. */
class SearxResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val instances: List<String> = listOf(
        "https://searx.be",
        "https://search.sapti.me",
        "https://searx.tiekoetter.com",
        "https://searx.work"
    )
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val errors = mutableListOf<String>()
        for (base in instances) {
            val url = "$base/search?q=$encoded&format=json&categories=general,science,it"
            val connection = connectionFactory(url)
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.disallowRedirects()
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) {
                    errors += "$base HTTP ${connection.responseCode}"
                    continue
                }
                val body = connection.inputStream.readCapped()
                if (body.trimStart().startsWith("<")) {
                    errors += "$base returned a web page instead of search results"
                    continue
                }
                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    errors += "$base returned a web page instead of search results"
                    continue
                }
                val results = json.optJSONArray("results") ?: JSONArray()
                val hits = mutableListOf<ResearchHit>()
                for (i in 0 until results.length()) {
                    if (hits.size >= limit) break
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val link = item.optString("url").trim()
                    val content = item.optString("content").ifBlank { item.optString("snippet") }.trim()
                    if (title.isBlank() || link.isBlank()) continue
                    val low = link.lowercase()
                    if (low.contains("wikipedia.org/wiki/talk:") || low.contains("wikipedia.org/wiki/user:")) continue
                    hits += ResearchHit(title, link, content.ifBlank { title })
                }
                if (hits.isNotEmpty()) return ResearchResult(query, hits.take(limit))
                errors += "$base empty"
            } catch (error: Exception) {
                errors += "$base ${error.message ?: error.javaClass.simpleName}"
            } finally {
                connection.disconnect()
            }
        }
        return ResearchResult(query, emptyList(), errors.joinToString(" | ").ifBlank { "Searx returned no results" })
    }
}

/** MDN Web Docs search — free, high-signal for web / UI platform questions. */
class MdnResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val url = "https://developer.mozilla.org/api/v1/search?q=$encoded&locale=en-US"
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
                connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                return ResearchResult(query, emptyList(), "MDN HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.readCapped())
            val documents = json.optJSONArray("documents") ?: JSONArray()
            val hits = mutableListOf<ResearchHit>()
            for (i in 0 until documents.length()) {
                if (hits.size >= limit) break
                val item = documents.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { item.optString("mdn_url") }
                val path = item.optString("mdn_url").ifBlank { item.optString("slug") }
                val summary = item.optString("summary").ifBlank { item.optString("excerpt") }
                if (title.isBlank() || path.isBlank()) continue
                val link = if (path.startsWith("http")) path else "https://developer.mozilla.org$path"
                hits += ResearchHit(title, link, summary.ifBlank { "MDN: $title" })
            }
            if (hits.isEmpty()) ResearchResult(query, emptyList(), "MDN returned no results")
            else ResearchResult(query, hits.take(limit))
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
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
            val json = JSONObject(connection.inputStream.readCapped())
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
            HtmlFetch(connection.inputStream.readCapped(), null)
        } catch (error: Exception) {
            HtmlFetch("", error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(connection: HttpURLConnection, accept: String) {
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.disallowRedirects()
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
            "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)"
    }
}

class GitHubResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val githubQuery = normalized
            .replace(Regex("\\bsite:[\\w.]+"), " ")
            .replace(Regex("\\bOR\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { normalized }
        val encoded = URLEncoder.encode(githubQuery, StandardCharsets.UTF_8.toString())
        val repoUrl = "https://api.github.com/search/repositories?q=$encoded&per_page=${limit.coerceIn(1, 10)}&sort=stars"
        val hits = mutableListOf<ResearchHit>()
        val errors = mutableListOf<String>()
        val fetched = fetchJson(repoUrl)
        fetched.json?.let { json ->
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
        }
        fetched.error?.let { errors.add(it) }
        return if (hits.isEmpty()) {
            ResearchResult(query, emptyList(), errors.joinToString(" | ").ifBlank { "GitHub returned no results" })
        } else {
            ResearchResult(query, hits.distinctBy { it.url }.take(limit))
        }
    }

    private data class JsonFetch(val json: JSONObject?, val error: String?)

    private fun fetchJson(url: String): JsonFetch {
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
                connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = runCatching { connection.errorStream?.readCapped(4_096).orEmpty() }.getOrDefault("")
                val message = runCatching { JSONObject(errBody).optString("message") }.getOrNull().orEmpty().trim()
                val detail = message.ifBlank { "HTTP $code" }.take(240)
                return JsonFetch(null, "GitHub search failed: $detail")
            }
            JsonFetch(JSONObject(connection.inputStream.readCapped()), null)
        } catch (error: Exception) {
            JsonFetch(null, "GitHub search failed: ${error.message ?: error.javaClass.simpleName}")
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
        val soQuery = normalized
            .replace(Regex("\\bsite:[\\w.]+"), " ")
            .replace(Regex("\\bOR\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { normalized }
        val encoded = URLEncoder.encode(soQuery, StandardCharsets.UTF_8.toString())
        val url = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=$encoded&site=stackoverflow&pagesize=${limit.coerceIn(1, 10)}&filter=default"
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
                connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode !in 200..299) return ResearchResult(query, emptyList(), "StackOverflow HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.readCapped())
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

/** Turns certificate errors, rate limits, and raw exceptions into one plain sentence. */
object ResearchErrors {
    fun plain(raw: String): String {
        val parts = raw.split("|", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return "A search site did not answer."
        return parts.map(::one).distinct().joinToString(" ")
    }

    private fun one(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "pkix" in lower || "certificate" in lower || "certpath" in lower || "ssl" in lower ->
                "A website's security certificate could not be checked."
            "429" in lower || "too many requests" in lower ->
                "A search site asked me to slow down."
            "validation failed" in lower || "validation error" in lower ->
                "GitHub did not accept that search."
            "bot" in lower || "challenge" in lower || "anomaly" in lower ->
                "A search site blocked the automatic request."
            "no result" in lower || "empty" in lower || "no usable" in lower || "no technical" in lower ->
                "A search site returned nothing."
            "400" in lower ->
                "A documentation site did not accept that search."
            "timed out" in lower || "timeout" in lower ->
                "A search site took too long to answer."
            else -> "A search site did not answer."
        }
    }
}

class WikipediaResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(SourceQuality.searchKeywords(normalized), StandardCharsets.UTF_8.toString())
        val cap = limit.coerceIn(1, 8)
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=1&format=json&srlimit=$cap"
        return fetchHits(query, url) { json ->
            val items = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) {
                    if (size >= cap) break
                    val item = items.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    if (title.isBlank()) continue
                    val snippet = item.optString("snippet").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                    val link = "https://en.wikipedia.org/wiki/" + title.replace(' ', '_')
                    add(ResearchHit(title, link, snippet.ifBlank { title }))
                }
            }
        }
    }

    private fun fetchHits(query: String, url: String, read: (JSONObject) -> List<ResearchHit>): ResearchResult {
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                return ResearchResult(query, emptyList(), "Wikipedia HTTP ${connection.responseCode}")
            }
            val hits = read(JSONObject(connection.inputStream.readCapped()))
            if (hits.isEmpty()) ResearchResult(query, emptyList(), "Wikipedia returned no results")
            else ResearchResult(query, hits)
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}

class HackerNewsResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), "A research query is required")
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val cap = limit.coerceIn(1, 8)
        val url = "https://hn.algolia.com/api/v1/search?query=$encoded&hitsPerPage=$cap"
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                return ResearchResult(query, emptyList(), "Hacker News HTTP ${connection.responseCode}")
            }
            val items = JSONObject(connection.inputStream.readCapped()).optJSONArray("hits") ?: JSONArray()
            val hits = mutableListOf<ResearchHit>()
            for (i in 0 until items.length()) {
                if (hits.size >= cap) break
                val item = items.optJSONObject(i) ?: continue
                val title = item.optString("title").ifBlank { item.optString("story_title") }.trim()
                if (title.isBlank()) continue
                val link = item.optString("url").trim().ifBlank {
                    val id = item.optString("objectID")
                    if (id.isBlank()) "" else "https://news.ycombinator.com/item?id=$id"
                }
                if (link.isBlank()) continue
                val excerpt = item.optString("story_text").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                hits += ResearchHit(title, link, excerpt.ifBlank { title })
            }
            if (hits.isEmpty()) ResearchResult(query, emptyList(), "Hacker News returned no results")
            else ResearchResult(query, hits)
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}

class GitHubReleasesResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), null)
        val repo = repoFor(normalized.lowercase()) ?: return ResearchResult(query, emptyList(), null)
        val url = "https://api.github.com/repos/$repo/releases/latest"
        val connection = connectionFactory(url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode !in 200..299) {
                return ResearchResult(query, emptyList(), "GitHub releases HTTP ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.readCapped())
            val tag = json.optString("tag_name").trim()
            val name = json.optString("name").ifBlank { tag }.trim()
            val page = json.optString("html_url").trim()
            if (tag.isBlank() || page.isBlank()) return ResearchResult(query, emptyList(), "GitHub releases returned no results")
            val body = json.optString("body").replace(Regex("\\s+"), " ").trim().take(400)
            ResearchResult(query, listOf(ResearchHit("$repo $name", page, "Latest release $tag. $body".trim())))
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun repoFor(query: String): String? {
        val wantsVersion = listOf("version", "latest", "release").any { it in query }
        if (!wantsVersion) return null
        return when {
            "kotlin" in query -> "JetBrains/kotlin"
            "gradle" in query -> "gradle/gradle"
            else -> null
        }
    }
}

class OfficialDocsResearchProvider(
    private val timeoutMillis: Int = 12_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) : WebResearchProvider {
    override fun search(query: String, limit: Int): ResearchResult {
        val normalized = query.trim()
        if (normalized.isBlank()) return ResearchResult(query, emptyList(), null)
        val page = pageFor(normalized.lowercase()) ?: return ResearchResult(query, emptyList(), null)
        val connection = connectionFactory(page.second)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.disallowRedirects()
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html")
            if (connection.responseCode !in 200..299) {
                return ResearchResult(query, emptyList(), "Official docs HTTP ${connection.responseCode}")
            }
            val html = connection.inputStream.readCapped()
            val title = Regex("(?is)<title>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)
                ?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { page.first }
            val text = html.replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("(?is)<style.*?</style>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(500)
            ResearchResult(query, listOf(ResearchHit(title, page.second, text.ifBlank { title })))
        } catch (error: Exception) {
            ResearchResult(query, emptyList(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun pageFor(query: String): Pair<String, String>? = when {
        "kotlin" in query && "cancel" in query ->
            "Kotlin cancellation" to "https://kotlinlang.org/docs/cancellation-and-timeouts.html"
        "kotlin" in query ->
            "Kotlin documentation" to "https://kotlinlang.org/docs/home.html"
        "android" in query ->
            "Android documentation" to "https://developer.android.com/docs"
        "python" in query ->
            "Python documentation" to "https://docs.python.org/3/"
        else -> null
    }
}

private const val USER_AGENT = "Coding-Agent/1.0 (Android; https://github.com/thepotatoninjahost/Coding-Agent)"

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
