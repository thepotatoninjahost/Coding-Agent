package com.codingagent.core

import com.codingagent.knowledge.KnowledgeHit
import com.codingagent.persistence.TaskJournal
import com.codingagent.persistence.TaskJournalRecord
import java.io.File

interface AgentKnowledge {
    fun search(query: String, limit: Int): List<KnowledgeHit>
}

fun interface AgentProgressListener {
    fun onProgress(phase: String, detail: String)
}

class AgentJournal(root: File) {
    private val journal = TaskJournal(root.resolve(".coding-agent"))
    fun record(task: AgentTask) { journal.record(TaskJournalRecord(task.id, task.status, task.request, task.changes.size, task.verification.passed, task.summary, task.events)) }
    fun recent(limit: Int = 20): List<String> = journal.recent(limit)
}
