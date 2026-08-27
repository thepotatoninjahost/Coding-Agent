package com.codingagent.agent

import com.codingagent.intake.TaskIntake
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Build the model prompt and the compact repo map.
 */
object AgentPrompt {
    fun repoMap(files: ProjectFileService, workspace: ProjectWorkspace, maxPaths: Int = 100): String {
        val paths = files.listSourceFilePaths()
        val summary = workspace.summary()
        return buildString {
            append("Repo map — indexed sources: ${paths.size}")
            append(" (extension whitelist; not every file on disk).")
            if (summary.languages.isNotEmpty()) {
                append(" Languages: ")
                append(summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
            }
            append('\n')
            if (paths.isEmpty()) {
                append("(no indexed source files)\n")
            } else {
                paths.take(maxPaths).forEach { path ->
                    append(path)
                    append('\n')
                }
                if (paths.size > maxPaths) {
                    append("… and ${paths.size - maxPaths} more (use list_files / search_project)\n")
                }
            }
        }
    }

    fun build(request: String, intake: TaskIntake, evidence: String, maxEvidenceChars: Int): String = buildString {
        appendLine("You are the Coding-Agent on this device. You extend the model with tools and real evidence — never invent paths or file contents.")
        appendLine()
        appendLine("Request:")
        appendLine(request)
        appendLine()
        val targets = intake.contract.targetPaths.joinToString().ifBlank { "none yet" }
        appendLine("Intent: ${intake.intent}")
        appendLine("Target paths: $targets")
        appendLine()
        appendLine("Operating rules for this turn:")
        appendLine("1. Gather real evidence with tools. Never invent file contents or paths.")
        appendLine("2. If the user names a file, call read_file on it before analysis or final answer.")
        appendLine("3. Exactly one tool call this turn. Observe the full result before the next step.")
        appendLine("4. Code changes only stage a proposal. Dual owner approval is required.")
        appendLine("5. Call verify after changes or when hunting bugs. Never report a fake pass.")
        appendLine("6. Use research_web when you lack current docs, APIs, errors, or practices not in the project.")
        appendLine("7. Persist until the goal is met. Only stop early for a specific missing user input.")
        appendLine("8. After real file reads or project search hits, WRITE THE ANSWER. Do not keep listing.")
        appendLine("9. Prefer research_web over guessing external APIs. Prefer project files over inventing local paths.")
        if (AgentRequestKind.isWholeProjectReview(request)) {
            appendLine("10. This is a whole-project review. After real evidence, write concrete improvements.")
        }
        appendLine()
        appendLine("Evidence so far:")
        append(evidence.take(maxEvidenceChars.coerceAtMost(6_000)))
    }
}
