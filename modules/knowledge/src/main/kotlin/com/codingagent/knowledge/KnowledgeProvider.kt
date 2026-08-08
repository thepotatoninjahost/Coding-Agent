package com.codingagent.knowledge

data class KnowledgeHit(
    val document: String,
    val section: String,
    val score: Int,
    val excerpt: String
)

interface KnowledgeProvider {
    fun search(query: String, limit: Int = 8): List<KnowledgeHit>
}

data class KnowledgeChunk(
    val document: String,
    val section: String,
    val text: String,
    val terms: Set<String>
)

