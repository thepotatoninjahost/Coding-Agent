package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.regex.Pattern

enum class ResearchMode { BROAD, EXPERIMENTAL, THEORETICAL, EMPIRICAL }

object ResearchModeDetector {
    fun detect(request: String): ResearchMode = when {
        Regex("\\b(theoretical|theory|formal model|proof|foundations|hypothesis)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) -> ResearchMode.THEORETICAL
        Regex("\\b(experimental|experiment|prototype|novel|unconventional|new paradigm|research-style)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) -> ResearchMode.EXPERIMENTAL
        Regex("\\b(benchmark|evaluation|ablation|performance|comparison|measure)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) -> ResearchMode.EMPIRICAL
        else -> ResearchMode.BROAD
    }
}

interface DeepResearchProvider {
    fun deepResearch(query: String, targetSources: Int = 50, mode: ResearchMode = ResearchMode.BROAD, onProgress: (DeepResearchProgress) -> Unit = {}): ResearchSession
}

data class DeepResearchProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val successful: Int = 0,
    val failed: Int = 0,
    val message: String = ""
)

data class ResearchSource(
    val title: String,
    val url: String,
    val content: String,
    val wordCount: Int,
    val codeExamples: List<String>,
    val lane: String = "primary"
)

data class ResearchSession(
    val id: String,
    val query: String,
    val mode: ResearchMode,
    val sources: List<ResearchSource>,
    val brief: String,
    val createdAt: Long = System.currentTimeMillis()
)

class DurableDeepResearchProvider(
    private val researchRoot: File,
    private val searchProvider: WebResearchProvider = CompositeWebResearchProvider(),
    private val maxSourceFetches: Int = 40
) : DeepResearchProvider {

    private val sessionsDir = File(researchRoot, "sessions").apply { mkdirs() }

    override fun deepResearch(
        query: String,
        targetSources: Int,
        mode: ResearchMode,
        onProgress: (DeepResearchProgress) -> Unit
    ): ResearchSession {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "Research query is required" }
        val effectiveTarget = targetSources.coerceIn(2, 30)
        val alreadyLearned = recent(50).flatMap { it.sources }.map { it.url.substringBefore('#').lowercase() }.toSet()
        val salt = (System.currentTimeMillis() / 60_000L).toString()
        val lanes = QueryLanes.expand(normalized, mode, salt)
        onProgress(DeepResearchProgress("searching", 0, effectiveTarget, message = "Expanding ${lanes.size} lanes"))

        val candidates = lanes.flatMap { lane ->
            searchProvider.search(lane.query, 16).hits
                .filter { hit -> SourceQuality.isAcceptable(hit.url, hit.title, hit.excerpt) }
                .filter { hit -> hit.url.substringBefore('#').lowercase() !in alreadyLearned }
                .map { Candidate(it, lane.name) }
        }.dedupe().sortedByDescending { SourceQuality.rankBoost(it.url) }.toMutableList()

        if (candidates.size > effectiveTarget) {
            val selected = mutableListOf<Candidate>()
            selected += candidates.take(effectiveTarget / 2)
            candidates.filter { candidate -> selected.none { it.url == candidate.url } }.forEach { candidate -> if (selected.size < effectiveTarget) selected += candidate }
            candidates.clear()
            candidates += selected
        }

        if (candidates.size < effectiveTarget) {
            val fallbackQueries = listOf(
                "$normalized site:github.com",
                "$normalized site:stackoverflow.com",
                "$normalized android kotlin",
                "$normalized documentation"
            )
            val fallback = fallbackQueries.flatMap { fq ->
                searchProvider.search(fq, 10).hits
                    .filter { hit -> SourceQuality.isAcceptable(hit.url, hit.title, hit.excerpt) }
                    .filter { hit -> hit.url.substringBefore('#').lowercase() !in alreadyLearned }
                    .map { Candidate(it, "fallback") }
            }.dedupe()
            fallback.forEach { candidate -> if (candidates.size < effectiveTarget && candidates.none { it.url == candidate.url }) candidates += candidate }
        }

        if (candidates.size < effectiveTarget) {
            val learned = recent(20).flatMap { it.sources }
                .filter { it.url.substringBefore('#').lowercase() !in alreadyLearned }
                .map { Candidate(ResearchHit(it.title, it.url, it.content.take(200)), "memory") }
            learned.forEach { candidate -> if (candidates.size < effectiveTarget) candidates += candidate }
        }

        val diverse = selectDiverse(candidates, effectiveTarget).take(maxSourceFetches)
        onProgress(DeepResearchProgress("fetching", 0, diverse.size, message = "Fetching ${diverse.size} sources"))

        val sources = mutableListOf<ResearchSource>()
        var failed = 0
        diverse.forEachIndexed { index, candidate ->
            val fetched = runCatching { ArticleExtractor.fetch(candidate.url) }.getOrNull()
            if (fetched != null && fetched.wordCount >= 40) {
                sources += ResearchSource(
                    title = candidate.title.ifBlank { fetched.title },
                    url = candidate.url,
                    content = fetched.text,
                    wordCount = fetched.wordCount,
                    codeExamples = fetched.codeBlocks.take(8),
                    lane = candidate.lane
                )
            } else {
                failed++
            }
            onProgress(DeepResearchProgress("fetching", index + 1, diverse.size, sources.size, failed))
        }

        val brief = buildBrief(normalized, mode, sources)
        val session = ResearchSession(
            id = UUID.randomUUID().toString(),
            query = normalized,
            mode = mode,
            sources = sources,
            brief = brief
        )
        persist(session)
        onProgress(DeepResearchProgress("learned", sources.size, sources.size, sources.size, failed, "Learned ${sources.size} distinct full sources"))
        return session
    }

    fun recent(limit: Int = 20): List<ResearchSession> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.take(limit)?.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val sourcesArr = json.optJSONArray("sources") ?: JSONArray()
                val sources = (0 until sourcesArr.length()).mapNotNull { i ->
                    val s = sourcesArr.optJSONObject(i) ?: return@mapNotNull null
                    ResearchSource(
                        title = s.optString("title"),
                        url = s.optString("url"),
                        content = s.optString("content"),
                        wordCount = s.optInt("wordCount"),
                        codeExamples = (s.optJSONArray("codeExamples") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.optString(it) } },
                        lane = s.optString("lane", "primary")
                    )
                }
                ResearchSession(
                    id = json.optString("id"),
                    query = json.optString("query"),
                    mode = runCatching { ResearchMode.valueOf(json.optString("mode", "BROAD")) }.getOrDefault(ResearchMode.BROAD),
                    sources = sources,
                    brief = json.optString("brief"),
                    createdAt = json.optLong("createdAt", file.lastModified())
                )
            }.getOrNull()
        }.orEmpty()
    }

    private fun persist(session: ResearchSession) {
        val file = File(sessionsDir, "${session.id}.json")
        val sourcesArr = JSONArray()
        session.sources.forEach { s ->
            sourcesArr.put(JSONObject()
                .put("title", s.title)
                .put("url", s.url)
                .put("content", s.content.take(12_000))
                .put("wordCount", s.wordCount)
                .put("codeExamples", JSONArray(s.codeExamples))
                .put("lane", s.lane))
        }
        file.writeText(JSONObject()
            .put("id", session.id)
            .put("query", session.query)
            .put("mode", session.mode.name)
            .put("brief", session.brief)
            .put("createdAt", session.createdAt)
            .put("sources", sourcesArr)
            .toString())
    }

    private fun buildBrief(query: String, mode: ResearchMode, sources: List<ResearchSource>): String {
        if (sources.isEmpty()) return "No usable sources for: $query"
        val sb = StringBuilder()
        sb.appendLine("Research brief for: $query")
        sb.appendLine("Mode: $mode | Sources: ${sources.size}")
        sources.forEachIndexed { i, s ->
            sb.appendLine()
            sb.appendLine("[${i + 1}] ${s.title}")
            sb.appendLine(s.url)
            sb.appendLine(s.content.take(900))
            if (s.codeExamples.isNotEmpty()) {
                sb.appendLine("Code excerpts:")
                s.codeExamples.take(2).forEach { sb.appendLine(it.take(400)) }
            }
        }
        return sb.toString()
    }

    private data class Candidate(val hit: ResearchHit, val lane: String) {
        val title get() = hit.title
        val url get() = hit.url
    }

    private fun List<Candidate>.dedupe(): List<Candidate> {
        val seen = LinkedHashMap<String, Candidate>()
        forEach { c ->
            val key = c.url.substringBefore('#').lowercase()
            if (key.isNotBlank()) seen.putIfAbsent(key, c)
        }
        return seen.values.toList()
    }

    private fun selectDiverse(candidates: List<Candidate>, target: Int): List<Candidate> {
        if (candidates.size <= target) return candidates
        val byDomain = LinkedHashMap<String, MutableList<Candidate>>()
        candidates.forEach { c ->
            val domain = runCatching { URI(c.url).host?.removePrefix("www.") ?: "unknown" }.getOrDefault("unknown")
            byDomain.getOrPut(domain) { mutableListOf() }.add(c)
        }
        val result = mutableListOf<Candidate>()
        var round = 0
        while (result.size < target && byDomain.values.any { it.isNotEmpty() }) {
            byDomain.entries.sortedByDescending { authority(it.key) }.forEach { (_, list) ->
                if (result.size < target && list.isNotEmpty()) {
                    result += list.removeAt(0)
                }
            }
            round++
            if (round > 20) break
        }
        return result
    }

    private fun authority(domain: String): Int = when {
        domain.contains("developer.android.com") || domain.contains("kotlinlang.org") || domain.contains("sqlite.org") -> 5
        domain.contains("rfc-editor.org") || domain.contains("github.com") -> 4
        domain.contains("stackoverflow.com") -> 3
        else -> 1
    }
}

