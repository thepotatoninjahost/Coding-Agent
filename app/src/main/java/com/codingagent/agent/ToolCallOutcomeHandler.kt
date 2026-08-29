package com.codingagent.agent

import java.time.Instant
import org.json.JSONObject
import com.codingagent.model.ModelResponse
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.ChangeRecord
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Turn-to-turn state that used to live as loop-local `var`s inside
 * AutonomousAgent.run(). Extracted verbatim — same fields, same meaning, now readable
 * both by the turn-start decision logic (LoopControl.decide) and by ToolCallOutcomeHandler.
 */
class ToolTurnState {
    var lastEvidence: String = ""
    var consecutiveFailures = 0
    var lastToolSignature: String? = null
    var identicalRepeats = 0
    var repeatResetCount = 0
    val readPaths = linkedSetOf<String>()
    var searchedProject = false
    var successfulGathers = 0
    var writeNowRefusals = 0
}

/** ONE JOB: Tell AutonomousAgent.run() whether to loop again or stop (events already emitted). */
sealed class ToolTurnOutcome {
    object Continue : ToolTurnOutcome()
    object Stop : ToolTurnOutcome()
}

/**
 * ONE JOB: Handle one model ToolCall response — writeNow refusal handling, identical-repeat
 * detection, tool execution, plan/tool-selection bookkeeping, success/failure outcomes, and
 * proposal-approval surfacing. Extracted verbatim out of AutonomousAgent.kt's turn loop; no
 * behavior change. All side effects (transcript, emit, recordTask) happen through the same
 * calls the inline version made — only the container changed.
 */
