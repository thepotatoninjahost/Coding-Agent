package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.LinkedHashMap
import java.util.UUID
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
    fun deepResearch(
        query: String,
        targetSources: Int = 50,
        mode: ResearchMode = ResearchMode.BROAD,
        onProgress: (DeepResearchProgress) -> Unit = {}
    ): ResearchSession
}

/**
 * Durable deep research backed by CompositeWebResearchProvider.
 * Uses ProjectModels ResearchSession / ResearchSource / DeepResearchProgress (no local redeclarations).
 *
 * Quality focus: short/truncated queries (common on mobile) must not expand into
 * tradeoffs / criticism / news lanes. Prefer GitHub, StackOverflow, official docs,
 * and require query-term overlap before accepting a hit.
 */
class DurableDeepResearchProvider(
    private val researchRoot: File,
    private val pageTimeoutMillis: Int = 15_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
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
        onProgress(DeepResearchProgress("searching", 0, effectiveTarget, 0, 0))

        val queryTerms = SourceQuality.queryTerms(normalized)
        val candidates = lanes.flatMap { lane ->
            searchProvider.search(lane.query, 16).hits
                .filter { hit -> SourceQuality.isAcceptable(hit.url, hit.title, hit.excerpt) }
                .filter { hit -> SourceQuality.hasQueryRelevance(queryTerms, hit.title, hit.excerpt, hit.url) }
                .filter { hit -> hit.url.substringBefore('#').lowercase() !in alreadyLearned }
                .map { Candidate(it, lane.name) }
        }.dedupe()
            .sortedByDescending { SourceQuality.relevanceScore(queryTerms, it.hit) + SourceQuality.rankBoost(it.url) }
            .toMutableList()

        if (candidates.size > effectiveTarget) {
            val selected = mutableListOf<Candidate>()
            selected += candidates.take((effectiveTarget / 2).coerceAtLeast(1))
            candidates.filter { c -> selected.none { it.url == c.url } }.forEach { c ->
                if (selected.size < effectiveTarget) selected += c
            }
            candidates.clear()
            candidates += selected
        }

        if (candidates.size < effectiveTarget) {
            // Strict code-host fallbacks only — never broad web for short experimental queries
            val fallbackQueries = listOf(
                "$normalized site:github.com",
                "$normalized site:stackoverflow.com",
                "$normalized experimental OR prototype site:github.com",
                "$normalized android kotlin code"
            )
            val fallback = fallbackQueries.flatMap { fq ->
                searchProvider.search(fq, 10).hits
                    .filter { hit -> SourceQuality.isAcceptable(hit.url, hit.title, hit.excerpt) }
                    .filter { hit -> SourceQuality.hasQueryRelevance(queryTerms, hit.title, hit.excerpt, hit.url) }
                    .filter { hit -> hit.url.substringBefore('#').lowercase() !in alreadyLearned }
                    .map { Candidate(it, "fallback") }
            }.dedupe()
            fallback.forEach { c ->
                if (candidates.size < effectiveTarget && candidates.none { it.url == c.url }) candidates += c
            }
        }

        val diverse = selectDiverse(candidates, effectiveTarget).take(maxSourceFetches)
        onProgress(DeepResearchProgress("fetching", 0, diverse.size, 0, 0))

        val sources = mutableListOf<ResearchSource>()
        var failed = 0
        diverse.forEachIndexed { index, candidate ->
            val fetched = runCatching { ArticleExtractor.fetch(candidate.url, connectionFactory, pageTimeoutMillis) }.getOrNull()
            if (fetched != null && fetched.wordCount >= 40) {
                sources += ResearchSource(
                    title = candidate.title.ifBlank { fetched.title },
                    url = candidate.url,
                    domain = DurableDeepResearchProvider.domainOf(candidate.url),
                    lane = candidate.lane,
                    status = 200,
                    wordCount = fetched.wordCount,
                    content = fetched.text,
                    codeExamples = fetched.codeBlocks.take(8)
                )
            } else {
                failed++
            }
            onProgress(DeepResearchProgress("fetching", index + 1, diverse.size, sources.size, failed))
        }

        val session = ResearchSession(
            id = UUID.randomUUID().toString(),
            query = normalized,
            createdAt = System.currentTimeMillis(),
            requestedSources = effectiveTarget,
            sources = sources,
            learnedChunks = sources.size,
            errors = if (failed > 0) listOf("$failed source fetch(es) failed") else emptyList(),
            mode = mode.name.lowercase()
        )
        persist(session)
        onProgress(DeepResearchProgress("learned", sources.size, sources.size, sources.size, failed))
        return session
    }

    fun recent(limit: Int = 20): List<ResearchSession> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    val sourcesArr = json.optJSONArray("sources") ?: JSONArray()
                    val sources = (0 until sourcesArr.length()).mapNotNull { i ->
                        val s = sourcesArr.optJSONObject(i) ?: return@mapNotNull null
                        ResearchSource(
                            title = s.optString("title"),
                            url = s.optString("url"),
                            domain = s.optString("domain").ifBlank { DurableDeepResearchProvider.domainOf(s.optString("url")) },
                            lane = s.optString("lane", "primary"),
                            status = s.optInt("status", 200),
                            wordCount = s.optInt("wordCount"),
                            content = s.optString("content"),
                            codeExamples = (s.optJSONArray("codeExamples") ?: JSONArray()).let { arr ->
                                (0 until arr.length()).map { arr.optString(it) }
                            },
                            error = s.optString("error").takeIf { it.isNotBlank() }
                        )
                    }
                    ResearchSession(
                        id = json.optString("id"),
                        query = json.optString("query"),
                        createdAt = json.optLong("createdAt", file.lastModified()),
                        requestedSources = json.optInt("requestedSources", sources.size),
                        sources = sources,
                        learnedChunks = json.optInt("learnedChunks", sources.size),
                        errors = (json.optJSONArray("errors") ?: JSONArray()).let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        },
                        mode = json.optString("mode", "broad")
                    )
                }.getOrNull()
            }.orEmpty()
    }

    private fun persist(session: ResearchSession) {
        val file = File(sessionsDir, "${session.id}.json")
        val sourcesArr = JSONArray()
        session.sources.forEach { s ->
            sourcesArr.put(
                JSONObject()
                    .put("title", s.title)
                    .put("url", s.url)
                    .put("domain", s.domain)
                    .put("lane", s.lane)
                    .put("status", s.status)
                    .put("wordCount", s.wordCount)
                    .put("content", s.content.take(12_000))
                    .put("codeExamples", JSONArray(s.codeExamples))
                    .put("error", s.error)
            )
        }
        file.writeText(
            JSONObject()
                .put("id", session.id)
                .put("query", session.query)
                .put("createdAt", session.createdAt)
                .put("requestedSources", session.requestedSources)
                .put("learnedChunks", session.learnedChunks)
                .put("mode", session.mode)
                .put("errors", JSONArray(session.errors))
                .put("sources", sourcesArr)
                .toString()
        )
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
            val domain = DurableDeepResearchProvider.domainOf(c.url)
            byDomain.getOrPut(domain) { mutableListOf() }.add(c)
        }
        val result = mutableListOf<Candidate>()
        var round = 0
        while (result.size < target && byDomain.values.any { it.isNotEmpty() }) {
            byDomain.entries.sortedByDescending { authority(it.key) }.forEach { (_, list) ->
                if (result.size < target && list.isNotEmpty()) result += list.removeAt(0)
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

    companion object {
        fun domainOf(url: String): String =
            runCatching { URI(url).host?.removePrefix("www.") ?: "unknown" }.getOrDefault("unknown")
    }
}

object QueryLanes {
    /**
     * Expand a research query into focused lanes.
     * Short / truncated queries (mobile autocomplete, partial paste) must stay
     * on code-host and documentation lanes; never inject tradeoffs/criticism.
     */
    fun expand(query: String, mode: ResearchMode = ResearchMode.BROAD, salt: String = ""): List<ResearchLane> {
        val base = query.trim()
        val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
        val isShort = words.size <= 4 || base.length < 36

        // Always start with high-signal code/docs lanes
        val core = mutableListOf(
            ResearchLane("primary documentation", "$base documentation OR guide OR reference OR official docs"),
            ResearchLane("implementation examples", "$base code example OR snippet OR implementation OR sample"),
            ResearchLane("community solutions", "$base site:stackoverflow.com OR site:github.com")
        )

        // Mode-specific prioritisation (prepended so they rank first)
        when (mode) {
            ResearchMode.EXPERIMENTAL -> {
                core.add(0, ResearchLane("experimental focus", "$base experimental OR prototype OR novel OR research code"))
                core.add(1, ResearchLane("github experimental", "$base experimental OR prototype site:github.com"))
            }
            ResearchMode.THEORETICAL -> {
                core.add(0, ResearchLane("theory focus", "$base theory OR formal OR model OR foundations"))
            }
            ResearchMode.EMPIRICAL -> {
                core.add(0, ResearchLane("empirical focus", "$base benchmark OR performance OR evaluation OR measure"))
            }
            ResearchMode.BROAD -> {}
        }

        if (!isShort) {
            // Long, well-formed queries get the full multi-perspective set
            core += ResearchLane("theoretical foundations", "$base theory OR formal model OR foundations OR hypothesis")
            core += ResearchLane("experimental research", "$base experimental OR prototype OR novel OR unconventional")
            core += ResearchLane("empirical evidence", "$base benchmark OR evaluation OR performance OR comparison OR measure")
            core += ResearchLane("standards and papers", "$base RFC OR specification OR paper OR standard")
            core += ResearchLane("failure modes", "$base pitfalls OR bugs OR failure OR limitations OR common mistakes")
            // Tradeoffs / criticism ONLY for long queries — root cause of chess / wellbeing noise
            core += ResearchLane("alternatives and criticism", "$base alternatives OR criticism OR tradeoffs OR vs OR compared")
        } else {
            // Short / truncated queries (mobile autocomplete): stay strictly on code hosts
            core += ResearchLane("code search", "$base programming OR library OR framework OR api")
            core += ResearchLane("github code", "$base site:github.com")
            if (mode == ResearchMode.EXPERIMENTAL) {
                core += ResearchLane("android kotlin experimental", "$base android kotlin experimental site:github.com")
            }
        }

        val rotated = if (salt.isBlank()) core else {
            val shift = (salt.hashCode().and(0x7fffffff)) % core.size
            core.drop(shift) + core.take(shift)
        }
        return rotated
    }
}

data class ResearchLane(val name: String, val query: String)

/**
 * Reject low-signal pages, news, slide decks, and anything that does not
 * share meaningful terms with the original research query.
 */
object SourceQuality {
    private val junkTitle = Pattern.compile(
        """\\b(talk|disambiguation|user talk|wikiProject|sandbox|article talk)\\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val junkExcerpt = Pattern.compile(
        """(please help improve|this article needs|not a guidebook|learn how and when to remove|for other uses, see)""",
        Pattern.CASE_INSENSITIVE
    )
    private val blockedDomains = listOf(
        "chess.com", "slideshare.net", "slideshare.com", "pinterest.com",
        "facebook.com", "twitter.com", "x.com", "instagram.com",
        "reddit.com/r/chess", "espn.com", "cnn.com", "bbc.com",
        "nytimes.com", "forbes.com", "medium.com/tag", "quora.com",
        "fullframeinitiative.org", "thisvsthat.io", "geeksforgeeks.org/system-design",
        "educative.io", "interview", "wellbeing", "self-improvement"
    )
    private val blockedTitleTokens = listOf(
        "tradeoffs in system design", "system design tradeoffs", "tradeoffs in professional",
        "criticism vs. critique", "u.s. takes", "rapid chess", "wellbeing",
        "making change is hard", "investor relations"
    )
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "with",
        "is", "are", "be", "by", "at", "from", "as", "it", "this", "that",
        "code", "experimental", "involving", "how", "what", "when", "where"
    )

    fun queryTerms(query: String): Set<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()

    fun hasQueryRelevance(terms: Set<String>, title: String, excerpt: String, url: String): Boolean {
        if (terms.isEmpty()) return true
        val hay = "$title $excerpt $url".lowercase()
        val hits = terms.count { hay.contains(it) }
        // Require at least one strong term overlap; for very short queries demand higher relative overlap
        return hits >= 1 && (terms.size <= 2 || hits.toDouble() / terms.size >= 0.25)
    }

    fun relevanceScore(terms: Set<String>, hit: ResearchHit): Int {
        if (terms.isEmpty()) return 0
        val hay = "${hit.title} ${hit.excerpt} ${hit.url}".lowercase()
        return terms.count { hay.contains(it) } * 4
    }

    fun isAcceptable(url: String, title: String, excerpt: String): Boolean {
        val u = url.lowercase()
        val t = title.lowercase()
        if (u.contains("wikipedia.org") && (t.contains("talk") || u.contains("talk:") || u.contains("disambiguation"))) return false
        if (junkTitle.matcher(title).find()) return false
        if (junkExcerpt.matcher(excerpt).find()) return false
        if (u.contains("/wiki/Talk:") || u.contains("/wiki/User:") || u.contains("/wiki/Wikipedia:")) return false
        if (blockedDomains.any { u.contains(it) || t.contains(it) }) return false
        if (blockedTitleTokens.any { t.contains(it) }) return false
        // Drop pure news / sports / soft-skill pages that sometimes leak through
        if (t.contains("chess") || t.contains("wellbeing") || t.contains("tradeoff") && t.contains("interview")) return false
        return true
    }

    fun rankBoost(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("developer.android.com") || u.contains("kotlinlang.org") -> 12
            u.contains("github.com") -> 10
            u.contains("stackoverflow.com") -> 9
            u.contains("android.com") || u.contains("developer.apple.com") -> 8
            u.contains("docs.") || u.contains("developer.") -> 7
            u.contains("medium.com") || u.contains("dev.to") -> 2
            u.contains("wikipedia.org") -> 0
            else -> 3
        }
    }
}

object ArticleExtractor {
    data class Extracted(val title: String, val text: String, val wordCount: Int, val codeBlocks: List<String>) {
        /** Compatibility alias used by unit tests. */
        val code: List<String> get() = codeBlocks
    }

    fun extract(html: String, fallbackTitle: String = "document"): Extracted = parse(html, fallbackTitle)

    fun fetch(
        url: String,
        connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
        timeoutMillis: Int = 15_000
    ): Extracted? {
        val connection = connectionFactory(url).apply {
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
            .filter { it.length in 8..4000 }
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
