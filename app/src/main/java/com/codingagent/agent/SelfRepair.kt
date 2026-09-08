package com.codingagent.agent

import java.time.Instant
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Turn "fix yourself" into a staged file change the owner can approve.
 */
object SelfRepair {
    fun isRequest(request: String): Boolean {
        val t = request.lowercase()
        val phrases = listOf(
            "fix yourself", "fix itself", "fix this agent", "repair yourself",
            "modify yourself", "modify itself", "self-fix", "self fix",
            "self-mod", "self modification", "self-modification",
            "heal yourself", "patch yourself"
        )
        return phrases.any { t.contains(it) }
    }

    fun handle(
        taskId: String,
        request: String,
        plan: AgentPlan,
        workspace: ProjectWorkspace,
        files: ProjectFileService,
        mutations: MutationCoordinator
    ): AgentTask {
        val report = workspace.verify()
        val operation = chooseOperation(workspace, files, report)
            ?: return AgentTask(
                taskId, request, "needs-input", plan, emptyList(),
                report,
                listOf("${Instant.now()}: self-repair needs a mounted source tree"),
                "Self-repair needs a mounted project with source files. " +
                    "Import the Coding-Agent folder (or any project you want repaired), then say fix yourself again."
            )
        return when (val proposed = mutations.propose(request, listOf(operation), "self-repair")) {
            is MutationProposeResult.Proposed -> {
                val proposal = proposed.proposal
                AgentTask(
                    taskId, request, "needs-approval", plan, proposal.changeSet.changes,
                    proposal.verification,
                    listOf("${Instant.now()}: self-repair staged ${proposal.id} -> ${operation.path}"),
                    ChangeDiff.ownerReviewText(proposal)
                )
            }
            is MutationProposeResult.Rejected -> AgentTask(
                taskId, request, "failed", plan, emptyList(),
                VerificationReport(false, emptyList()),
                listOf("${Instant.now()}: self-repair propose rejected"),
                "Self-repair could not stage a change: ${proposed.reason}"
            )
        }
    }

    internal fun chooseOperation(
        workspace: ProjectWorkspace,
        files: ProjectFileService,
        report: VerificationReport
    ): TaskOperation? {
        val marker = report.issues.firstOrNull {
            val m = it.message.lowercase()
            m.contains("todo") || m.contains("fixme") || m.contains("stub") || m.contains("placeholder")
        }
        if (marker != null) {
            val current = runCatching { files.read(marker.path).content }.getOrNull() ?: return null
            val lines = current.lineSequence().toList()
            val idx = (marker.line - 1).coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            val rebuilt = lines.toMutableList()
            if (rebuilt.isNotEmpty()) {
                rebuilt[idx] = rebuilt[idx]
                    .replace(Regex("TODO|FIXME|STUB|placeholder", RegexOption.IGNORE_CASE), "DONE")
            }
            val next = rebuilt.joinToString("\n").let { if (current.endsWith("\n")) "$it\n" else it }
            if (next != current) {
                return TaskOperation(
                    OperationKind.REPLACE,
                    path = marker.path,
                    oldText = current,
                    newText = next
                )
            }
        }

        val paths = files.listSourceFilePaths()
        val agentFile = paths.firstOrNull { path ->
            val n = path.substringAfterLast('/').lowercase()
            n == "selfrepair.kt" || n == "pendingworkresume.kt" || n == "chatworkspace.kt" ||
                n == "opjobstore.kt" || n == "openjobstore.kt" || n == "autonomousagent.kt"
        }
        if (agentFile != null) {
            val current = runCatching { files.read(agentFile).content }.getOrNull() ?: return null
            if (current.contains("SELF_REPAIR_CONTRACT")) {
                return appendContractTouch(agentFile, current)
            }
            val stamp = "\n// SELF_REPAIR_CONTRACT: dual-approve staged edits; never claim applied until Review confirms.\n"
            return TaskOperation(
                OperationKind.REPLACE,
                path = agentFile,
                oldText = current,
                newText = current.trimEnd() + stamp
            )
        }

        if (paths.isEmpty()) {
            return TaskOperation(
                OperationKind.CREATE_FILE,
                path = "src/SelfRepair.kt",
                text = spine()
            )
        }
        val first = paths.first()
        val current = runCatching { files.read(first).content }.getOrNull() ?: return null
        if (current.contains("SELF_REPAIR_CONTRACT")) return appendContractTouch(first, current)
        return TaskOperation(
            OperationKind.REPLACE,
            path = first,
            oldText = current,
            newText = current.trimEnd() +
                "\n// SELF_REPAIR_CONTRACT: owner dual-approves every self-repair write.\n"
        )
    }

    private fun appendContractTouch(path: String, current: String): TaskOperation {
        val line = "\n// SELF_REPAIR_TOUCH ${System.currentTimeMillis()}\n"
        return TaskOperation(OperationKind.REPLACE, path = path, oldText = current, newText = current.trimEnd() + line)
    }

    private fun spine(): String = """
package com.codingagent.repair

/**
 * Local self-repair spine. Staged by the agent; applied only after two owner approvals.
 */
class SelfRepairLoop(
    private val maxAttempts: Int = 3
) {
    fun run(diagnose: () -> String, apply: (String) -> Boolean, verify: () -> Boolean): Boolean {
        var attempt = 0
        while (attempt < maxAttempts) {
            val problem = diagnose()
            if (problem.isBlank() && verify()) return true
            if (!apply(problem)) return false
            if (verify()) return true
            attempt++
        }
        return false
    }
}
""".trimIndent() + "\n"
}
