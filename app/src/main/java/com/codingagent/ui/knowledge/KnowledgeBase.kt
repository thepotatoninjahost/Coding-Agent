package com.codingagent.ui.knowledge

import android.content.Context
import com.codingagent.core.AgentKnowledge
import com.codingagent.knowledge.DocumentIngester
import com.codingagent.knowledge.IndexedDocument
import com.codingagent.knowledge.IngestRequest
import com.codingagent.knowledge.IngestResult
import com.codingagent.knowledge.KnowledgeHit
import com.codingagent.knowledge.KnowledgeIndex
import com.codingagent.knowledge.KnowledgeProvider
import java.io.File

/**
 * App knowledge facade over [KnowledgeIndex].
 * Bundled example assets and user-imported references share one searchable index.
 */
class KnowledgeBase(context: Context) : KnowledgeProvider, AgentKnowledge {
    private val root = File(context.filesDir, "coding-agent/knowledge").apply { mkdirs() }
    private val index = KnowledgeIndex(root)
    private val flagFile = File(root, "bundled.flag")

    /** Ensure the example reference is present once without wiping user imports. */
    fun ensureBundledExample(context: Context, assetPath: String = "knowledge/coding-for-dummies.txt"): Int {
        if (flagFile.isFile) return 0
        return runCatching {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val result = index.indexText("Coding For Dummies (example)", "asset:$assetPath", text)
            flagFile.writeText("1")
            result.chunkCount
        }.getOrDefault(0)
    }

    fun importAsset(context: Context, assetPath: String, document: String): Int {
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        return index.indexText(document, "asset:$assetPath", text).chunkCount
    }

    fun ingest(request: IngestRequest): IngestResult = index.indexRequest(request)

    fun ingestFile(file: File, documentName: String? = null): IngestResult =
        index.indexRequest(DocumentIngester.extractFromFile(file, documentName))

    fun ingestText(documentName: String, source: String, text: String): IngestResult =
        index.indexText(documentName, source, text)

    fun listDocuments(): List<IndexedDocument> = index.listDocuments()

    fun removeDocument(name: String): Boolean = index.removeDocument(name)

    fun stats(): Pair<Int, Int> = index.documentCount() to index.chunkCount()

    override fun search(query: String, limit: Int): List<KnowledgeHit> = index.search(query, limit)
}
