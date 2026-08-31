package com.codingagent.agent

import org.json.JSONObject
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.research.DeepResearchProvider
import com.codingagent.research.ResearchBriefBuilder
import com.codingagent.research.ResearchMode
import com.codingagent.research.ResearchModeDetector
import com.codingagent.workspace.ChangeSet
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession

/**
 * ONE JOB: Execute one named agent tool and return its text result.
 */
class AgentToolDispatch(
    private val files: ProjectFileService,
    private val workspace: ProjectWorkspace,
    private val knowledge: AgentKnowledge,
    private val research: DeepResearchProvider,
    private val mutations: MutationCoordinator,
    private val terminal: TerminalSession,
    private val maxOutputCharacters: Int,
    private val onResearchProgress: (String) -> Unit,
    private val onApplied: (ChangeSet) -> Unit
) {
    // Wired in: was previously dead code. Every disk write that clears the full dual-owner
    // approval gate below also gets staged and promoted through SelfEvolution, so successful
    // changes are recorded for later sessions. This reuses the SAME approval already granted
    // for the write (ownerVerified + approvalCount>=2 + sandboxPassed, all enforced above by
    // MutationCoordinator via AgentConstitution) — it does not grant any new authority, and
    // AgentConstitution.check runs again inside promoteSource as a second, independent gate.
    private val evolution = SelfEvolution(workspace.projectRoot())

    fun execute(name: String, rawArguments: String): String {
        return try {
            val arguments = JSONObject(rawArguments)
            when (name) {
                "list_files" -> listFiles(arguments)
                "read_file" -> files.read(arguments.getString("path")).content.take(maxOutputCharacters)
                "search_project" -> workspace.search(arguments.getString("query"))
                    .joinToString("\n") { hit -> "${hit.path}:${hit.line}: ${hit.text}" }
                    .take(maxOutputCharacters)
                "search_knowledge" -> knowledge.search(arguments.getString("query"))
                    .joinToString("\n") { hit -> "${hit.document}/${hit.section}: ${hit.excerpt}" }
                    .take(maxOutputCharacters)
                "research_web" -> researchWeb(arguments)
                "replace_text" -> replaceText(arguments)
                "create_file" -> createFile(arguments)
                "run_command" -> {
                    val entry = terminal.execute(arguments.getString("command"))
                    "exit=${entry.exitCode} timeout=${entry.timedOut}\n${entry.stdout}\n${entry.stderr}"
                        .take(maxOutputCharacters)
                }
                "verify" -> {
                    val report = workspace.verify()
                    val issues = report.issues.joinToString("\n") { issue ->
                        "${issue.path}:${issue.line}: ${issue.message}"
                    }
                    "passed=${report.passed}\n$issues".take(maxOutputCharacters)
                }
                "approve_change" -> approveChange(arguments)
                "reject_change" -> {
                    val id = arguments.getString("id")
                    if (mutations.reject(id)) "REJECTED id=$id" else "ERROR: Change proposal does not exist"
                }
                else -> "ERROR: Unknown tool '$name'"
            }
        } catch (error: Exception) {
            "ERROR: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    // Best-effort: promotion failing must never affect the write that already succeeded above.
    private fun promoteAppliedChanges(result: MutationApprovalResult.Applied) {
        runCatching {
            val proposal = result.proposal
            val latestApproval = proposal.approvals.maxOfOrNull { it.approvedAt }
            result.changeSet.changes.forEach { change ->
                val file = workspace.projectRoot().resolve(change.path)
                if (!file.isFile) return@forEach
                val staged = evolution.stageSource(file, kind = change.operation.name)
                val action = AgentAction(
                    description = proposal.request,
                    category = AgentActionCategory.CODE_CHANGE,
                    ownerVerified = true,
                    approvalCount = proposal.approvalCount,
                    sandboxPassed = proposal.verification.passed,
                    clearPermission = true
                )
                evolution.promoteSource(staged, change.operation.name, proposal.verification, action, latestApproval)
            }
        }
    }

    private fun listFiles(arguments: JSONObject): String {
        val rawPath = arguments.optString("path").trim()
        val pathArg = when {
            rawPath.isEmpty() || rawPath == "." || rawPath == "./" || rawPath == "/" -> ""
            else -> rawPath
        }
        val listed = if (pathArg.isEmpty()) {
            files.listSourceFilePaths().ifEmpty { files.listSourceFileNames() }
        } else {
            files.list(pathArg)
        }
        val result = if (listed.isEmpty()) "(no files)" else listed.joinToString("\n")
        return result.take(maxOutputCharacters)
    }

    private fun researchWeb(arguments: JSONObject): String {
        val query = arguments.getString("query")
        val mode = runCatching {
            ResearchMode.valueOf(arguments.optString("mode", "BROAD").uppercase())
        }.getOrDefault(ResearchModeDetector.detect(query))
        val sources = arguments.optInt("sources", 8).coerceIn(1, 12)
        var progressLine = "not started"
        val sessionResult = runCatching {
            research.deepResearch(query, sources, mode) { progress ->
                progressLine =
                    "${progress.stage}: ${progress.completed}/${progress.total}; " +
                        "learned ${progress.successful}, failed ${progress.failed}"
                onResearchProgress(progressLine)
            }
        }
        val session = sessionResult.getOrNull()
        return when {
            sessionResult.isFailure -> {
                val err = sessionResult.exceptionOrNull()
                "ERROR: research_web failed: ${err?.message ?: err?.javaClass?.simpleName ?: "unknown"}"
            }
            session == null || session.sources.isEmpty() ->
                "ERROR: research_web found no usable sources for \"$query\". " +
                    "Try a tighter technical query (library + API + error text). Do not invent docs."
            else -> {
                val brief = ResearchBriefBuilder.build(session)
                val header =
                    "Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, " +
                        "${brief.wordCount} words, ${brief.codeExampleCount} code examples.\n" +
                        "Progress: $progressLine\n"
                (header + brief.evidence).take(maxOutputCharacters)
            }
        }
    }

    private fun replaceText(arguments: JSONObject): String {
        val path = arguments.getString("path")
        return when (val result = mutations.propose(
            request = "replace_text $path",
            operations = listOf(
                TaskOperation(
                    OperationKind.REPLACE,
                    path,
                    arguments.getString("oldText"),
                    arguments.getString("newText")
                )
            ),
            reason = arguments.optString("reason", "Autonomous model proposal")
        )) {
            is MutationProposeResult.Proposed ->
                "PROPOSAL_READY id=${result.proposal.id} path=$path " +
                    "changes=${result.proposal.changeSet.changes.size} approval_required=2 " +
                    "Confirm twice in Review or chat to APPLY this change to disk."
            is MutationProposeResult.Rejected ->
                "ERROR: replace_text proposal rejected — ${result.reason}"
        }
    }

    private fun createFile(arguments: JSONObject): String {
        val path = arguments.getString("path")
        return when (val result = mutations.propose(
            request = "create_file $path",
            operations = listOf(
                TaskOperation(
                    OperationKind.CREATE_FILE,
                    path,
                    text = arguments.getString("content")
                )
            ),
            reason = arguments.optString("reason", "Autonomous model proposal")
        )) {
            is MutationProposeResult.Proposed ->
                "PROPOSAL_READY id=${result.proposal.id} path=$path " +
                    "changes=${result.proposal.changeSet.changes.size} approval_required=2 " +
                    "Confirm twice in Review or chat to APPLY this file to disk."
            is MutationProposeResult.Rejected ->
                "ERROR: create_file proposal rejected — ${result.reason}"
        }
    }

    private fun approveChange(arguments: JSONObject): String {
        val result = mutations.approve(
            id = arguments.getString("id"),
            ownerVerified = arguments.optBoolean("ownerVerified", false),
            ownerLabel = arguments.optString("ownerLabel", "owner")
        )
        return when (result) {
            is MutationApprovalResult.AwaitingSecond ->
                "AWAITING_SECOND_APPROVAL id=${result.proposal.id} approvals=${result.proposal.approvalCount}"
            is MutationApprovalResult.Applied -> {
                onApplied(result.changeSet)
                promoteAppliedChanges(result)
                "APPLIED id=${result.proposal.id} changes=${result.changeSet.changes.size}"
            }
            is MutationApprovalResult.Rejected -> "ERROR: ${result.reason}"
        }
    }
}
