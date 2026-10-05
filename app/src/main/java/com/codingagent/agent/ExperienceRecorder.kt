package com.codingagent.agent

import java.io.File
import java.io.RandomAccessFile

/**
 * ONE JOB: Persist per-task outcomes to experience.tsv for later sessions.
 */
class ExperienceRecorder(private val root: File) {
    private val file = root.resolve(".coding-agent/experience.tsv")

    init {
        LessonContext.bindExperience(::all)
    }

    @Synchronized
    fun record(task: String, operation: String, result: String, evidence: String, passed: Boolean) {
        file.parentFile?.mkdirs()
        file.appendText(
            listOf(System.currentTimeMillis(), passed, task, operation, result, evidence)
                .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') } + "\n"
        )
    }

    fun all(): List<String> {
        if (!file.isFile) return emptyList()
        val limit = MAX_LINES
        val result = ArrayDeque<String>(limit)
        RandomAccessFile(file, "r").use { raf ->
            var position = raf.length() - 1
            val bytes = java.io.ByteArrayOutputStream()
            while (position >= 0 && result.size < limit) {
                raf.seek(position--)
                val value = raf.read()
                if (value == '\n'.code) {
                    val line = bytes.toByteArray().reversedArray().toString(Charsets.UTF_8).trim()
                    if (line.isNotBlank()) {
                        result.addFirst(line)
                        if (result.size > limit) result.removeFirst()
                    }
                    bytes.reset()
                } else {
                    bytes.write(value)
                }
            }
            if (bytes.size() > 0 && result.size < limit) {
                val line = bytes.toByteArray().reversedArray().toString(Charsets.UTF_8).trim()
                if (line.isNotBlank()) result.addFirst(line)
            }
        }
        return result.asReversed()
    }

    companion object {
        private const val MAX_LINES = 200
    }
}
