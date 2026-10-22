package com.codingagent.agent

import java.time.Instant
import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntent
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationIssue
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Answer hello / list / status / read / inspect / self-repair / agent-meta without the model.
 */
class AgentDirectLanes(
    private val workspace: ProjectWorkspace,
    private val files: ProjectFileService,
    private val mutations: MutationCoordinator
) {
    fun respond(taskId: String, request: String, intake: TaskIntake, plan: AgentPlan): AgentTask? {
        val t = request.lowercase().trim()
        if (SelfRepair.isRequest(request)) {
            SelfRepair.handle(taskId, request, plan, workspace, files, mutations)?.let { return it }
        }
        if (AgentRequestKind.isAgentMeta(t)) {
            return AgentTask(
                taskId, request, "completed", plan, emptyList(),
                VerificationReport(false, emptyList()),
                listOf("${Instant.now()}: agent-meta answer (no project verify)"),
                AgentRequestKind.metaAnswer(t)
            )
        }
        if (AgentRequestKind.isGreeting(t)) {
            return greeting(taskId, request, plan, workspace.verify())
        }
        if (AgentRequestKind.isListing(t) && AgentRequestKind.isSourceFileList(t)) {
            return listing(taskId, request, plan, workspace.verify())
        }
        if (AgentRequestKind.isStatus(t)) {
            return status(taskId, request, intake, plan, workspace.verify())
        }
        AgentRequestKind.explicitReadPath(request)?.let { path ->
            return readFile(taskId, request, plan, workspace.verify(), path)
        }
        if (!AgentRequestKind.isWholeProjectReview(request)) {
            AgentRequestKind.inspectTarget(request)?.let { target ->
                return inspect(taskId, request, plan, target)
            }
        }
        return null
    }

    private fun greeting(taskId: String, request: String, plan: AgentPlan, report: VerificationReport): AgentTask {
        val summary = workspace.summary()
        val text = buildString {
            append("Hello. Coding Agent is ready.\n")
            append("I can see ${summary.files.size} files that look like source code. That is not every file in the project.\n")
            append("Languages: ")
            append(
                if (summary.languages.isEmpty()) "none yet"
                else summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key} (${it.value})" }
            )
            append(".\n")
            append(
                if (report.passed) "A simple text check passed. This does not prove the program runs.\n"
                else "A simple text check found ${report.issues.size} note(s). This does not by itself prove the program is broken.\n"
            )
            append("You can ask me to list files, read a file, explain something, look something up, or change the project. I ask before I save a change.")
        }
        return AgentTask(
            taskId, request, "completed", plan, emptyList(), report,
            listOf("${Instant.now()}: greeting / readiness"),
            text
        )
    }

    private fun listing(taskId: String, request: String, plan: AgentPlan, report: VerificationReport): AgentTask {
        val paths = files.listSourceFilePaths()
        val text = buildString {
            append("Indexed source files: ${paths.size}\n")
            append("These are source-code files only, not every file in the project.\n")
            if (paths.isEmpty()) append("None yet.\n") else paths.forEach { append(it).append('\n') }
            append("\n")
            append(if (report.passed) "A simple text check passed. This does not prove the program runs." else "A simple text check found ${report.issues.size} note(s).")
        }
        return AgentTask(
            taskId, request, "completed", plan, emptyList(), report,
            listOf("${Instant.now()}: direct indexed source listing (${paths.size} files)"),
            text
        )
    }

    private fun status(
        taskId: String,
        request: String,
        intake: TaskIntake,
        plan: AgentPlan,
        report: VerificationReport
    ): AgentTask {
        val summary = workspace.summary()
        val text = buildString {
            append("Here is where things stand.\n")
            append("- Files I can see: ${summary.files.size}\n")
            append("- Named pieces of code: ${summary.symbols}. Links between files: ${summary.imports}\n")
            append("- Languages: ")
            append(
                if (summary.languages.isEmpty()) "none yet"
                else summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key} (${it.value})" }
            )
            append("\n- Simple text check: ")
            append(if (report.passed) "passed. This does not prove the program runs." else "found ${report.issues.size} note(s).")
            if (report.issues.isNotEmpty()) {
                report.issues.forEach { issue ->
                    append("\n  - ${issue.path}:${issue.line} — ${issue.message}")
                }
            }
            append("\n- Changes waiting for your yes: ${mutations.pending().size}")
            append("\n- I understood this as: ${plainIntent(intake.intent)}")
        }
        return AgentTask(
            taskId, request, "completed", plan, emptyList(), report,
            listOf("${Instant.now()}: status report"),
            text
        )
    }

    private fun readFile(
        taskId: String,
        request: String,
        plan: AgentPlan,
        report: VerificationReport,
        readPath: String
    ): AgentTask {
        val resolved = LocalFileEvidence.resolve(readPath, files, workspace) ?: readPath
        val content = runCatching { LocalFileEvidence.readComplete(resolved, files) }.getOrElse {
            return AgentTask(
                taskId, request, "failed", plan, emptyList(),
                VerificationReport(false, listOf(VerificationIssue(resolved, 0, it.message ?: "read failed"))),
                listOf("${Instant.now()}: read failed for $resolved"),
                "I could not read $resolved. ${it.message ?: "The file may be missing."}"
            )
        }
        val text = buildString {
            append("File: $resolved\n───\n")
            append(content)
        }
        return AgentTask(
            taskId, request, "completed", plan, emptyList(), report,
            listOf("${Instant.now()}: direct read_file $resolved"),
            text
        )
    }

    private fun inspect(taskId: String, request: String, plan: AgentPlan, inspectTarget: String): AgentTask {
        val local = LocalFileEvidence.report(inspectTarget, files, workspace)
            ?: return AgentTask(
                taskId, request, "failed", plan, emptyList(),
                VerificationReport(false, listOf(VerificationIssue(inspectTarget, 0, "file not found in project index"))),
                listOf("${Instant.now()}: inspect target not found: $inspectTarget"),
                "I could not find $inspectTarget in the project. Ask me to list files, then use the exact name."
            )
        if (AgentRequestKind.isMarkerOnly(request)) {
            return AgentTask(
                taskId, request, "completed", plan, emptyList(),
                local.report,
                listOf("${Instant.now()}: local policy scan ${local.path}"),
                local.asUserText(includePolicy = true, includeStructure = false)
            )
        }
        val text = buildString {
            append(local.asUserText(includePolicy = true, includeStructure = true))
            append("\n\nThis is only what I can see in the file on this phone.\n")
            append("- Notes such as TODO or FIXME are reminders, not proof the program is broken.\n")
            append("- Structure notes are guesses, not a full check that the program runs.\n")
            append("- For a deeper look, connect a code helper in Setup and ask again.")
        }
        return AgentTask(
            taskId, request, "completed", plan, emptyList(),
            local.report,
            listOf("${Instant.now()}: local evidence package ${local.path}"),
            text
        )
    }

    private fun plainIntent(intent: TaskIntent): String = when (intent) {
        TaskIntent.INSPECT -> "look at the project"
        TaskIntent.CHANGE -> "change existing work"
        TaskIntent.CREATE -> "make something new"
        TaskIntent.REFACTOR -> "rearrange existing work without changing what it is supposed to do"
        TaskIntent.DEBUG -> "find and fix a problem"
        TaskIntent.TEST -> "check that something works"
        TaskIntent.EXPLAIN -> "explain something"
        TaskIntent.UNKNOWN -> "not sure yet"
    }
}
