package com.codingagent.persistence

import java.io.File

data class TaskJournalRecord(
    val id: String,
    val status: String,
    val request: String,
    val changes: Int,
    val verificationPassed: Boolean,
    val summary: String,
    val events: List<String>
)

class TaskJournal(private val root: File) {
    private val file = root.resolve("tasks.tsv")

    init { root.mkdirs() }

    @Synchronized
    fun record(record: TaskJournalRecord) {
        val line = listOf(record.id, record.status, record.request, record.changes, record.verificationPassed, record.summary, record.events.joinToString(" | "))
            .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
        file.appendText(line + "\n")
    }

    fun recent(limit: Int = 20): List<String> = if (!file.isFile) emptyList() else file.readLines().asReversed().take(limit)
}
