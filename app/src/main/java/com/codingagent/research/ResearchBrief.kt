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
            compareByDescending<ResearchSource> { SourceQuality.contentRelevant(queryTerms, it.title, it.content) }
                .thenByDescending { authority(it.domain) }
                .thenByDescending { it.wordCount }
        )
        val fullWordCount = usable.sumOf { it.wordCount }
        val fullEvidence = buildString {
            append("Research corpus for: ").append(session.query).append("\n")
            append("Full sources read: ").append(usable.size)
            append("; distinct lanes: ").append(usable.map { it.lane }.distinct().size)
            append("; words: ").append(fullWordCount).append("\n\n")
            ranked.forEachIndexed { index, source ->
                append("SOURCE ").append(index + 1)
                append(" [").append(source.lane).append("] ")
                append(source.title).append(" <").append(source.url).append(">\n")
                append(source.content).append("\n")
                source.codeExamples.forEach { append("CODE: ").append(it).append("\n") }
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
