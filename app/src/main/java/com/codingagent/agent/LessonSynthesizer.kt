package com.codingagent.agent

/**
 * ONE JOB: Convert raw experience.tsv lines and evolution history into prompt-ready lessons.
 * Enables self-modification: the agent reads its own past outcomes and avoids
 * repeating failures while reinforcing successful patterns.
 *
 * experience.tsv columns (tab-separated):
 *   0: timestamp, 1: passed (true/false), 2: task, 3: operation, 4: result, 5: evidence
 */
object LessonSynthesizer {
    private const val LOOKBACK = 30
    private const val MAX_LESSONS = 4
    private const val PREVIEW = 100

    fun synthesize(
        lines: List<String>,
        evolution: List<EvolutionVersion> = emptyList()
    ): String {
        val body = buildString {
            val recent = lines.takeLast(LOOKBACK)
            val failures = recent.filter { parsePassed(it) == false }
            val successes = recent.filter { parsePassed(it) == true }
            if (failures.isNotEmpty()) {
                appendLine("Patterns that failed recently — avoid repeating:")
                failures.takeLast(MAX_LESSONS).forEach { line ->
                    val task = col(line, 2).take(PREVIEW).ifBlank { return@forEach }
                    val result = col(line, 4).take(PREVIEW).ifBlank { return@forEach }
                    appendLine("  - $task → $result")
                }
            }
            if (successes.isNotEmpty()) {
                appendLine("Patterns that succeeded recently — repeat these:")
                successes.takeLast(MAX_LESSONS).forEach { line ->
                    val task = col(line, 2).take(PREVIEW).ifBlank { return@forEach }
                    val op = col(line, 3).ifBlank { "?" }
                    appendLine("  - [$op] $task")
                }
            }
            val promoted = evolution.takeLast(MAX_LESSONS)
            if (promoted.isNotEmpty()) {
                appendLine("Recently promoted self-changes (dual-approved snapshots):")
                promoted.forEach { version ->
                    val name = version.sourcePath.substringAfterLast('/').ifBlank { version.id.take(8) }
                    val verdict = if (version.evaluationPassed) "verified" else "unverified"
                    appendLine("  - [${version.kind}] $name $verdict")
                }
            }
        }.trim()
        return body
    }

    private fun parsePassed(line: String): Boolean? =
        when (col(line, 1).lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun col(line: String, index: Int): String =
        line.split('\t').getOrElse(index) { "" }.trim()
}
