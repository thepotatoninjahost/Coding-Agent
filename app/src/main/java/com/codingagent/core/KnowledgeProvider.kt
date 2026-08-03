package com.codingagent.core

interface KnowledgeProvider {
    fun search(query: String, limit: Int): List<KnowledgeHit>
}
