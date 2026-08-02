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
 * BROAD mode delegates to [PersonalResearchProvider] for topical personal research.
 * Specialized modes keep multi-lane coding research.
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
        // BROAD = topical personal-style research (Research tab + general agent asks).
        if (mode == ResearchMode.BROAD) {
            return PersonalResearchProvider(researchRoot, pageTimeoutMillis, connectionFactory, searchProvider)
                .deepResearch(query, targetSources, mode, onProgress)
        }
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

        if (candidates.size > effectiveTarget * 2) {
            val trimmed = candidates.take(effectiveTarget * 3)
            candidates.clear()
            candidates += trimmed
        }

        if (candidates.size < effectiveTarget) {
            val focused = QueryLanes.focusedFallbacks(normalized)
            val fallback = focused.flatMap { fq ->
                searchProvider.search(fq, 12).hits
                    .filter { hit -> SourceQuality.isAcceptable(hit.url, hit.title, hit.excerpt) }
                    .filter { hit -> SourceQuality.hasQueryRelevance(queryTerms, hit.title, hit.excerpt, hit.url) }
                    .filter { hit -> hit.url.substringBefore('#').lowercase() !in alreadyLearned }
                    .map { Candidate(it, "fallback") }
            }.dedupe()
            fallback.forEach { c ->
                if (candidates.none { it.url == c.url }) candidates += c
            }
            candidates.sortByDescending { SourceQuality.relevanceScore(queryTerms, it.hit) + SourceQuality.rankBoost(it.url) }
        }

        val diverse = selectDiverse(candidates, (effectiveTarget * 2).coerceAtMost(maxSourceFetches))
        onProgress(DeepResearchProgress("fetching", 0, diverse.size, 0, 0))

        val sources = mutableListOf<ResearchSource>()
        var failed = 0
        diverse.forEachIndexed { index, candidate ->
            if (sources.size >= effectiveTarget) {
                onProgress(DeepResearchProgress("fetching", index + 1, diverse.size, sources.size, failed))
                return@forEachIndexed
            }
            val fetched = runCatching { ArticleExtractor.fetch(candidate.url, connectionFactory, pageTimeoutMillis) }.getOrNull()
            if (fetched != null &&
                fetched.wordCount >= 40 &&
                SourceQuality.isAcceptable(candidate.url, fetched.title.ifBlank { candidate.title }, fetched.text.take(500)) &&
                SourceQuality.contentRelevant(queryTerms, fetched.title, fetched.text)
            ) {
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
            errors = buildList {
                if (failed > 0) add("$failed source fetch(es) failed or failed relevance")
                if (sources.isEmpty()) add("No sources passed relevance filters for: $normalized")
            },
            mode = mode.name.lowercase()
        )
        persist(session)
        onProgress(DeepResearchProgress("learned", sources.size, sources.size.coerceAtLeast(1), sources.size, failed))
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
        domain.contains("wikipedia.org") -> 0
        else -> 1
    }

    companion object {
        fun domainOf(url: String): String =
            runCatching { URI(url).host?.removePrefix("www.") ?: "unknown" }.getOrDefault("unknown")
    }
}

object QueryLanes {
    private val howTo = Regex(
        "\\b(how|create|creating|build|building|implement|custom|ui|interface|layout|compose|widget|view)\\b",
        RegexOption.IGNORE_CASE
    )