class ToolCallOutcomeHandler(
    private val config: AutonomousAgentConfig,
    private val workspace: ProjectWorkspace,
    private val mutations: MutationCoordinator,
    private val planningLoop: PlanningLoop,
    private val toolSelectionLoop: ToolSelectionLoop,
    private val executeTool: (String, String) -> String,
    private val isListingRequest: (String) -> Boolean,
    private val isSourceFileListRequest: (String) -> Boolean,
    private val currentRequestFocus: (String) -> String,
    private val extractExplicitReadPath: (String) -> String?,
    private val formatListingSummary: (String, VerificationReport, Boolean) -> String,
    private val synthesizeFromEvidence: (String, String, VerificationReport) -> String,
    private val recordTask: (AgentTask) -> Unit,
    private val emit: (AutonomousAgentEvent) -> Unit,
    private val isCancelled: () -> Boolean
) {
    fun handle(
        response: ModelResponse.ToolCall,
        state: ToolTurnState,
        writeNow: Boolean,
        changeWork: Boolean,
        decision: LoopDecision,
        taskId: String,
        normalized: String,
        plan: AgentPlan,
        transcript: MutableList<com.codingagent.model.ModelMessage>,
        changes: () -> List<ChangeRecord>
    ): ToolTurnOutcome {
        if (writeNow) {
            val mutationTool = response.name == "replace_text" ||
                response.name == "create_file" ||
                response.name == "verify"
            if (!(changeWork && mutationTool)) {
                state.writeNowRefusals++
                if (changeWork) {
                    transcript += com.codingagent.model.ModelMessage(
                        "user",
                        "SYSTEM: This is a change task. Call replace_text or create_file. " +
                            "Do not call ${response.name}. A written review is not the work."
                    )
                    return ToolTurnOutcome.Continue
                }
                if (decision.synthesizeFromEvidence || state.writeNowRefusals >= 2) {
                    val report = workspace.verify()
                    val summary = synthesizeFromEvidence(normalized, state.lastEvidence, report)
                    val task = AgentTask(
                        taskId, normalized, "completed-with-warning", plan,
                        changes(), report,
                        listOf("${Instant.now()}: model kept requesting tools after close; finished from evidence"),
                        summary
                    )
                    recordTask(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return ToolTurnOutcome.Stop
                }
                transcript += com.codingagent.model.ModelMessage(
                    "user",
                    "SYSTEM: Tools are closed. Your last reply was a tool call (${response.name}). " +
                        "Write the review in plain text now."
                )
                return ToolTurnOutcome.Continue
            }
        }
        val signature = "${response.name}|${response.arguments.trim()}"
        if (signature == state.lastToolSignature) {
            state.identicalRepeats++
            if (state.identicalRepeats >= config.maxIdenticalToolRepeats) {
                if ((response.name == "list_files" || response.name == "search_project") &&
                    state.lastEvidence.isNotBlank() && !state.lastEvidence.startsWith("ERROR:") &&
                    isListingRequest(currentRequestFocus(normalized))
                ) {
                    val report = workspace.verify()
                    val summary = formatListingSummary(state.lastEvidence, report, isSourceFileListRequest(currentRequestFocus(normalized)))
                    val task = AgentTask(
                        taskId, normalized, "completed", plan,
                        changes(), report,
                        listOf("${Instant.now()}: stopped identical ${response.name} loop; returned last listing"),
                        summary
                    )
                    recordTask(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return ToolTurnOutcome.Stop
                }
                state.repeatResetCount++
                if (state.repeatResetCount >= 2) {
                    // Already nudged once and told the model to stop; it repeated the
                    // identical call again anyway. Do not keep burning turns on a nudge
                    // that has already proven ineffective — abort now, clearly.
                    val report = workspace.verify()
                    val msg = "Aborted: ${response.name} was called identically and repeatedly " +
                        "even after being told to stop. The model is not responding to correction, " +
                        "so continuing would only waste the remaining turn budget."
                    val task = AgentTask(
                        taskId, normalized, "failed", plan,
                        changes(), report,
                        listOf("${Instant.now()}: aborted after repeated identical ${response.name} calls survived a prior warning"),
                        msg
                    )
                    recordTask(task)
                    emit(AutonomousAgentEvent.Failed(task, msg))
                    return ToolTurnOutcome.Stop
                }
                // Any tool with usable evidence: force a final answer. Do not abort the task.
                if (state.lastEvidence.isNotBlank() && !state.lastEvidence.startsWith("ERROR:")) {
                    transcript += com.codingagent.model.ModelMessage(
                        "user",
                        "SYSTEM: Tool ${response.name} was called identically ${state.identicalRepeats} times. " +
                            "Do NOT call any tool again. Write a clear final answer using only the evidence already returned."
                    )
                    state.lastEvidence = state.lastEvidence.take(config.maxOutputCharacters)
                    state.lastToolSignature = ""
                    state.identicalRepeats = 0
                    return ToolTurnOutcome.Continue
                }
                // No usable evidence: nudge a different tool instead of hard-aborting.
                transcript += com.codingagent.model.ModelMessage(
                    "user",
                    "SYSTEM: ${response.name} repeated with the same arguments and returned nothing useful. " +
                        "Call a different tool (list_files, search_project, or read_file on a different path) " +
                        "or give a final answer stating what is missing. Do not repeat the same call."
                )
                state.lastToolSignature = ""
                state.identicalRepeats = 0
                return ToolTurnOutcome.Continue
            }
        } else {
            state.lastToolSignature = signature
            state.identicalRepeats = 1
        }
        emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments))
        if (isCancelled()) return ToolTurnOutcome.Stop
        val toolResult = executeTool(response.name, response.arguments)
        // Never wipe gathered context with an empty tool body.
        if (toolResult.isNotBlank() && toolResult != "(no files)") {
            state.lastEvidence = toolResult
        }
        // Some providers/paths (XML-recovered tool calls especially) never give us a
        // call id. Generate a real one here so the assistant "tool_calls[].id" and the
        // matching "tool" message's "tool_call_id" are always the exact same value —
        // relying on two independently-computed fallbacks (as RemoteHttpGateway used
        // to) risks the ids diverging and the provider rejecting the next turn outright.
        val toolCallId = response.callId ?: "call_${java.util.UUID.randomUUID()}"
        transcript += com.codingagent.model.ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, toolCallId, response.name, response.arguments)
        transcript += com.codingagent.model.ModelMessage("tool", "${response.name}: $toolResult", toolCallId)
        val success = !toolResult.startsWith("ERROR:")
        // Advance the real dependency-tracked plan and, on repeated failure, feed
        // its auto-generated diagnose/recover steps back to the model instead of
        // just silently counting toward the abort threshold below.
        runCatching { planningLoop.next() }.getOrNull()?.let {
            if (success) {
                runCatching { planningLoop.complete(toolResult.take(300)) }
            } else {
                val replanned = runCatching { planningLoop.fail(toolResult.take(300)) }.getOrDefault(false)
                if (replanned) {
                    val guidance = planningLoop.currentSteps()
                        .filter { step -> step.status == PlanStepStatus.PENDING }
                        .takeLast(2)
                        .joinToString("; ") { step -> step.detail }
                    if (guidance.isNotBlank()) {
                        transcript += com.codingagent.model.ModelMessage(
                            "user",
                            "SYSTEM: Repeated failure on ${response.name} — diagnosis: $guidance"
                        )
                    }
                }
            }
        }
        runCatching { toolSelectionLoop.next() }.getOrNull()?.let {
            if (success) {
                runCatching { toolSelectionLoop.complete(toolResult.take(300)) }
            } else {
                runCatching { toolSelectionLoop.fail(toolResult.take(300)) }
            }
        }
        if (success) {
            when (response.name) {
                "read_file" -> {
                    val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                    if (!path.isNullOrBlank()) state.readPaths += path.trim().trimStart('/')
                }
                "search_project", "list_files" -> state.searchedProject = true
            }
            // Only substantive local evidence closes the gather window.
            // list_files alone must not lock out research_web.
            // research_web / search_knowledge stay available until last turns.
            val usefulBody = toolResult.isNotBlank() &&
                toolResult != "(no files)" &&
                !toolResult.equals("(no matches)", ignoreCase = true)
            if (usefulBody && response.name in setOf("read_file", "search_project")) {
                state.successfulGathers++
            }
        }
        emit(AutonomousAgentEvent.ToolFinished(response.name, toolResult, success))
        if (success) {
            state.consecutiveFailures = 0
            if (response.name == "read_file") {
                val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                val focus = currentRequestFocus(normalized)
                if (!path.isNullOrBlank() && (
                        extractExplicitReadPath(focus) != null ||
                            focus.lowercase().contains("read") ||
                            focus.lowercase().contains("show") ||
                            focus.lowercase().contains("open")
                        )
                ) {
                    val report = workspace.verify()
                    val summary = buildString {
                        append("File: ${path.trim().trimStart('/')}\n───\n")
                        append(toolResult.take(12_000))
                        if (toolResult.length > 12_000) append("\n… (truncated)")
                    }
                    val task = AgentTask(
                        taskId, normalized, "completed", plan,
                        changes(), report,
                        listOf("${Instant.now()}: answered read via read_file"),
                        summary
                    )
                    recordTask(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return ToolTurnOutcome.Stop
                }
            }
            if (response.name == "list_files" || response.name == "search_project") {
                if (isListingRequest(currentRequestFocus(normalized))) {
                    val report = workspace.verify()
                    val summary = formatListingSummary(toolResult, report, isSourceFileListRequest(currentRequestFocus(normalized)))
                    val task = AgentTask(
                        taskId, normalized, "completed", plan,
                        changes(), report,
                        listOf("${Instant.now()}: answered listing via ${response.name}"),
                        summary
                    )
                    recordTask(task)
                    emit(AutonomousAgentEvent.Completed(task))
                    return ToolTurnOutcome.Stop
                }
                transcript += com.codingagent.model.ModelMessage(
                    "user",
                    "SYSTEM: ${response.name} already returned the result above. " +
                        "Use that result to answer the user. Do NOT call ${response.name} again with the same arguments."
                )
            }
        } else {
            state.consecutiveFailures++
            if (state.consecutiveFailures >= config.maxConsecutiveToolFailures) {
                val msg = "Aborted after ${state.consecutiveFailures} consecutive tool failures (last: ${response.name})"
                val task = AgentTaskBuilders.failed(taskId, normalized, plan, msg, changes())
                emit(AutonomousAgentEvent.Failed(task, msg))
                recordTask(task)
                return ToolTurnOutcome.Stop
            }
        }
        val proposalId = toolResult.substringAfter("PROPOSAL_READY id=", "").substringBefore(' ').takeIf { it.isNotBlank() }
        if (proposalId != null) {
            val proposal = mutations.get(proposalId)
            if (proposal == null) {
                val task = AgentTaskBuilders.failed(taskId, normalized, plan, "The mutation proposal disappeared before approval: $proposalId", changes())
                emit(AutonomousAgentEvent.Failed(task, task.summary))
                recordTask(task)
            } else {
                val task = AgentTaskBuilders.approval(taskId, normalized, plan, proposal)
                emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
                recordTask(task)
            }
            return ToolTurnOutcome.Stop
        }
        return ToolTurnOutcome.Continue
    }
}
