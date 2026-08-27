package com.codingagent.knowledge

import android.content.Context
import java.io.File
import com.codingagent.agent.AgentKnowledge
import com.codingagent.workspace.KnowledgeHit

/**
 * ONE JOB: Persist and query offline knowledge documents the owner imports.
 * No bundled copyrighted books. Owner chooses every document.
 */
class KnowledgeBase(context: Context) : KnowledgeProvider, AgentKnowledge {
    private val root = File(context.filesDir, "coding-agent/knowledge").apply { mkdirs() }
    private val index = KnowledgeIndex(root)

    fun ingest(request: IngestRequest): IngestResult = index.indexRequest(request)

    fun ingestFile(file: File, documentName: String? = null): IngestResult =
        index.indexRequest(DocumentIngester.extractFromFile(file, documentName))

    fun ingestText(documentName: String, source: String, text: String): IngestResult =
        index.indexText(documentName, source, text)

    /** Optional: import a plain asset the owner ships intentionally (never a default book). */
    fun importAsset(context: Context, assetPath: String, document: String): Int {
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        return index.indexText(document, "asset:$assetPath", text).chunkCount
    }

    fun listDocuments(): List<IndexedDocument> = index.listDocuments()

    fun removeDocument(name: String): Boolean = index.removeDocument(name)

    fun stats(): Pair<Int, Int> = index.documentCount() to index.chunkCount()

    override fun search(query: String, limit: Int): List<KnowledgeHit> = index.search(query, limit)
}