    fun expand(query: String, mode: ResearchMode = ResearchMode.BROAD, salt: String = ""): List<ResearchLane> {
        val base = query.trim()
        val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
        val isShort = words.size <= 4 || base.length < 36
        val isHowTo = howTo.containsMatchIn(base)

        val core = mutableListOf(
            ResearchLane("primary documentation", "$base documentation OR guide OR tutorial OR official docs"),
            ResearchLane("implementation examples", "$base code example OR snippet OR implementation OR sample"),
            ResearchLane("community solutions", "$base site:stackoverflow.com OR site:github.com")
        )

        if (isHowTo) {
            core.add(0, ResearchLane("howto coding", "$base android OR ios OR jetpack compose OR swiftui OR react native"))
            core.add(1, ResearchLane("ui implementation", "$base custom view OR custom component OR UI toolkit"))
            val rotated = if (salt.isBlank()) core else {
                val shift = (salt.hashCode().and(0x7fffffff)) % core.size
                core.drop(shift) + core.take(shift)
            }
            return rotated
        }

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
            core += ResearchLane("theoretical foundations", "$base theory OR formal model OR foundations OR hypothesis")
            core += ResearchLane("experimental research", "$base experimental OR prototype OR novel OR unconventional")
            core += ResearchLane("empirical evidence", "$base benchmark OR evaluation OR performance OR comparison OR measure")
            core += ResearchLane("standards and papers", "$base RFC OR specification OR paper OR standard")
            core += ResearchLane("failure modes", "$base pitfalls OR bugs OR failure OR limitations OR common mistakes")
            core += ResearchLane("alternatives and criticism", "$base alternatives OR criticism OR tradeoffs OR vs OR compared")
        } else {
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

    fun focusedFallbacks(query: String): List<String> = listOf(
        "$query site:github.com",
        "$query site:stackoverflow.com",
        "$query site:developer.android.com",
        "$query site:developer.apple.com",
        "$query jetpack compose OR swiftui OR react native UI",
        "$query custom view OR custom component code example"
    )
}

data class ResearchLane(val name: String, val query: String)

object SourceQuality {
    private val junkTitle = Pattern.compile(
        """\\b(talk|disambiguation|user talk|wikiProject|sandbox|article talk|wikipedia search)\\b""",
        Pattern.CASE_INSENSITIVE
    )
    private val junkExcerpt = Pattern.compile(
        """(please help improve|this article needs|not a guidebook|learn how and when to remove|for other uses, see|\\{\\{cite|cite web\\||Article Talk|\\[\\[Category:)""",
        Pattern.CASE_INSENSITIVE
    )
    private val wikiNoise = Pattern.compile(
        """(\\{\\{cite|\\|access-date=|\\|archive-url=|\\|url-status=|Article Talk|\\[\\[Category:|Help improve this article)""",
        Pattern.CASE_INSENSITIVE
    )
    private val blockedDomains = listOf(
        "chess.com", "slideshare.net", "slideshare.com", "pinterest.com",
        "facebook.com", "twitter.com", "x.com", "instagram.com",
        "reddit.com/r/chess", "espn.com", "cnn.com", "bbc.com",
        "nytimes.com", "forbes.com", "medium.com/tag", "quora.com",
        "fullframeinitiative.org", "thisvsthat.io", "geeksforgeeks.org/system-design",
        "educative.io", "wellbeing"
    )
    private val blockedTitleTokens = listOf(
        "tradeoffs in system design", "system design tradeoffs", "tradeoffs in professional",
        "criticism vs. critique", "u.s. takes", "rapid chess", "wellbeing",
        "making change is hard", "investor relations", "mastodon (social", "k-meleon",
        "windows installer", "microsoft copilot"
    )
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "with",
        "is", "are", "be", "by", "at", "from", "as", "it", "this", "that",
        "code", "experimental", "involving", "how", "what", "when", "where",
        "can", "do", "does", "using", "use", "used", "into", "about", "your",
        "my", "me", "we", "our", "their", "them", "than", "then", "also"
    )

    fun queryTerms(query: String): Set<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()

    fun hasQueryRelevance(terms: Set<String>, title: String, excerpt: String, url: String): Boolean {
        if (terms.isEmpty()) return false
        val hay = "$title $excerpt $url".lowercase()
        val hits = terms.count { hay.contains(it) }
        if (hits == 0) return false
        return when {
            terms.size <= 2 -> hits >= 1
            terms.size <= 4 -> hits >= 2 || hits.toDouble() / terms.size >= 0.5
            else -> hits >= 3 || hits.toDouble() / terms.size >= 0.4
        }
    }

    fun contentRelevant(terms: Set<String>, title: String, content: String): Boolean {
        if (terms.isEmpty()) return false
        if (wikiNoise.matcher(content.take(800)).find()) return false
        val hay = "$title ${content.take(4_000)}".lowercase()
        val hits = terms.count { hay.contains(it) }
        return when {
            terms.size <= 2 -> hits >= 1
            terms.size <= 4 -> hits >= 2
            else -> hits >= 3
        }
    }

    fun relevanceScore(terms: Set<String>, hit: ResearchHit): Int {
        if (terms.isEmpty()) return 0
        val hay = "${hit.title} ${hit.excerpt} ${hit.url}".lowercase()
        var score = terms.count { hay.contains(it) } * 5
        if (hay.contains("user interface") || hay.contains("custom view") || hay.contains("custom ui")) score += 8
        if (hay.contains("jetpack compose") || hay.contains("swiftui") || hay.contains("react native")) score += 6
        return score
    }

