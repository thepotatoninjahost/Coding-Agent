package com.codingagent.agent

import java.io.File

/**
 * ONE JOB: Persist per-task outcomes to experience.tsv for later sessions.
 */
/**
 * The owner's own sentence. The model may also see the open-job note, but that note is not the title.
 */
object OwnerWords {
    fun title(raw: String): String {
        val marker = "Current request:"
        val at = raw.lastIndexOf(marker, ignoreCase = true)
        val focus = if (at >= 0) raw.substring(at + marker.length).trim() else raw.trim()
        if (!focus.startsWith("OPEN JOB")) return focus.ifBlank { raw.trim() }
        val goal = Regex("(?m)^- goal:\\s*(.+)$").find(focus)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return if (goal.isNotBlank() && !goal.startsWith("OPEN JOB")) goal else focus
    }
}

class ExperienceRecorder(private val root: File) {
    private val file = com.codingagent.workspace.ProjectMetadataBoundary.normalizePath(root.resolve(".coding-agent/experience.tsv"))

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
        return file.readLines().filter { it.isNotBlank() }
    }
}
