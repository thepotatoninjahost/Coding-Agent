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

class DurableDeepResearchProvider(
    private val root: File,
    private val timeoutMillis: Int = 10_000,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val searchProvider: WebResearchProvider = DuckDuckGoResearchProvider(timeoutMillis, connectionFactory),
    private val maxSourceFetches: Int = 180
) : DeepResearchProvider {
    override fun deepResearch(query: String, targetSources: Int, mode: ResearchMode, onProgress: (DeepResearchProgress) -> Unit): ResearchSession {
        val normalized = query.trim()
        require(normalized.isNotBlank()) { "A research query is required" }
        require(targetSources in 1..50) { "Research source count must be between 1 and 50" }
        val lanes = QueryLanes.expand(normalized, mode)
        val candidates = lanes.flatMap { lane ->
            val first = searchProvider.search(lane.query, 20).hits
            first.map { hit -> Candidate(hit.title, hit.url, hit.excerpt, lane.name) }
        }.dedupe().toMutableList()
        if (candidates.size > targetSources) {
            val selected = mutableListOf<Candidate>()
            lanes.forEach { lane -> candidates.firstOrNull { it.lane == lane.name && selected.none { chosen -> chosen.url == it.url } }?.let(selected::add) }
            candidates.filter { candidate -> selected.none { it.url == candidate.url } }.forEach { candidate -> if (selected.size < targetSources) selected += candidate }
            candidates.clear()
            candidates += selected
        }
        if (candidates.size < targetSources) {
            val fallbackQueries = lanes.flatMap { lane ->
                listOf(
                    "${lane.query} tutorial guide reference",
                    "${lane.query} documentation implementation example",
                    "${lane.query} case study survey paper github"
                )
            }
            val fallback = fallbackQueries.flatMap { query ->
                searchProvider.search(query, 20).hits.map { hit -> Candidate(hit.title, hit.url, hit.excerpt, lanes.firstOrNull { lane -> query.startsWith(lane.query) }?.name ?: "discovery") }
            }.dedupe()
            fallback.forEach { candidate -> if (candidates.size < targetSources && candidates.none { it.url == candidate.url }) candidates += candidate }
        }
        if (candidates.size < targetSources) {
            val learned = recent(100).flatMap { it.sources }.map { source ->
                Candidate(source.title, source.url, source.content.take(500), source.lane)
            }.filter { candidate -> candidates.none { it.url == candidate.url } }.dedupe()
            learned.forEach { candidate -> if (candidates.size < targetSources) candidates += candidate }
        }
        val diverse = selectDiverse(candidates, targetSources).take(maxSourceFetches)
        candidates.clear()
        candidates += diverse
        val candidateCount = candidates.size
        if (candidateCount == 0) throw IllegalStateException("No distinct research candidates were found. Check the network or use a more specific query.")
        onProgress(DeepResearchProgress("fetching", 0, candidateCount, 0, 0))
        val executor = Executors.newFixedThreadPool(4)
        val futures = candidates.map { candidate -> executor.submit(Callable { fetch(candidate) }) }
        val sources = mutableListOf<ResearchSource>()
        var completed = 0
        var failed = 0
        futures.forEach { future ->
            val source = runCatching { future.get() }.getOrElse { errorSource("fetch failed", it.message ?: "fetch failed") }
            completed += 1
            if (source.error == null && source.content.isNotBlank()) sources += source else failed += 1
            onProgress(DeepResearchProgress("fetching", completed, candidateCount, sources.size, failed))
        }
        executor.shutdownNow()
        if (sources.isEmpty()) throw IllegalStateException("No full sources could be read. The search returned candidates, but every article fetch failed.")
        val minimumUsableSources = targetSources.coerceAtMost(4)
        if (sources.size < minimumUsableSources) {
            throw IllegalStateException("Only ${sources.size} distinct full sources were learned; at least ${minimumUsableSources} were required. ${failed} candidates failed during fetch.")
        }
        val errors = listOf("fetch failures: $failed")
        val session = ResearchSession(UUID.randomUUID().toString(), normalized, System.currentTimeMillis(), targetSources, sources, sources.sumOf { source -> chunk(source.content).size }, errors, mode.name.lowercase())
        persist(session)
        onProgress(DeepResearchProgress("learned", candidateCount, candidateCount, sources.size, failed))
        return session
    }

    fun recent(limit: Int = 10): List<ResearchSession> = root.resolve("sessions.jsonl").takeIf(File::exists)?.useLines { lines ->
        lines.mapNotNull { line -> runCatching { decode(line) }.getOrNull() }.toList().takeLast(limit).reversed()
    } ?: emptyList()

    fun searchLearned(query: String, limit: Int = 12): List<ResearchHit> {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+" )).filter { it.length > 2 }
        return recent(100).flatMap { session -> session.sources }.map { source ->
            val text = "${source.title} ${source.content}".lowercase()
            source to terms.count { text.contains(it) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map {
            ResearchHit(it.first.title, it.first.url, excerpt(it.first.content, terms))
        }
    }

    fun searchLearnedKnowledge(query: String, limit: Int = 12): List<KnowledgeHit> {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+" )).filter { it.length > 2 }
        if (terms.isEmpty()) return emptyList()
        return recent(100).flatMap { it.sources }.map { source ->
            val text = "${source.title} ${source.lane} ${source.content}".lowercase()
            val score = terms.count { term -> text.contains(term) }
            KnowledgeHit("${source.domain}/${source.lane}", source.title, score, excerpt(source.content, terms))
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(limit)
    }

    private fun fetch(candidate: Candidate): ResearchSource {
        val connection = connectionFactory(candidate.url)
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "CodingAgent/0.3 research")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain")
            val status = connection.responseCode
            if (status !in 200..299) return errorSource(candidate.url, "HTTP $status", candidate)
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val extracted = ArticleExtractor.extract(html)
            ResearchSource(candidate.title, candidate.url, domain(candidate.url), candidate.lane, status, extracted.text.split(Regex("\\s+")).count { it.isNotBlank() }, extracted.text, extracted.code)
        } catch (error: Exception) {
            errorSource(candidate.url, error.message ?: error.javaClass.simpleName, candidate)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorSource(title: String, error: String, candidate: Candidate? = null) = ResearchSource(candidate?.title ?: title, candidate?.url ?: "", candidate?.url?.let(::domain).orEmpty(), candidate?.lane.orEmpty(), 0, 0, candidate?.excerpt.orEmpty(), error = error)

    private fun persist(session: ResearchSession) {
        root.mkdirs()
        val encoded = buildString {
            append(escape(session.id)).append('\t')
            append(escape(session.query)).append('\t')
            append(session.createdAt).append('\t')
            append(session.requestedSources).append('\t')
            append(session.learnedChunks).append('\t')
            append(escape(session.mode)).append('\t')
            append(session.errors.joinToString("\u001e", transform = ::escape)).append('\t')
            append(session.sources.joinToString("\u001f") { source ->
                listOf(source.title, source.url, source.domain, source.lane, source.status.toString(), source.wordCount.toString(), source.content, source.codeExamples.joinToString("\u001e"), source.error.orEmpty()).joinToString("\u001e", transform = ::escape)
            })
        }
        root.resolve("sessions.jsonl").appendText(encoded + "\n")
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\u001e", "\\u001e").replace("\u001f", "\\u001f")

    private fun unescape(value: String): String = value.replace("\\u001f", "\u001f").replace("\\u001e", "\u001e").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    private fun decode(line: String): ResearchSession {
        val fields = line.split('\t').map(::unescape)
        if (fields.size < 7) error("Invalid research session")
        val hasMode = fields.size >= 8
        val errorsIndex = if (hasMode) 6 else 5
        val sourcesIndex = if (hasMode) 7 else 6
        val sources = if (fields[sourcesIndex].isBlank()) emptyList() else fields[sourcesIndex].split("\u001f").map { encoded ->
            val item = encoded.split("\u001e").map(::unescape)
            ResearchSource(item[0], item[1], item[2], item[3], item[4].toInt(), item[5].toInt(), item[6], item[7].takeIf(String::isNotBlank)?.split("\u001e") ?: emptyList(), item[8].ifBlank { null })
        }
        return ResearchSession(fields[0], fields[1], fields[2].toLong(), fields[3].toInt(), sources, fields[4].toInt(), fields[errorsIndex].takeIf(String::isNotBlank)?.split("\u001e") ?: emptyList(), if (hasMode) fields[5] else "broad")
    }

    private fun chunk(text: String): List<String> = text.split(Regex("(?<=[.!?])\\s+|\\n+")).map(String::trim).filter { it.length >= 80 }.chunked(8).map { it.joinToString(" ") }
    private fun excerpt(text: String, terms: List<String>): String { val lower = text.lowercase(); val at = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0; return text.substring(at.coerceAtLeast(0), (at + 500).coerceAtMost(text.length)) }
    private fun domain(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    private data class Candidate(val title: String, val url: String, val excerpt: String, val lane: String)
    private fun selectDiverse(candidates: List<Candidate>, target: Int): List<Candidate> {
        val buckets = candidates.groupBy { it.lane }.values.map { it.toMutableList() }
        val selected = mutableListOf<Candidate>()
        var index = 0
        while (selected.size < target && buckets.any { it.isNotEmpty() }) {
            val bucket = buckets[index % buckets.size]
            if (bucket.isNotEmpty()) selected += bucket.removeAt(0)
            index += 1
        }
        return selected
    }

    private fun List<Candidate>.dedupe(): List<Candidate> { val seen = LinkedHashMap<String, Candidate>(); forEach { candidate -> if (candidate.url.isNotBlank()) seen.putIfAbsent(candidate.url.substringBefore('#'), candidate) }; return seen.values.toList() }
}

data class ResearchLane(val name: String, val query: String)

object QueryLanes {
    fun expand(query: String, mode: ResearchMode = ResearchMode.BROAD): List<ResearchLane> {
        val common = listOf(
            ResearchLane("primary documentation", "$query official documentation API reference"),
            ResearchLane("implementation examples", "$query GitHub implementation example code"),
            ResearchLane("community solutions", "$query Stack Overflow production solution pitfalls"),
            ResearchLane("alternatives and criticism", "$query alternative approaches critique limitations counterexample"),
            ResearchLane("failure modes", "$query security performance failure modes troubleshooting production")
        )
        val focused = when (mode) {
            ResearchMode.THEORETICAL -> listOf(ResearchLane("theoretical foundations", "$query theory formal model fundamentals academic textbook"), ResearchLane("proofs and assumptions", "$query theorem proof assumptions invariants formal verification"), ResearchLane("competing hypotheses", "$query hypothesis debate counterargument falsification open problem"), ResearchLane("standards and papers", "$query RFC specification research paper survey"))
            ResearchMode.EXPERIMENTAL -> listOf(ResearchLane("prior art", "$query experimental prototype novel approach prior art research"), ResearchLane("empirical evidence", "$query benchmark evaluation dataset ablation performance comparison"), ResearchLane("failed attempts", "$query negative results failed experiment limitations reproducibility"), ResearchLane("standards and papers", "$query RFC specification research paper survey"))
            ResearchMode.EMPIRICAL -> listOf(ResearchLane("benchmarks", "$query benchmark evaluation dataset ablation performance comparison"), ResearchLane("measurement methods", "$query measurement methodology metrics experimental design validity"), ResearchLane("negative results", "$query negative results limitations reproducibility bias"), ResearchLane("standards and papers", "$query RFC specification research paper survey"))
            ResearchMode.BROAD -> listOf(ResearchLane("theoretical foundations", "$query theory formal model fundamentals academic textbook"), ResearchLane("experimental research", "$query experimental prototype novel approach research results"), ResearchLane("empirical evidence", "$query benchmark evaluation dataset ablation performance comparison"))
        }
        return common + focused
    }
}

data class ExtractedArticle(val text: String, val code: List<String>)

object ArticleExtractor {
    private val block = Pattern.compile("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val codeBlock = Pattern.compile("<(pre|code)[^>]*>(.*?)</\\1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    fun extract(html: String): ExtractedArticle {
        val code = codeBlock.matcher(html).let { matcher -> buildList { while (matcher.find()) { val value = clean(matcher.group(2).orEmpty()); if (value.isNotBlank()) add(value.take(20_000)) } } }
        val text = clean(block.matcher(html).replaceAll(" ").replace(Regex("<[^>]+>"), " ")).take(120_000)
        return ExtractedArticle(text, code)
    }
    private fun clean(value: String): String = value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace(Regex("\\s+"), " ").trim()
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
        val ranked = usable.sortedWith(compareByDescending<ResearchSource> { authority(it.domain) }.thenByDescending { it.wordCount })
        val evidence = buildString {
            append("Research corpus for: ").append(session.query).append("\\n")
            append("Full sources read: ").append(usable.size).append("; distinct lanes: ").append(usable.map { it.lane }.distinct().size).append("; words: ").append(usable.sumOf { it.wordCount }).append("\\n\\n")
            ranked.forEachIndexed { index, source ->
                append("SOURCE ").append(index + 1).append(" [").append(source.lane).append("] ").append(source.title).append(" <").append(source.url).append(">\\n")
                append(source.content.take(4_000)).append("\\n")
                source.codeExamples.take(2).forEach { append("CODE: ").append(it.take(2_000)).append("\\n") }
                append("\\n")
            }
        }.take(maxCharacters)
        return ResearchBrief(session.query, usable.size, usable.map { it.lane }.distinct().size, usable.sumOf { it.wordCount }, usable.sumOf { it.codeExamples.size }, ranked.map { "${it.title} — ${it.url}" }, evidence)
    }

    private fun authority(domain: String): Int = when {
        domain.contains("developer.android.com") || domain.contains("kotlinlang.org") || domain.contains("sqlite.org") -> 5
        domain.contains("rfc-editor.org") || domain.contains("github.com") -> 4
        domain.contains("stackoverflow.com") -> 3
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

