package com.codingagent.research
import com.codingagent.workspace.ResearchSession
import com.codingagent.workspace.ResearchSource

/**
 * ONE JOB: Session → ranked evidence brief for the agent loop.
 */
data class ResearchBrief(
    val query: String,
    val sourceCount: Int,
    val laneCount: Int,
    val wordCount: Int,
    val codeExampleCount: Int,
    val sourceLines: List<String>,
    val evidence: String,
    val truncated: Boolean = false
)

object ResearchBriefBuilder {
    fun build(session: ResearchSession, maxCharacters: Int = 48_000): ResearchBrief {
        val limit = maxCharacters.coerceAtLeast(1)
        val usable = session.sources.filter { it.content.isNotBlank() }
        val queryTerms = SourceQuality.queryTerms(session.query)
        val ranked = usable.sortedWith(
            compareByDescending<ResearchSource> { authority(it) }
                .thenByDescending { SourceQuality.rankBoost(it.url) }
                .thenByDescending { SourceQuality.contentRelevant(queryTerms, it.title, it.content) }
                .thenByDescending { it.wordCount }
        )
        val fullWordCount = usable.sumOf { it.wordCount }
        val fullEvidence = buildString {
            append("Research corpus for: ").append(session.query).append("\n")
            append("Full sources read: ").append(usable.size)
            append("; distinct lanes: ").append(usable.map { it.lane }.distinct().size)
            append("; words: ").append(fullWordCount).append("\n\n")
            ranked.firstOrNull()?.let { best ->
                append("Best source: ").append(best.title).append(" <").append(best.url).append(">\n")
                append(best.content.replace(Regex("\\s+"), " ").take(400)).append("\n\n")
            }
            append("Write the owner's answer in plain English from these sources. Do not paste a whole page.\n\n")
            ranked.forEachIndexed { index, source ->
                append("SOURCE ").append(index + 1)
                append(" [").append(source.lane).append("] ")
                append(source.title).append(" <").append(source.url).append(">\n")
                append(source.content.replace(Regex("\\s+"), " ").take(700)).append("\n")
                source.codeExamples.take(2).forEach { append("CODE: ").append(it.take(300)).append("\n") }
                append("\n")
            }
        }
        val truncated = fullEvidence.length > limit
        val evidence = if (!truncated) {
            fullEvidence
        } else {
            var end = limit
            if (end < fullEvidence.length && fullEvidence[end - 1].isHighSurrogate()) end--
            fullEvidence.substring(0, end) +
                "\n\n[Research brief truncated to $limit characters for this turn. " +
                "Retrieved corpus is still $fullWordCount words across ${usable.size} sources. " +
                "The saved research session keeps the full text.]\n"
        }
        return ResearchBrief(
            query = session.query,
            sourceCount = usable.size,
            laneCount = usable.map { it.lane }.distinct().size,
            wordCount = fullWordCount,
            codeExampleCount = usable.sumOf { it.codeExamples.size },
            sourceLines = ranked.map { "${it.title} — ${it.url}" },
            evidence = evidence,
            truncated = truncated
        )
    }

    private fun authority(source: ResearchSource): Int {
        val domain = source.domain.lowercase()
        val url = source.url.lowercase()
        return when {
            url.contains("github.com") && url.contains("/releases") -> 6
            domain.contains("developer.android.com") || domain.contains("kotlinlang.org") || domain.contains("sqlite.org") -> 5
            domain.contains("rfc-editor.org") || domain.contains("github.com") -> 4
            domain.contains("stackoverflow.com") -> 3
            domain.contains("developer.mozilla.org") -> 0
            domain.contains("wikipedia.org") -> 0
            else -> 1
        }
    }

    fun looksLikeRawPage(text: String): Boolean {
        val lower = text.lowercase()
        if (text.length > 900 && (lower.contains("<html") || lower.contains("source 1") || text.lines().size > 20)) return true
        if ("developer.mozilla.org" in lower || ("threat model" in lower && text.length > 400)) return true
        return false
    }

    fun plainAnswer(evidence: String): String {
        val best = Regex("Best source: (.+)").find(evidence)?.groupValues?.getOrNull(1)?.trim()
        val fact = evidence.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.length in 40..240 &&
                    !line.startsWith("SOURCE") &&
                    !line.startsWith("Research") &&
                    !line.startsWith("Best source") &&
                    !line.startsWith("Write the owner") &&
                    !line.startsWith("Full sources")
            }
        return if (best != null) {
            "From $best. ${fact ?: "That page is the best match I found."}"
        } else {
            "I could not turn the pages into a short answer."
        }
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
