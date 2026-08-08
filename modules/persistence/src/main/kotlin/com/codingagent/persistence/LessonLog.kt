package com.codingagent.persistence

import java.io.File
import java.time.Instant

class LessonLog(private val root: File) {
    private val file = root.resolve("lessons.tsv")

    init { root.mkdirs() }

    @Synchronized
    fun record(pattern: String, outcome: String, evidence: String) {
        val values = listOf(Instant.now().toEpochMilli(), pattern, outcome, evidence)
        file.appendText(values.joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') } + "\n")
    }

    fun recent(limit: Int = 20): List<String> = if (!file.isFile) emptyList() else file.readLines().asReversed().take(limit)
}
