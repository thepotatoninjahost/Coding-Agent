package com.codingagent.core

import java.time.Instant
import java.util.UUID

sealed class AgentRuntimeResult {
    data class Completed(val task: AgentTask) : AgentRuntimeResult()
    data class NeedsInput(val task: AgentTask, val question: String) : AgentRuntimeResult()
    data class NeedsApproval(val task: AgentTask, val question: String, val proposalId: String) : AgentRuntimeResult()
    data class Failed(val task: AgentTask) : AgentRuntimeResult()
}

data class AgentRuntimeConfig(
    val maxRepairAttempts: Int = 3,
    val commandTimeoutSeconds: Long = 180,
    val allowEdits: Boolean = true,
    val maxPlanningIterations: Int = 32,
    val maxReplans: Int = 3,
    val maxToolIterations: Int = 32
)

class CodingAgentRuntime(
    private val workspace: ProjectWorkspace,
    private val knowledge: AgentKnowledge,
    private val journal: AgentJournal,
    private val config: AgentRuntimeConfig = AgentRuntimeConfig(),
    private val research: WebResearchProvider? = null,
    private val deepResearch: DeepResearchProvider? = null,
    private val modelGateway: ModelGateway? = null,
    private val mutationCoordinator: MutationCoordinator = MutationCoordinator(workspace)
) : CodingAgentExecutor {
    fun pendingProposals(): List<PendingChangeProposal> = mutationCoordinator.pending()

    fun approveProposal(id: String, ownerVerified: Boolean, ownerLabel: String): MutationApprovalResult = mutationCoordinator.approve(id, ownerVerified, ownerLabel)

    fun rejectProposal(id: String): Boolean = mutationCoordinator.reject(id)

    fun intake(request: String): TaskIntake = TaskIntakeParser(workspace.projectRoot()).parse(request)

    override fun execute(request: String): AgentRuntimeResult {
        require(request.isNotBlank()) { "A coding request is required" }
        val intake = intake(request)
        if (intake.intent == TaskIntent.EXPLAIN || intake.intent == TaskIntent.INSPECT) return executeAuditedInspection(request, intake)
        val taskId = UUID.randomUUID().toString()
        val events = mutableListOf("${Instant.now()}: received request", "intake: ${intake.summary}", "goal: ${intake.contract.goal}", "intent: ${intake.contract.intent}", "targets: ${intake.contract.targetPaths.joinToString().ifBlank { "none" }}", "constraints: ${intake.contract.constraints.joinToString().ifBlank { "none" }}", "acceptance: ${intake.contract.acceptanceCriteria.joinToString()}", "confidence: ${intake.confidence}%")
        val plan = AgentPlanner(workspace).plan(intake)
        val planning = PlanningLoop(plan, config.maxPlanningIterations, config.maxReplans)
        val toolPlan = ToolSelector().select(intake)
        val tools = ToolSelectionLoop(toolPlan, config.maxToolIterations)
        events += "planning initialized with ${plan.steps.size} modular steps"
        events += "tool plan initialized with ${toolPlan.tools.size} selected tools"
        if (!intake.executionReady) return needsInput(taskId, request, plan, emptyList(), events, intake.clarificationQuestion ?: "Clarify the coding request before execution.")
        val model = modelGateway ?: return failure(taskId, request, plan, emptyList(), "The coding model is not loaded; coding execution is blocked.", events)

        // Research is best-effort only. Local project evidence is enough to proceed.
        var researchEvidence = ""
        val wantsResearch = Regex("\\b(research|look up|search the web|documentation online)\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)
        if (wantsResearch && deepResearch != null) {
            val mode = ResearchModeDetector.detect(request)
            val learned = runCatching {
                deepResearch.deepResearch(intake.goal, 6, mode) { progress ->
                    events += "research ${progress.stage}: ${progress.completed}/${progress.total}, learned=${progress.successful}, failed=${progress.failed}"
                }
            }.getOrNull()
            if (learned != null && learned.sources.isNotEmpty()) {
                val brief = ResearchBriefBuilder.build(learned)
                researchEvidence = "\n\nResearch brief:\n${brief.evidence}"
                events += "learned ${brief.sourceCount} sources, ${brief.wordCount} words"
            } else {
                events += "research empty or failed; continuing with local project only"
            }
        } else {
            events += "research skipped; using local project evidence"
        }

        val before = workspace.summary()
        val transcript = mutableListOf<ModelMessage>()
        var evidence = buildString {
            append("Repository files:\n")
            append(before.files.joinToString("\n") { it.path })
            append(researchEvidence)
        }
        completeNext(planning, "intake", "request interpreted", events)
        completeNext(planning, "understand", "repository indexed", events)
        completeNext(planning, "research", if (researchEvidence.isNotBlank()) "sources learned" else "local-only", events)
        while (planning.next()?.phase in setOf("target", "scope", "constraints")) {
            val current = planning.currentSteps().firstOrNull { it.status == PlanStepStatus.ACTIVE } ?: break
            planning.complete("goal contract resolved")
            events += "plan step complete: ${current.phase}"
        }
        var appliedChanges = emptyList<ChangeRecord>()
        var pendingChangeSet: ChangeSet? = null
        var pendingChangeReason: String? = null
        for (turn in 0 until config.maxToolIterations) {
            val response = model.stream(
                ModelRequest(
                    AgentModelProtocol.SYSTEM,
                    buildPrompt(request, intake, evidence),
                    AgentModelProtocol.tools(),
                    transcript.toList(),
                    researchRequired = false
                )
            ) { delta -> events += "model: ${delta.take(240)}" }
            when (response) {
                is ModelResponse.Failure -> return failure(taskId, request, plan, appliedChanges, "Model failed: ${response.message}", events)
                is ModelResponse.Text -> {
                    events += "model completed without further tools"
                    if (requiresEdit(intake) && appliedChanges.isEmpty() && pendingChangeSet == null) {
                        // Allow text-only answers for guidance; do not hard-fail if the model explained instead of editing.
                        events += "no staged edit; returning model answer"
                    }
                    break
                }
                is ModelResponse.ToolCall -> {
                    events += "tool proposed: ${response.name}"
                    val toolResult = stageOrExecute(response, mutationCoordinator, evidence)
                    evidence = toolResult.evidence
                    transcript += ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, response.callId, response.name, response.arguments)
                    transcript += ModelMessage("tool", toolResult.output, response.callId)
                    events += "tool result: ${toolResult.output.take(500)}"
                    if (toolResult.changeSet != null) {
                        pendingChangeSet = toolResult.changeSet
                        pendingChangeReason = "Model proposal: $request"
                        break
                    }
                    if (toolResult.terminalResult != null && toolResult.terminalResult.exitCode != 0) {
                        events += "verification command failed; model must repair from actual output"
                    }
                }
            }
        }
        if (pendingChangeSet != null) {
            val proposal = mutationCoordinator.propose(request, pendingChangeSet!!.changes.map(::taskOperation), pendingChangeReason ?: request)
            return needsApproval(taskId, request, plan, proposal.changeSet.changes, events, proposal.id)
        }
        val report = verify(plan)
        planning.complete("verification checked")
        completeNext(planning, "learn", "outcome persisted", events)
        runCatching { planning.finishIfReady() }
        val task = task(taskId, request, "completed", plan, emptyList(), report, events)
        journal.record(task)
        return AgentRuntimeResult.Completed(task)
    }

    private fun buildPrompt(request: String, intake: TaskIntake, evidence: String): String = buildString {
        append("Coding request:\n").append(request)
        append("\n\nTyped intake:\n").append(intake.summary)
        append("\n\nUse the project files below. Inspect before edits. Propose changes through the transaction tool and wait for owner approval. Do not invent file writes.\n")
        append("\n\nEvidence:\n").append(evidence.take(12_000))
    }

    private fun stageOrExecute(response: ModelResponse.ToolCall, coordinator: MutationCoordinator, evidence: String): ToolExecutionResult {
        return try {
            val args = org.json.JSONObject(response.arguments)
            when (response.name) {
                "list_files" -> ToolExecutionResult("${workspace.summary().files.joinToString("\n") { it.path }}", evidence)
                "read_file" -> ToolExecutionResult(AgentTools(workspace).read(args.getString("path")).content.take(8_000), evidence)
                "search_project" -> ToolExecutionResult(workspace.search(args.getString("query")).joinToString("\n") { "${it.path}:${it.line}: ${it.text}" }.take(8_000), evidence)
                "search_knowledge" -> ToolExecutionResult(knowledge.search(args.getString("query")).joinToString("\n") { "${it.document}/${it.section}: ${it.excerpt}" }.take(8_000), evidence)
                "research_web" -> ToolExecutionResult("Call research only when the user asked for external docs; local project is preferred.", evidence)
                "replace_text", "create_file" -> {
                    val operation = if (response.name == "replace_text") TaskOperation(OperationKind.REPLACE, args.getString("path"), args.getString("oldText"), args.getString("newText")) else TaskOperation(OperationKind.CREATE_FILE, args.getString("path"), text = args.getString("content"))
                    val proposal = coordinator.propose("Model proposed ${response.name}", listOf(operation), args.optString("reason", "Model-directed change"))
                    ToolExecutionResult("PROPOSAL_READY id=${proposal.id} changes=${proposal.changeSet.changes.size} approval_required=2", evidence, proposal.changeSet)
                }
                "run_command" -> {
                    val result = CommandRunner(workspace.projectRoot()).run(listOf("sh", "-c", args.getString("command")), config.commandTimeoutSeconds)
                    ToolExecutionResult("exit=${result.exitCode} timedOut=${result.timedOut}\n${result.stdout}\n${result.stderr}", evidence, terminalResult = result)
                }
                "verify" -> {
                    val report = workspace.verify()
                    ToolExecutionResult("passed=${report.passed}\n${report.issues.joinToString("\n")}", evidence)
                }
                else -> ToolExecutionResult("ERROR: Unknown tool ${response.name}", evidence)
            }
        } catch (error: Exception) {
            ToolExecutionResult("ERROR: ${error.message.orEmpty()}", evidence)
        }
    }

    private fun taskOperation(record: ChangeRecord): TaskOperation = when (record.operation) {
        ChangeOperation.CREATE -> TaskOperation(OperationKind.CREATE_FILE, record.path, text = record.after.orEmpty())
        ChangeOperation.REPLACE -> TaskOperation(OperationKind.REPLACE, record.path, record.before.orEmpty(), record.after.orEmpty())
        ChangeOperation.APPEND -> TaskOperation(OperationKind.APPEND, record.path, text = record.after.orEmpty().removePrefix(record.before.orEmpty()))
        ChangeOperation.REMOVE -> TaskOperation(OperationKind.REMOVE, record.path, oldText = record.before.orEmpty().removeSuffix(record.after.orEmpty()))
    }

    private fun verify(plan: AgentPlan): VerificationReport = if (plan.checks.isEmpty()) workspace.verify() else workspace.runChecks(plan.checks, config.commandTimeoutSeconds)

    private fun requiresEdit(intake: TaskIntake) = intake.operation.kind != OperationKind.NONE || intake.intent in setOf(TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG)

    private fun task(id: String, request: String, status: String, plan: AgentPlan, changes: List<ChangeRecord>, report: VerificationReport, events: List<String>) = AgentTask(id, request, status, plan, changes, report, events, if (status == "completed") "Task completed with verification evidence" else "Task did not complete")

    private fun needsInput(id: String, request: String, plan: AgentPlan, changes: List<ChangeRecord>, events: MutableList<String>, question: String): AgentRuntimeResult.NeedsInput {
        val task = task(id, request, "needs-input", plan, changes, VerificationReport(false, emptyList()), events)
        journal.record(task)
        return AgentRuntimeResult.NeedsInput(task, question)
    }

    private fun needsApproval(id: String, request: String, plan: AgentPlan, changes: List<ChangeRecord>, events: MutableList<String>, proposalId: String): AgentRuntimeResult.NeedsApproval {
        val task = task(id, request, "waiting-approval", plan, changes, VerificationReport(false, listOf(VerificationIssue("<approval>", 0, "Proposal $proposalId requires two owner approvals"))), events)
        journal.record(task)
        return AgentRuntimeResult.NeedsApproval(task, "Review proposal $proposalId and confirm twice before applying any code change.", proposalId)
    }

    private fun failure(id: String, request: String, plan: AgentPlan, changes: List<ChangeRecord>, message: String, events: MutableList<String>, report: VerificationReport = VerificationReport(false, listOf(VerificationIssue("<agent>", 0, message)))): AgentRuntimeResult.Failed {
        events += "failure: $message"
        val task = task(id, request, "failed", plan, changes, report, events)
        journal.record(task)
        return AgentRuntimeResult.Failed(task)
    }

    private data class ToolExecutionResult(val output: String, val evidence: String, val changeSet: ChangeSet? = null, val terminalResult: CommandResult? = null)

    private fun executeAuditedInspection(request: String, intake: TaskIntake): AgentRuntimeResult {
        val taskId = UUID.randomUUID().toString()
        val plan = AgentPlanner(workspace).plan(intake)
        val events = mutableListOf("${Instant.now()}: received request", "intake: ${intake.summary}")
        events += "retrieved ${knowledge.search(intake.goal).size} local knowledge matches"
        val report = workspace.verify()
        val task = task(taskId, request, "completed", plan, emptyList(), report, events + "inspection evidence collected from the indexed project")
        journal.record(task)
        return AgentRuntimeResult.Completed(task)
    }

    private fun completeNext(planning: PlanningLoop, phase: String, evidence: String, events: MutableList<String>) {
        val step = planning.next() ?: return
        if (step.phase != phase) {
            events += "plan phase skip: expected $phase got ${step.phase}"
            return
        }
        events += "plan step active: $phase"
        planning.complete(evidence)
    }
}
