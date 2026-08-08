package com.codingagent.persistence

import com.codingagent.domain.ChatRecord
import java.io.File

class EventLog(private val root: File) {
    private val file = root.resolve("events.jsonl")

    init { root.mkdirs() }

    fun append(record: ChatRecord) {
        file.appendText("${record.createdAt}\t${record.role}\t${record.content.replace("\n", " ")}\n")
    }

    fun recent(limit: Int = 100): List<ChatRecord> = if (!file.isFile) emptyList() else file.readLines().asReversed().take(limit).mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) null else ChatRecord(parts[1], parts[2], parts[0].toLongOrNull() ?: 0L)
    }
}
