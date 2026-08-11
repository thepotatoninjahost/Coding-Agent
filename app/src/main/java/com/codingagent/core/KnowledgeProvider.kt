package com.codingagent.core

/**
 * ONE JOB: Search interface over local offline knowledge.
 */
interface KnowledgeProvider {
    fun search(query: String, limit: Int): List<KnowledgeHit>
}
