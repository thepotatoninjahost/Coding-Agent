package com.codingagent.agent

import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntent
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
            append('\\n')
            if (paths.isEmpty()) {
                append("(no indexed source files)\\n")
            } else {
                paths.take(maxPaths).forEach { path ->
                    append(path)
                    append('\\n')
                }
                if (paths.size > maxPaths) {
                    append("… and ${paths.size - maxPaths} more (use list_files / search_project)\\n")
                }
            }
        }
    }

    fun build(
        request: String,
        intake: TaskIntake,
        evidence: String,
        maxEvidenceChars: Int,
        lessons: String = LessonContext.prompt()
    ): String = buildString {
        appendLine("You are the Coding-Agent on this device. You extend the model with tools and real evidence — never invent paths or file contents.")
        appendLine()
        appendLine("Request:")
        appendLine(request)
        appendLine()
        val targets = intake.contract.targetPaths.joinToString().ifBlank { "none yet" }
        appendLine("Intent: ${intake.intent}")
        appendLine("Target paths: $targets")
        if (intake.contract.constraints.isNotEmpty()) {
            appendLine("Owner constraints — binding requirements:")
            intake.contract.constraints.distinct().forEach { constraint ->
                appendLine("- $constraint")
            }
        }
        if (intake.contract.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            intake.contract.acceptanceCriteria.distinct().forEach { criterion ->
                appendLine("- $criterion")
            }
        }
        appendLine()
        appendLine("Operating rules for this turn:")
        appendLine("1. Gather real evidence with tools. Never invent file contents or paths.")
        appendLine("2. If the owner names a file, call read_file on it before analysis or changing it.")
        appendLine("3. Exactly one tool call this turn. Inspect the complete result before deciding what to do next.")
        appendLine("4. Code changes only stage a proposal. Dual owner approval is required.")
        appendLine("5. Verify meaningful milestones before depending on them. Use the strongest relevant tests, compile/build checks, and integration checks available; static marker scanning alone is not proof that code works.")
        appendLine("6. When a check fails, stop building on that result. Diagnose the cause, change the approach if needed, fix it, and rerun the relevant check. Record the failure and outcome in personal logs.")
        appendLine("7. Work at the scale the objective requires, including coordinated multi-file changes, new tools, architectural changes, experiments, and self-modification when needed and authorized.")
        appendLine("8. Continue gathering evidence while it is useful. Do not stop merely because a file was read or a search returned results. Stop gathering when evidence is sufficient to take the next meaningful step.")
        appendLine("9. Use research_web for current documentation, external APIs, unfamiliar behavior, and errors not resolved by project evidence. Prefer official sources where available; if documentation does not exist, test hypotheses and label uncertainty.")
        appendLine("10. Preserve the active goal and every owner constraint across follow-up turns. Do not silently drop requirements or substitute a different task.")
        appendLine("11. Maintain accessible personal logs of actions, tool results, decision rationale, research, experiments, changes, verification, failures, communications, learning, and self-modification. Log available rationale and evidence, not an invented transcript of hidden cognition.")
        appendLine("12. Be candid about disagreements and better alternatives, with evidence and trade-offs. The owner decides unless the request conflicts with one of the twelve constitution rules or is technically impossible.")
        appendLine("13. Lead with the conclusion. Do not expose hidden chain-of-thought. Every project claim must be supported by the evidence below.")
        if (AgentRequestKind.isWholeProjectReview(request)) {
            appendLine("14. This is a whole-project review. Gather enough evidence to make a useful assessment, then write concrete improvements and their evidence. Do not stop after a token sample.")
        }
        if (intake.intent in setOf(TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG)) {
            appendLine("15. This is change work. A review alone is not the work. After understanding the target and relevant dependencies, stage the actual change. Use run_command when a shell check beats guessing.")
        }
        if (lessons.isNotBlank()) {
            appendLine()
            appendLine("Lessons from recent runs (self-correction context):")
            appendLine(lessons)
        }
        appendLine()
        appendLine("Evidence so far:")
        append(evidence.take(maxEvidenceChars.coerceAtMost(6_000)))
    }

    fun listingSummary(listing: String, report: VerificationReport, namesOnly: Boolean = true): String {
        return buildString {
            if (namesOnly) {
                append("Indexed source files (extension whitelist — not a full disk listing):\\n")
            } else {
                append("Directory listing:\\n")
            }
            append(listing.trim().ifBlank { "(none)" })
            append("\\n\\nVerification: ")
            if (report.passed) {
                append("passed (static unfinished-work marker scan)")
            } else {
                append("FAILED; ")
                append(report.issues.size)
                append(" issue(s)")
                report.issues.take(20).forEach { issue ->
                    append("\\n- ")
                    append(issue.path)
                    append(":")
                    append(issue.line)
                    append(" — ")
                    append(issue.message)
                }
            }
        }
    }

    fun synthesizeFromEvidence(
        request: String,
        evidence: String,
        report: VerificationReport,
        maxChars: Int
    ): String {
        val draft = buildString {
            append("Review from gathered evidence (model did not write a final after tools were closed).\\n\\n")
            append("Request: ").append(request.trim()).append("\\n\\n")
            append(evidence.take(maxChars))
            append("\\n\\nVerification: ")
            if (report.passed) {
                append("passed (static unfinished-work marker scan)")
            } else {
                append("FAILED (").append(report.issues.size).append(" issue(s))")
                report.issues.take(20).forEach { issue ->
                    append("\\n- ").append(issue.path).append(":").append(issue.line).append(" — ").append(issue.message)
                }
            }
            append("\\n\\nIf this is thinner than you wanted, retry once. The next run starts with this evidence already in context.")
        }
        return LogicReasoning.inspect(draft, evidence).displayText
    }
}