object QueryLanes {
    fun expand(query: String, mode: ResearchMode = ResearchMode.BROAD, salt: String = ""): List<ResearchLane> {
        val base = query.trim()
        val variants = mutableListOf(
            ResearchLane("primary", base),
            ResearchLane("code", "$base code example OR snippet OR implementation"),
            ResearchLane("docs", "$base documentation OR guide OR reference"),
            ResearchLane("android", "$base Android Kotlin"),
            ResearchLane("github", "$base site:github.com"),
            ResearchLane("so", "$base site:stackoverflow.com")
        )
        when (mode) {
            ResearchMode.EXPERIMENTAL -> variants += ResearchLane("experimental", "$base experimental OR prototype OR novel")
            ResearchMode.THEORETICAL -> variants += ResearchLane("theory", "$base theory OR formal OR model")
            ResearchMode.EMPIRICAL -> variants += ResearchLane("benchmark", "$base benchmark OR performance OR evaluation")
            else -> {}
        }
        val rotated = if (salt.isBlank()) variants else {
            val shift = (salt.hashCode().and(0x7fffffff)) % variants.size
            variants.drop(shift) + variants.take(shift)
        }
        return rotated
    }
}

data class ResearchLane(val name: String, val query: String)

/** Reject Wikipedia talk/disambiguation boilerplate and other low-signal pages. */
object SourceQuality {
    private val junkTitle = Pattern.compile(
        """\\b(talk|disambiguation|user talk|wikiProject|sandbox|article talk)\\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val junkExcerpt = Pattern.compile(
        """(please help improve|this article needs|not a guidebook|learn how and when to remove|for other uses, see)""",
        Pattern.CASE_INSENSITIVE
    )

    fun isAcceptable(url: String, title: String, excerpt: String): Boolean {
        val u = url.lowercase()
        val t = title.lowercase()
        val e = excerpt.lowercase()
        if (u.contains("wikipedia.org") && (t.contains("talk") || u.contains("talk:") || u.contains("disambiguation"))) return false
        if (junkTitle.matcher(title).find()) return false
        if (junkExcerpt.matcher(excerpt).find()) return false
        if (u.contains("/wiki/Talk:") || u.contains("/wiki/User:") || u.contains("/wiki/Wikipedia:")) return false
        return true
    }

    fun rankBoost(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("developer.android.com") || u.contains("kotlinlang.org") -> 10
            u.contains("github.com") -> 8
            u.contains("stackoverflow.com") -> 7
            u.contains("android.com") || u.contains("developer.apple.com") -> 6
            u.contains("medium.com") || u.contains("dev.to") -> 2
            u.contains("wikipedia.org") -> 1
            else -> 3
        }
    }
}

object ArticleExtractor {
    private val timeoutMillis = 15_000

    data class Extracted(val title: String, val text: String, val wordCount: Int, val codeBlocks: List<String>)

    fun fetch(url: String): Extracted? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            parse(html, url)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(html: String, url: String): Extracted {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), " ")?.trim()?.take(200) ?: url
        val cleaned = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
        val codeBlocks = Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE).findAll(cleaned)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.length in 40..4000 }
            .take(8)
            .toList()
        val text = cleaned.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return Extracted(title, text.take(20_000), words.size, codeBlocks)
    }
}

data class ResearchDisplayState(
    val phase: String = "idle",
    val completed: Int = 0,
    val total: Int = 50,
    val fullSources: Int = 0,
    val failedSources: Int = 0,
    val laneCount: Int = 0,
    val wordCount: Int = 0,
    val codeExamples: Int = 0,
    val canSend: Boolean = true
)
