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
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession

/**
 * ONE JOB: Run exactly one named tool and return its result string.
 */
class AgentToolDispatch(
    private val workspace: ProjectWorkspace,
    private val files: ProjectFileService,
    private val knowledge: AgentKnowledge,
    private val research: DeepResearchProvider,
    private val mutations: MutationCoordinator,
    private val terminal: TerminalSession,
    private val maxOutputCharacters: Int,
    private val onChangeApplied: (ChangeSet) -> Unit = {},
    private val onResearchProgress: (String) -> Unit = {}
) {
    private var lastResearchProgress: String = "not started"

    fun execute(name: String, rawArguments: String): String {
        return try {
            val arguments = JSONObject(rawArguments)
            when (name) {
                "list_files" -> {
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
                    result.limitOutput()
                }
                "read_file" -> files.read(arguments.getString("path")).content.limitOutput()
                "search_project" -> {
                    workspace.search(arguments.getString("query"))
                        .joinToString("\n") { hit -> "${hit.path}:${hit.line}: ${hit.text}" }
                        .limitOutput()
                }
                "search_knowledge" -> {
                    knowledge.search(arguments.getString("query"))
                        .joinToString("\n") { hit -> "${hit.document}/${hit.section}: ${hit.excerpt}" }
                        .limitOutput()
                }
                "research_web" -> researchWeb(arguments)
                "replace_text" -> {
                    val path = arguments.getString("path")
                    val proposal = mutations.propose(
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
                    )
                    "PROPOSAL_READY id=${proposal.id} path=$path changes=${proposal.changeSet.changes.size} approval_required=2 " +
                        "Confirm twice in Review or chat to APPLY this change to disk."
                }
                "create_file" -> {
                    val path = arguments.getString("path")
                    val proposal = mutations.propose(
                        request = "create_file $path",
                        operations = listOf(
                            TaskOperation(
                                OperationKind.CREATE_FILE,
                                path,
                                text = arguments.getString("content")
                            )
                        ),
                        reason = arguments.optString("reason", "Autonomous model proposal")
                    )
                    "PROPOSAL_READY id=${proposal.id} path=$path changes=${proposal.changeSet.changes.size} approval_required=2 " +
                        "Confirm twice in Review or chat to APPLY this file to disk."
                }
                "run_command" -> {
                    val entry = terminal.execute(arguments.getString("command"))
                    ("exit=${entry.exitCode} timeout=${entry.timedOut}\n${entry.stdout}\n${entry.stderr}").limitOutput()
                }
                "verify" -> {
                    val report = workspace.verify()
                    val issues = report.issues.joinToString("\n") { issue ->
                        "${issue.path}:${issue.line}: ${issue.message}"
                    }
                    "passed=${report.passed}\n$issues".limitOutput()
                }
                "approve_change" -> approveChange(arguments)
                "reject_change" -> rejectChange(arguments)
                else -> "ERROR: Unknown tool '$name'"
            }
        } catch (error: Exception) {
            "ERROR: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun researchWeb(arguments: JSONObject): String {
        val query = arguments.getString("query")
        val mode = runCatching {
            ResearchMode.valueOf(arguments.optString("mode", "BROAD").uppercase())
        }.getOrDefault(ResearchModeDetector.detect(query))
        val sources = arguments.optInt("sources", 8).coerceIn(1, 12)
        val sessionResult = runCatching {
            research.deepResearch(query, sources, mode) { progress ->
                lastResearchProgress =
                    "${progress.stage}: ${progress.completed}/${progress.total}; " +
                        "learned ${progress.successful}, failed ${progress.failed}"
                onResearchProgress(lastResearchProgress)
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
                        "Progress: $lastResearchProgress\n"
                (header + brief.evidence).limitOutput()
            }
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
                onChangeApplied(result.changeSet)
                "APPLIED id=${result.proposal.id} changes=${result.changeSet.changes.size}"
            }
            is MutationApprovalResult.Rejected -> "ERROR: ${result.reason}"
        }
    }

    private fun rejectChange(arguments: JSONObject): String {
        val id = arguments.getString("id")
        return if (mutations.reject(id)) "REJECTED id=$id" else "ERROR: Change proposal does not exist"
    }

    private fun String.limitOutput(): String = take(maxOutputCharacters)
}
