package com.codingagent.research

import com.codingagent.domain.ResearchHit

typealias ResearchHit = com.codingagent.domain.ResearchHit

enum class ResearchMode { BROAD, EXPERIMENTAL, THEORETICAL, EMPIRICAL }

data class ResearchResult(val query: String, val hits: List<ResearchHit>, val error: String? = null)
data class ResearchSource(
    val title: String,
    val url: String,
    val domain: String,
    val lane: String,
    val status: Int,
    val wordCount: Int,
    val content: String,
    val codeExamples: List<String> = emptyList(),
    val error: String? = null
)
data class ResearchSession(
    val id: String,
    val query: String,
    val createdAt: Long,
    val requestedSources: Int,
    val sources: List<ResearchSource>,
    val learnedChunks: Int,
    val errors: List<String> = emptyList(),
    val mode: String = "broad"
)
data class DeepResearchProgress(
    val stage: String,
    val completed: Int,
    val total: Int,
    val successful: Int,
    val failed: Int
)

fun interface ResearchCancellation {
    fun isCancelled(): Boolean
}

object ResearchLimits {
    const val MAX_QUERY_CHARS = 1_000
    const val MAX_TARGET_SOURCES = 30
    const val MAX_RESEARCH_QUERIES = 24
    const val MAX_SEARCH_RESULTS_PER_QUERY = 16
}

interface WebResearchProvider {
    fun search(query: String, limit: Int = 6): ResearchResult
}

interface DeepResearchProvider {
    fun deepResearch(
        query: String,
        targetSources: Int = 50,
        mode: ResearchMode = ResearchMode.BROAD,
        onProgress: (DeepResearchProgress) -> Unit = {},
        cancellation: ResearchCancellation = ResearchCancellation { false }
    ): ResearchSession
}