    fun isAcceptable(url: String, title: String, excerpt: String): Boolean {
        val u = url.lowercase()
        val t = title.lowercase()
        val e = excerpt.lowercase()
        if (u.contains("wikipedia.org")) {
            if (t.contains("talk") || u.contains("talk:") || u.contains("disambiguation")) return false
            if (u.contains("/wiki/talk:") || u.contains("/wiki/user:") || u.contains("/wiki/wikipedia:")) return false
            if (wikiNoise.matcher(excerpt).find()) return false
            if (t.contains("article talk") || e.contains("article talk")) return false
        }
        if (junkTitle.matcher(title).find()) return false
        if (junkExcerpt.matcher(excerpt).find()) return false
        if (wikiNoise.matcher(excerpt).find()) return false
        if (blockedDomains.any { u.contains(it) || t.contains(it) }) return false
        if (blockedTitleTokens.any { t.contains(it) }) return false
        if (t.contains("chess") || t.contains("wellbeing")) return false
        if (t.contains("tradeoff") && (t.contains("interview") || t.contains("system design"))) return false
        return true
    }

    fun rankBoost(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("developer.android.com") || u.contains("kotlinlang.org") -> 14
            u.contains("developer.apple.com") -> 12
            u.contains("github.com") -> 11
            u.contains("stackoverflow.com") -> 10
            u.contains("android.com") -> 9
            u.contains("docs.") || u.contains("developer.") -> 8
            u.contains("medium.com") || u.contains("dev.to") -> 2
            u.contains("wikipedia.org") -> -4
            else -> 3
        }
    }
}

object ArticleExtractor {
    data class Extracted(val title: String, val text: String, val wordCount: Int, val codeBlocks: List<String>) {
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
            .replace(Regex("<sup[^>]*class=\"reference\"[\\s\\S]*?</sup>", RegexOption.IGNORE_CASE), " ")
        val codeBlocks = Regex("<pre[^>]*>([\\s\\S]*?)</pre>", RegexOption.IGNORE_CASE).findAll(cleaned)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.length in 8..4000 }
            .take(8)
            .toList()
        var text = cleaned.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        text = text
            .replace(Regex("\\{\\{[^}]{0,400}\\}\\}"), " ")
            .replace(Regex("\\[\\[(?:File|Image|Category|Help|Wikipedia|Talk|User):[^\\]]+\\]\\]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\|\\s*(access-date|archive-url|archive-date|url-status|website|publisher|date|title|url|last|first|ref)\\s*="), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return Extracted(title, text.take(20_000), words.size, codeBlocks)
    }
}

data class ResearchBrief(
    val query: String,
    val sourceCount: Int,
    val laneCount: Int,
    val wordCount: Int,
    val codeExampleCount: Int,
    val sourceLines: List<String>,
    val evidence: String
)

object ResearchBriefBuilder {
    fun build(session: ResearchSession, maxCharacters: Int = 48_000): ResearchBrief {
        val usable = session.sources.filter { it.content.isNotBlank() }
        val queryTerms = SourceQuality.queryTerms(session.query)
        val ranked = usable.sortedWith(
            compareByDescending<ResearchSource> { SourceQuality.contentRelevant(queryTerms, it.title, it.content) }
                .thenByDescending { authority(it.domain) }
                .thenByDescending { it.wordCount }
        )
        val evidence = buildString {
            append("Research corpus for: ").append(session.query).append("\n")
            append("Full sources read: ").append(usable.size)
            append("; distinct lanes: ").append(usable.map { it.lane }.distinct().size)
            append("; words: ").append(usable.sumOf { it.wordCount }).append("\n\n")
            ranked.forEachIndexed { index, source ->
                append("SOURCE ").append(index + 1)
                append(" [").append(source.lane).append("] ")
                append(source.title).append(" <").append(source.url).append(">\n")
                append(source.content.take(4_000)).append("\n")
                source.codeExamples.take(2).forEach { append("CODE: ").append(it.take(2_000)).append("\n") }
                append("\n")
            }
        }.take(maxCharacters)
        return ResearchBrief(
            query = session.query,
            sourceCount = usable.size,
            laneCount = usable.map { it.lane }.distinct().size,
            wordCount = usable.sumOf { it.wordCount },
            codeExampleCount = usable.sumOf { it.codeExamples.size },
            sourceLines = ranked.map { "${it.title} — ${it.url}" },
            evidence = evidence
        )
    }

    private fun authority(domain: String): Int = when {
        domain.contains("developer.android.com") || domain.contains("kotlinlang.org") || domain.contains("sqlite.org") -> 5
        domain.contains("rfc-editor.org") || domain.contains("github.com") -> 4
        domain.contains("stackoverflow.com") -> 3
        domain.contains("wikipedia.org") -> 0
        else -> 1
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
