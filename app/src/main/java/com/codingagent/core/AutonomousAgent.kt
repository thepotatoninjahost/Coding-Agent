package com.codingagent.core

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

sealed class AutonomousAgentEvent {
    data class Started(val taskId: String, val request: String) : AutonomousAgentEvent()
    data class Phase(val name: String, val detail: String) : AutonomousAgentEvent()
    data class ToolStarted(val name: String, val arguments: String) : AutonomousAgentEvent()
    data class ToolFinished(val name: String, val result: String, val success: Boolean) : AutonomousAgentEvent()
    data class ModelDelta(val text: String) : AutonomousAgentEvent()
    data class ModelMessage(val content: String) : AutonomousAgentEvent()
    data class Completed(val task: AgentTask) : AutonomousAgentEvent()
    data class Failed(val task: AgentTask?, val message: String) : AutonomousAgentEvent()
    data class ApprovalRequired(val task: AgentTask, val proposal: PendingChangeProposal) : AutonomousAgentEvent()
}

data class AutonomousAgentConfig(
    val maxTurns: Int = 24,
    val commandTimeoutSeconds: Long = 180,
    val maxOutputCharacters: Int = 8_000
)

class AutonomousAgent(
    private val root: java.io.File,
    private val runtime: CodingAgentRuntime,
    private val knowledge: AgentKnowledge,
    private val gateway: ModelGateway,
    private val config: AutonomousAgentConfig = AutonomousAgentConfig(),
    private val research: DeepResearchProvider = DurableDeepResearchProvider(root.resolve(".coding-agent/research")),
    private val mutations: MutationCoordinator = MutationCoordinator(ProjectWorkspace(root))
) : CodingAgentExecutor {
    private val workspace = ProjectWorkspace(root)
    private val files = ProjectFileService(workspace)
    private val terminal = TerminalSession(root, config.commandTimeoutSeconds)
    private val journal = AgentJournal(root)
    private val changeSets = mutableListOf<ChangeSet>()
    private var lastResearchProgress: String = "not started"

    override fun execute(request: String): AgentRuntimeResult {
        val events = run(request)
        return when (val terminalEvent = events.lastOrNull()) {
            is AutonomousAgentEvent.ApprovalRequired -> AgentRuntimeResult.NeedsApproval(terminalEvent.task, "Review proposal ${terminalEvent.proposal.id} and confirm twice before applying any code change.", terminalEvent.proposal.id)
            is AutonomousAgentEvent.Completed -> AgentRuntimeResult.Completed(terminalEvent.task)
            is AutonomousAgentEvent.Failed -> terminalEvent.task?.let { AgentRuntimeResult.Failed(it) } ?: error(terminalEvent.message)
            else -> error("Autonomous agent ended without a terminal result")
        }
    }

    fun pendingProposals(): List<PendingChangeProposal> = mutations.pending()

    fun approveProposal(id: String, ownerVerified: Boolean, ownerLabel: String): MutationApprovalResult = mutations.approve(id, ownerVerified, ownerLabel)

    fun rejectProposal(id: String): Boolean = mutations.reject(id)

    fun run(request: String, onEvent: (AutonomousAgentEvent) -> Unit = {}): List<AutonomousAgentEvent> {
        val normalized = request.trim()
        require(normalized.isNotEmpty()) { "A coding request is required" }
        val taskId = UUID.randomUUID().toString()
        val events = mutableListOf<AutonomousAgentEvent>(AutonomousAgentEvent.Started(taskId, normalized))
        fun emit(event: AutonomousAgentEvent) {
            events += event
            onEvent(event)
        }
        emit(AutonomousAgentEvent.Phase("INTAKE", "Inspecting the request and repository contract"))
        val intake = runtime.intake(normalized)
        val plan = AgentPlanner(workspace).plan(intake)
        emit(AutonomousAgentEvent.Phase("PLAN", plan.steps.joinToString(" → ") { it.phase }))
        if (!intake.executionReady && intake.intent !in setOf(TaskIntent.INSPECT, TaskIntent.EXPLAIN, TaskIntent.TEST)) {
            val task = AgentTask(taskId, normalized, "needs-input", plan, emptyList(), VerificationReport(false, emptyList()), emptyList(), intake.clarificationQuestion ?: "Clarify the requested operation")
            emit(AutonomousAgentEvent.Failed(task, task.summary))
            journal.record(task)
            return events
        }

        emit(AutonomousAgentEvent.Phase("RESEARCH", "Reading 50 distinct sources before model decisions"))
        val mode = ResearchModeDetector.detect(normalized)
        val session = runCatching {
            research.deepResearch(normalized, 50, mode) { progress ->
                emit(AutonomousAgentEvent.Phase("RESEARCH", "${progress.stage}: ${progress.completed}/${progress.total}; learned ${progress.successful}, failed ${progress.failed}"))
            }
        }.getOrElse { error ->
            val task = failedTask(taskId, normalized, plan, "Mandatory research failed: ${error.message.orEmpty()}", changeSets.flatMap { it.changes })
            emit(AutonomousAgentEvent.Failed(task, task.summary))
            journal.record(task)
            return events
        }
        val brief = ResearchBriefBuilder.build(session)
        emit(AutonomousAgentEvent.Phase("RESEARCH", "Learned ${brief.sourceCount} full sources across ${brief.laneCount} lanes (${brief.wordCount} words, ${brief.codeExampleCount} code examples)"))
        val transcript = mutableListOf<ModelMessage>()
        var lastEvidence = "Repository has ${workspace.summary().files.size} indexed files.\n\nResearch brief:\n${brief.evidence}"
        for (turn in 0 until config.maxTurns) {
            emit(AutonomousAgentEvent.Phase("MODEL", "Decision turn ${turn + 1}/${config.maxTurns}"))
            val response = gateway.stream(ModelRequest(AgentModelProtocol.SYSTEM, buildPrompt(normalized, intake, lastEvidence), AgentModelProtocol.tools(), transcript.toList(), researchRequired = true)) { delta -> emit(AutonomousAgentEvent.ModelDelta(delta)) }
            when (response) {
                is ModelResponse.Failure -> {
                    val task = failedTask(taskId, normalized, plan, response.message, changeSets.flatMap { it.changes })
                    emit(AutonomousAgentEvent.Failed(task, response.message))
                    journal.record(task)
                    return events
                }
                is ModelResponse.Text -> {
                    emit(AutonomousAgentEvent.ModelMessage(response.content))
                    val task = completedTask(taskId, normalized, plan, response.content, changeSets.flatMap { it.changes }, workspace.verify())
                    journal.record(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return events
                }
                is ModelResponse.ToolCall -> {
                    emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments))
                    val toolResult = executeTool(response.name, response.arguments)
                    lastEvidence = toolResult
                    transcript += ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, response.callId, response.name, response.arguments)
                    transcript += ModelMessage("tool", "${response.name}: $toolResult", response.callId)
                    val success = !toolResult.startsWith("ERROR:")
                    emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult, success))
                    val proposalId = toolResult.substringAfter("PROPOSAL_READY id=", "").substringBefore(' ').takeIf { it.isNotBlank() }
                    if (proposalId != null) {
                        val proposal = mutations.get(proposalId)
                        if (proposal == null) {
                            val task = failedTask(taskId, normalized, plan, "The mutation proposal disappeared before approval: $proposalId", changeSets.flatMap { it.changes })
                            emit(AutonomousAgentEvent.Failed(task, task.summary))
                            journal.record(task)
                        } else {
                            val task = approvalTask(taskId, normalized, plan, proposal)
                            emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
                            journal.record(task)
                        }
                        return events
                    }
                }
            }
        }
        val task = failedTask(taskId, normalized, plan, "The model exceeded the autonomous turn budget", changeSets.flatMap { it.changes })
        emit(AutonomousAgentEvent.Failed(task, task.summary))
        journal.record(task)
        return events
    }

    private fun buildPrompt(request: String, intake: TaskIntake, evidence: String): String = buildString {
        append("User request:\n").append(request)
        append("\n\nTyped intake:\n").append(intake.summary)
        append("\nIntent: ").append(intake.intent)
        append("\nTargets: ").append(intake.contract.targetPaths.joinToString().ifBlank { "none" })
        append("\nAcceptance: ").append(intake.contract.acceptanceCriteria.joinToString("; "))
        append("\n\nResearch requirements:\n")
        append("Treat research as accumulated experience. For ordinary tasks, gather diverse primary and secondary sources. For experimental or theoretical tasks, include theory, prior art, competing approaches, experiments, benchmarks, counterexamples, failed attempts, and open questions. Separate established evidence from hypotheses.\n")
        append("\n\nLatest verified evidence:\n").append(evidence.take(config.maxOutputCharacters))
    }

    private fun executeTool(name: String, rawArguments: String): String {
        return try {
            val arguments = JSONObject(rawArguments)
            when (name) {
                "list_files" -> files.list(arguments.optString("path"))
                    .joinToString("\n").limitOutput()
                "read_file" -> files.read(arguments.getString("path")).content.limitOutput()
                "search_project" -> workspace.search(arguments.getString("query"))
                    .joinToString("\n") { "${it.path}:${it.line}: ${it.text}" }.limitOutput()
                "search_knowledge" -> knowledge.search(arguments.getString("query"))
                    .joinToString("\n") { "${it.document}/${it.section}: ${it.excerpt}" }.limitOutput()
                "research_web" -> {
                    val query = arguments.getString("query")
                    val mode = runCatching { ResearchMode.valueOf(arguments.optString("mode", "BROAD").uppercase()) }.getOrDefault(ResearchModeDetector.detect(query))
                    val sources = arguments.optInt("sources", 50).coerceIn(1, 50)
                    val session = research.deepResearch(query, sources, mode) { progress ->
                        lastResearchProgress = "${progress.stage}: ${progress.completed}/${progress.total}; learned ${progress.successful}, failed ${progress.failed}"
                    }
                    val brief = ResearchBriefBuilder.build(session)
                    "Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, ${brief.wordCount} words, ${brief.codeExampleCount} code examples.\nProgress: $lastResearchProgress\n${brief.evidence}".limitOutput()
                }
                "replace_text" -> {
                    val proposal = mutations.propose("Autonomous model proposed replace_text", listOf(TaskOperation(OperationKind.REPLACE, arguments.getString("path"), arguments.getString("oldText"), arguments.getString("newText"))), arguments.optString("reason", "Autonomous model proposal"))
                    "PROPOSAL_READY id=${proposal.id} changes=${proposal.changeSet.changes.size} approval_required=2"
                }
                "create_file" -> {
                    val proposal = mutations.propose("Autonomous model proposed create_file", listOf(TaskOperation(OperationKind.CREATE_FILE, arguments.getString("path"), text = arguments.getString("content"))), arguments.optString("reason", "Autonomous model proposal"))
                    "PROPOSAL_READY id=${proposal.id} changes=${proposal.changeSet.changes.size} approval_required=2"
                }
                "run_command" -> terminal.execute(arguments.getString("command")).let { "exit=${it.exitCode} timeout=${it.timedOut}\n${it.stdout}\n${it.stderr}".limitOutput() }
                "verify" -> workspace.verify().let { "passed=${it.passed}\n${it.issues.joinToString("\n") { issue -> "${issue.path}:${issue.line}: ${issue.message}" }}".limitOutput() }
                "approve_change" -> approveChange(arguments)
                "reject_change" -> rejectChange(arguments)
                else -> "ERROR: Unknown tool '$name'"
            }
        } catch (error: Exception) {
            "ERROR: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun proposeReplace(arguments: JSONObject): String {
        val proposal = mutations.propose(
            request = arguments.optString("reason", "Model-directed replacement"),
            operations = listOf(TaskOperation(OperationKind.REPLACE, arguments.getString("path"), arguments.getString("oldText"), arguments.getString("newText"))),
            reason = arguments.optString("reason", "Model-directed replacement")
        )
        return "PENDING_APPROVAL id=${proposal.id} approvals=${proposal.approvalCount} changes=${proposal.changeSet.changes.size} verification=${proposal.verification.passed}"
    }

    private fun proposeCreate(arguments: JSONObject): String {
        val proposal = mutations.propose(
            request = arguments.optString("reason", "Model-directed file creation"),
            operations = listOf(TaskOperation(OperationKind.CREATE_FILE, arguments.getString("path"), text = arguments.getString("content"))),
            reason = arguments.optString("reason", "Model-directed file creation")
        )
        return "PENDING_APPROVAL id=${proposal.id} approvals=${proposal.approvalCount} changes=${proposal.changeSet.changes.size} verification=${proposal.verification.passed}"
    }

    private fun approveChange(arguments: JSONObject): String {
        return when (val result = mutations.approve(arguments.getString("id"), arguments.optBoolean("ownerVerified", false), arguments.optString("ownerLabel", "owner"))) {
            is MutationApprovalResult.AwaitingSecond -> "AWAITING_SECOND_APPROVAL id=${result.proposal.id} approvals=${result.proposal.approvalCount}"
            is MutationApprovalResult.Applied -> {
                changeSets += result.changeSet
                "APPLIED id=${result.proposal.id} changes=${result.changeSet.changes.size}"
            }
            is MutationApprovalResult.Rejected -> "ERROR: ${result.reason}"
        }
    }

    private fun rejectChange(arguments: JSONObject): String = if (mutations.reject(arguments.getString("id"))) "REJECTED id=${arguments.getString("id")}" else "ERROR: Change proposal does not exist"

    private fun String.limitOutput(): String = take(config.maxOutputCharacters)

    private fun completedTask(id: String, request: String, plan: AgentPlan, message: String, changes: List<ChangeRecord>, report: VerificationReport) =
        AgentTask(id, request, "completed", plan, changes, report, listOf("${Instant.now()}: model completed: $message"), message)

    private fun failedTask(id: String, request: String, plan: AgentPlan, message: String, changes: List<ChangeRecord>) =
        AgentTask(id, request, "failed", plan, changes, VerificationReport(false, listOf(VerificationIssue("<agent>", 0, message))), listOf("${Instant.now()}: $message"), message)

    private fun approvalTask(id: String, request: String, plan: AgentPlan, proposal: PendingChangeProposal) =
        AgentTask(id, request, "waiting-approval", plan, proposal.changeSet.changes, proposal.verification, listOf("${Instant.now()}: proposal ${proposal.id} staged; awaiting two owner approvals"), "Review proposal ${proposal.id} and confirm twice before applying any code change")
}
