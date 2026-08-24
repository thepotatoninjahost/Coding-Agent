package com.codingagent.knowledge
import com.codingagent.workspace.KnowledgeHit

/**
 * ONE JOB: Search interface over local offline knowledge.
 */
interface KnowledgeProvider {
    fun search(query: String, limit: Int): List<KnowledgeHit>
}
