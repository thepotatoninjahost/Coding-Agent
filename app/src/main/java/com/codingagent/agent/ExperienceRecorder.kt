package com.codingagent.agent

import java.io.File

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
        val recent = ArrayDeque<String>(MAX_LINES)
        file.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach
                if (recent.size == MAX_LINES) recent.removeFirst()
                recent.addLast(line)
            }
        }
        return recent.toList()
    }

    companion object {
        private const val MAX_LINES = 200
    }
}
