package com.codingagent.agent

import java.io.File

/**
 * ONE JOB: Append-only task-outcome logging (experience.tsv) for later self-correction context
 * (LessonSynthesizer reads this). Extracted out of SelfEvolution.kt — versioning staged/promoted
 * source snapshots is a different job from logging every task's raw outcome.
 */
class ExperienceRecorder(private val root: File) {
    private val file = root.resolve(".coding-agent/experience.tsv")

    @Synchronized
    fun record(task: String, operation: String, result: String, evidence: String, passed: Boolean) {
        file.parentFile?.mkdirs()
        file.appendText(listOf(System.currentTimeMillis(), passed, task, operation, result, evidence).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') } + "\n")
    }

    fun all(): List<String> = if (file.isFile) file.readLines() else emptyList()
}
