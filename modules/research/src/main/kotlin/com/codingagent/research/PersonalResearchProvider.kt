package com.codingagent.research

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Personal Research tab provider (BROAD mode).
 *
 * Uses QueryLanes for how-to / UI queries so results bias toward Jetpack Compose,
 * SwiftUI, custom views, and code hosts. Rejects generic-term-only hits and common
 * junk (metaverse intros, career READMEs, FTC robots, no-code builders, Wikipedia noise).
 */
class PersonalResearchProvider(
    private val researchRoot: File,
    private val pageTimeoutMillis: Int = 15_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val searchProvider: WebResearchProvider = CompositeWebResearchProvider()
) : DeepResearchProvider {

    private val sessionsDir = File(researchRoot, "sessions").apply { mkdirs() }

    override fun deepResearch(
        query: String,
        targetSources: Int,
        mode: ResearchMode,
        onProgress: (DeepResearchProgress) -> Unit,
        cancellation: ResearchCancellation
    ): ResearchSession {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "Research query is required" }
        require(normalized.length <= ResearchLimits.MAX_QUERY_CHARS) { "Research query exceeds ${ResearchLimits.MAX_QUERY_CHARS} characters" }
        if (cancellation.isCancelled()) return cancelledSession(normalized, targetSources)
        val target = targetSources.coerceIn(1, ResearchLimits.MAX_TARGET_SOURCES)
        val terms = SourceQuality.queryTerms(normalized)
        require(terms.isNotEmpty()) { "Query has no searchable terms" }

        onProgress(DeepResearchProgress("searching", 0, target, 0, 0))

        // Prefer QueryLanes expansion for how-to / UI queries so search is biased
        // toward coding UI docs instead of loose "coding + apps" web noise.
        val laneQueries = QueryLanes.expand(normalized, ResearchMode.BROAD)
            .map { it.query }
            .filter { it.isNotBlank() }
        val searchQueries = linkedSetOf<String>().apply {
            add(normalized)
            addAll(laneQueries)
            add("$normalized site:github.com")
            add("$normalized site:stackoverflow.com")
            add("$normalized site:developer.android.com OR site:developer.apple.com")
            add(terms.joinToString(" ") + " site:github.com OR site:stackoverflow.com")
            if (SourceQuality.isUiQuery(terms)) {
                add("$normalized jetpack compose OR swiftui OR custom view OR custom component")
                add("$normalized android UI OR iOS UI documentation")
            }
        }.filter { it.isNotBlank() }.take(ResearchLimits.MAX_RESEARCH_QUERIES)

        val hits = linkedMapOf<String, ResearchHit>()
        for (q in searchQueries) {
            if (cancellation.isCancelled()) break
            val result = searchProvider.search(q, ResearchLimits.MAX_SEARCH_RESULTS_PER_QUERY)
            result.hits.forEach { hit ->
                if (!SourceQuality.isAcceptable(hit.url, hit.title, hit.excerpt)) return@forEach
                if (!SourceQuality.hasQueryRelevance(terms, hit.title, hit.excerpt, hit.url)) return@forEach
                val key = hit.url.substringBefore('#').lowercase()
                if (key.isNotBlank()) hits.putIfAbsent(key, hit)
            }
        }

        val ranked = hits.values
            .sortedByDescending { SourceQuality.relevanceScore(terms, it) + SourceQuality.rankBoost(it.url) }
            .take(target * 3)

        onProgress(DeepResearchProgress("fetching", 0, ranked.size.coerceAtLeast(1), 0, 0))

        val sources = mutableListOf<ResearchSource>()
        var failed = 0
        ranked.forEachIndexed { index, hit ->
            if (cancellation.isCancelled()) return@forEachIndexed
            if (sources.size >= target) return@forEachIndexed
            val fetched = runCatching {
                require(hit.url.startsWith("https://")) { "Research source must use HTTPS" }
                ArticleExtractor.fetch(hit.url, connectionFactory, pageTimeoutMillis)
            }.getOrNull()
            val title = fetched?.title?.takeIf { it.isNotBlank() } ?: hit.title
            val body = fetched?.text.orEmpty()
            val ok = fetched != null &&
                fetched.wordCount >= 40 &&
                SourceQuality.isAcceptable(hit.url, title, body.take(500)) &&
                SourceQuality.contentRelevant(terms, title, body) &&
                !(hit.url.lowercase().contains("wikipedia.org") && !normalized.lowercase().contains("wiki"))
            if (ok) {
                sources += ResearchSource(
                    title = title,
                    url = hit.url,
                    domain = DurableDeepResearchProvider.domainOf(hit.url),
                    lane = "personal",
                    status = 200,
                    wordCount = fetched!!.wordCount,
                    content = body,
                    codeExamples = fetched.codeBlocks.take(8)
                )
            } else {
                failed++
            }
            onProgress(DeepResearchProgress("fetching", index + 1, ranked.size, sources.size, failed))
        }

        val session = ResearchSession(
            id = UUID.randomUUID().toString(),
            query = normalized,
            createdAt = System.currentTimeMillis(),
            requestedSources = target,
            sources = sources,
            learnedChunks = sources.size,
            errors = buildList {
                if (sources.isEmpty()) add("No on-topic sources found for: $normalized")
                if (failed > 0) add("$failed candidates rejected as off-topic or unreadable")
            },
            mode = "personal"
        )
        persist(session)
        onProgress(DeepResearchProgress("learned", sources.size, sources.size.coerceAtLeast(1), sources.size, failed))
        return session
    }

    private fun cancelledSession(query: String, targetSources: Int): ResearchSession = ResearchSession(
        id = UUID.randomUUID().toString(),
        query = query,
        createdAt = System.currentTimeMillis(),
        requestedSources = targetSources.coerceIn(1, ResearchLimits.MAX_TARGET_SOURCES),
        sources = emptyList(),
        learnedChunks = 0,
        errors = listOf("Research cancelled by owner"),
        mode = "cancelled"
    )

    private fun persist(session: ResearchSession) {
        val file = File(sessionsDir, "${session.id}.json")
        val temporary = File(sessionsDir, ".${session.id}.tmp")
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
        val payload = JSONObject()
                .put("id", session.id)
                .put("query", session.query)
                .put("createdAt", session.createdAt)
                .put("requestedSources", session.requestedSources)
                .put("learnedChunks", session.learnedChunks)
                .put("mode", session.mode)
                .put("errors", JSONArray(session.errors))
                .put("sources", sourcesArr)
                .toString()
        temporary.writeText(payload)
        require(temporary.renameTo(file)) { "Could not atomically persist research session ${session.id}" }
    }
}
