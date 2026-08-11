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

/**
 * ONE JOB: Multi-lane deep research with durable sessions and progress callbacks.
 */
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
                                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                            }
                        )
                    }
                    ResearchSession(
                        id = json.optString("id"),
                        query = json.optString("query"),
                        createdAt = json.optLong("createdAt"),
                        requestedSources = json.optInt("requestedSources"),
                        sources = sources,
                        learnedChunks = json.optInt("learnedChunks", sources.size),
                        errors = (json.optJSONArray("errors") ?: JSONArray()).let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        },
                        mode = json.optString("mode", "broad")
                    )
                }.getOrNull()
            } ?: emptyList()
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
