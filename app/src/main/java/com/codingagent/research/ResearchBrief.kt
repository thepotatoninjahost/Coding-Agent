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
