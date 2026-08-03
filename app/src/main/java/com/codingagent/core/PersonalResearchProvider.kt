package com.codingagent.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Personal Research tab provider.
 *
 * Prefers code hosts. Rejects generic-term-only hits and common junk
 * (metaverse intros, career READMEs, study-limitations blogs, Wikipedia noise).
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
        onProgress: (DeepResearchProgress) -> Unit
    ): ResearchSession {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "Research query is required" }
        val target = targetSources.coerceIn(1, 20)
        val terms = SourceQuality.queryTerms(normalized)
        require(terms.isNotEmpty()) { "Query has no searchable terms" }

        onProgress(DeepResearchProgress("searching", 0, target, 0, 0))

        val searchQueries = linkedSetOf(
            normalized,
            "$normalized site:github.com",
            "$normalized site:stackoverflow.com",
            "$normalized documentation OR guide OR tutorial OR paper OR specification",
            terms.joinToString(" ") + " site:github.com OR site:stackoverflow.com"
        ).filter { it.isNotBlank() }

        val hits = linkedMapOf<String, ResearchHit>()
        for (q in searchQueries) {
            val result = searchProvider.search(q, 14)
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
            if (sources.size >= target) return@forEachIndexed
            val fetched = runCatching {
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
}
