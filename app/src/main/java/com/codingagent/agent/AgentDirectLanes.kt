package com.codingagent.agent

import java.time.Instant
import com.codingagent.intake.TaskIntake
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationIssue
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Answer hello / list / status / read / inspect / agent-meta without the model.
 */
class AgentDirectLanes(
    private val workspace: ProjectWorkspace,
    private val files: ProjectFileService,
    private val mutations: MutationCoordinator
) {
    fun respond(taskId: String, request: String, intake: TaskIntake, plan: AgentPlan): AgentTask? {
        val t = request.lowercase().trim()
        if (AgentRequestKind.isAgentMeta(t)) {
            return AgentTask(
                taskId, request, "completed", plan, emptyList(),
                VerificationReport(true, emptyList()),
                listOf("${Instant.now()}: agent-meta answer (no project verify)"),
                AgentRequestKind.metaAnswer(t)
            )
        }
        val report = workspace.verify()
        if (AgentRequestKind.isGreeting(t)) return greeting(taskId, request, plan, report)
        if (AgentRequestKind.isListing(t) && AgentRequestKind.isSourceFileList(t)) {
            return listing(taskId, request, plan, report)
        }
        if (AgentRequestKind.isStatus(t)) return status(taskId, request, intake, plan, report)
        AgentRequestKind.explicitReadPath(request)?.let { path ->
            return readFile(taskId, request, plan, report, path)
        }
        AgentRequestKind.inspectTarget(request)?.let { target ->
            return inspect(taskId, request, plan, target)
        }
        return null
    }

    private fun greeting(taskId: String, request: String, plan: AgentPlan, report: VerificationReport): AgentTask {
        val summary = workspace.summary()
        val text = buildString {
            append("Hello. Coding Agent is ready.\n")
            append("Project files indexed: ${summary.files.size} (source extensions only — not every file on disk).\n")
            append("Languages: ")
            append(
                if (summary.languages.isEmpty()) "none detected"
                else summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
            )
            append(".\n")
            append("Verification: ${if (report.passed) "passed (static marker scan)" else "FAILED (${report.issues.size} issue(s))"}.\n")
            append("Ask me to list files, read a path, analyze something, research a topic, or propose a change.")
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
            append("(Extension whitelist only — not a full disk listing.)\n")
            if (paths.isEmpty()) append("(none)\n") else paths.forEach { append(it).append('\n') }
            append("\nVerification: ")
            append(if (report.passed) "passed (static unfinished-work marker scan)" else "FAILED (${report.issues.size} issue(s))")
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
            append("Status report\n")
            append("- Indexed files: ${summary.files.size}\n")
            append("- Symbols: ${summary.symbols}, imports: ${summary.imports}\n")
            append("- Languages: ")
            append(
                if (summary.languages.isEmpty()) "none"
                else summary.languages.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
            )
            append("\n- Static verification: ")
            append(if (report.passed) "passed (0 unfinished-work markers)" else "FAILED (${report.issues.size} issue(s))")
            if (report.issues.isNotEmpty()) {
                report.issues.take(15).forEach { issue ->
                    append("\n  - ${issue.path}:${issue.line} — ${issue.message}")
                }
            }
            append("\n- Pending change proposals: ${mutations.pending().size}")
            append("\n- Intent classified as: ${intake.intent.name}")
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
        val content = runCatching { files.read(resolved).content }.getOrElse {
            return AgentTask(
                taskId, request, "failed", plan, emptyList(),
                VerificationReport(false, listOf(VerificationIssue(resolved, 0, it.message ?: "read failed"))),
                listOf("${Instant.now()}: read failed for $resolved"),
                "Could not read `$resolved`: ${it.message ?: it.javaClass.simpleName}"
            )
        }
        val text = buildString {
            append("File: $resolved\n───\n")
            append(content.take(12_000))
            if (content.length > 12_000) append("\n… (truncated)")
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
                "Could not find `$inspectTarget` in the project. Try `list project source files`, then use the exact name."
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
            append("\n\nScope note: local evidence only.\n")
            append("- Policy markers (TODO/FIXME/stub) are rule violations, not compile errors.\n")
            append("- Structure notes are heuristics, not a compiler.\n")
            append("- For logic/API/compile diagnosis, retry when the model is not rate-limited, or switch provider in Model settings.")
        }
        return AgentTask(
            taskId, request, "completed", plan, emptyList(),
            local.report,
            listOf("${Instant.now()}: local evidence package ${local.path}"),
            text
        )
    }
}
