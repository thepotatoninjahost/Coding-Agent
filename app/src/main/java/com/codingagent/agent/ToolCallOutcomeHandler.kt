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
    var lastToolResult: String = ""
    var identicalRepeats = 0
    var repeatResetCount = 0
    var sameSignatureCalls = 0
    var mutationOccurred: Boolean = false
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
 * ONE JOB: Handle one model ToolCall response — scaffold the model toward success.
 *
 * The agent's job is to HELP the model do its work, not block it. When the model
 * loops, the agent diagnoses why and redirects with specific actionable guidance.
 * It only aborts when the model is truly spinning with zero new information and
 * has ignored repeated specific guidance.
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
                response.name == "create_file"
            if (!(changeWork && mutationTool)) {
                state.writeNowRefusals++
                if (changeWork) {
                    transcript += com.codingagent.model.ModelMessage(
                        "user",
                        "SYSTEM: You have enough evidence. Call replace_text or create_file now. " +
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

        val toolKind = ToolKindMapper.kindFor(response.name)
        if (toolKind == null) {
            val msg = "Tool '" + response.name + "' is not authorized by the production tool plan"
            transcript += com.codingagent.model.ModelMessage("user", "SYSTEM: " + msg + " Use only an available planned tool.")
            emit(AutonomousAgentEvent.ToolFinished(response.name, "ERROR: " + msg, false))
            return ToolTurnOutcome.Continue
        }
        val selectionFailure = toolSelectionLoop.authorize(response.name, toolKind)
        if (selectionFailure != null) {
            transcript += com.codingagent.model.ModelMessage(
                "user",
                "SYSTEM: " + selectionFailure + " Gather the required evidence before attempting this tool."
            )
            emit(AutonomousAgentEvent.ToolFinished(response.name, "ERROR: " + selectionFailure, false))
            return ToolTurnOutcome.Continue
        }
        val planningFailure = planningLoop.authorizeTool(response.name, toolKind)
        if (planningFailure != null) {
            transcript += com.codingagent.model.ModelMessage(
                "user",
                "SYSTEM: " + planningFailure + " Follow the active execution plan before attempting this tool."
            )
            emit(AutonomousAgentEvent.ToolFinished(response.name, "ERROR: " + planningFailure, false))
            return ToolTurnOutcome.Continue
        }

        val signature = "${response.name}|${response.arguments.trim()}"
        val isIdenticalCall = signature == state.lastToolSignature
        if (isIdenticalCall) state.sameSignatureCalls++ else state.sameSignatureCalls = 1

        val purpose = ToolPurpose.of(response.name, response.arguments)
        emit(AutonomousAgentEvent.ToolStarted(response.name, response.arguments, purpose))
        if (isCancelled()) return ToolTurnOutcome.Stop
        val toolResult = executeTool(response.name, response.arguments)

        val isMutation = response.name == "replace_text" || response.name == "create_file"
        if (isMutation && !toolResult.startsWith("ERROR:")) {
            state.lastToolSignature = ""
            state.identicalRepeats = 0
            state.mutationOccurred = true
        }

        if (isIdenticalCall && !isMutation && state.sameSignatureCalls >= config.maxIdenticalToolRepeats) {
            state.repeatResetCount++
            if (state.repeatResetCount >= 2) {
                val report = workspace.verify()
                val msg = "Aborted: ${response.name} was repeated with the same arguments ${state.sameSignatureCalls} times without establishing a new path. The model cannot make progress on this path."
                val task = AgentTask(
                    taskId, normalized, "failed", plan,
                    changes(), report,
                    listOf("${Instant.now()}: aborted after repeated identical ${response.name} signatures"),
                    msg
                )
                recordTask(task)
                emit(AutonomousAgentEvent.Failed(task, msg))
                return ToolTurnOutcome.Stop
            }
            state.lastToolSignature = ""
            state.identicalRepeats = 0
            state.sameSignatureCalls = 0
            transcript += com.codingagent.model.ModelMessage(
                "user",
                "SYSTEM: ${response.name} has been called repeatedly with identical arguments. " +
                    "Do NOT call it again with those arguments. Choose a different diagnostic or implementation path."
            )
        }

        if (isIdenticalCall && !isMutation) {
            val resultChanged = toolResult.trim() != state.lastToolResult.trim()
            if (resultChanged && toolResult.isNotBlank() && !toolResult.startsWith("ERROR:")) {
                state.identicalRepeats = 0
                state.repeatResetCount = 0
            } else {
                state.identicalRepeats++
            }

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
                    val report = workspace.verify()
                    val msg = "Aborted: ${response.name} was repeated ${state.identicalRepeats} times " +
                        "with no new results, even after specific guidance. The model cannot make progress on this path."
                    val task = AgentTask(
                        taskId, normalized, "failed", plan,
                        changes(), report,
                        listOf("${Instant.now()}: aborted after ${state.identicalRepeats} identical ${response.name} calls with no new info"),
                        msg
                    )
                    recordTask(task)
                    emit(AutonomousAgentEvent.Failed(task, msg))
                    return ToolTurnOutcome.Stop
                }

                val pendingProposals = mutations.pending()
                val nextStep = when {
                    pendingProposals.isNotEmpty() ->
                        "You have a pending proposal (${pendingProposals.first().id.take(8)}). " +
                            "Do not call approve_change. The owner must approve the pending proposal from Review or chat."
                    state.readPaths.isEmpty() ->
                        "You have not read any project files yet. " +
                            "Call read_file with the path of the first file you need to modify."
                    state.mutationOccurred ->
                        "You have already made changes. Call verify to check the result, " +
                            "then read_file on the next file that needs changing."
                    else ->
                        "You have searched ${state.readPaths.size} file(s). " +
                            "Call read_file on the next specific file you need to change, " +
                            "or call replace_text if you have enough evidence to make the change."
                }

                transcript += com.codingagent.model.ModelMessage(
                    "user",
                    "SYSTEM: ${response.name} returned the same result ${state.identicalRepeats} times. " +
                        "Do NOT call ${response.name} again with these arguments. " +
                        "Next action: $nextStep"
                )
                state.lastToolSignature = ""
                state.identicalRepeats = 0
                if (toolResult.isNotBlank() && toolResult != "(no files)") {
                    state.lastEvidence = toolResult
                }
                state.lastToolResult = toolResult
                return ToolTurnOutcome.Continue
            }
        } else if (!isIdenticalCall) {
            state.lastToolSignature = signature
            state.identicalRepeats = 1
        }

        if (toolResult.isNotBlank() && toolResult != "(no files)") {
            state.lastEvidence = toolResult
        }
        state.lastToolResult = toolResult

        val toolCallId = response.callId ?: "call_${java.util.UUID.randomUUID()}"
        transcript += com.codingagent.model.ModelMessage("assistant", response.thought.ifBlank { "Calling ${response.name}" }, toolCallId, response.name, response.arguments)
        transcript += com.codingagent.model.ModelMessage("tool", "${response.name}: $toolResult", toolCallId)

        val success = !toolResult.startsWith("ERROR:")

        if (success) {
            toolSelectionLoop.recordSuccess(response.name, toolKind, toolResult.take(300))
        } else {
            toolSelectionLoop.recordFailure(response.name, toolKind, toolResult.take(300))
        }
        if (success) {
            planningLoop.recordSuccess(response.name, toolKind, toolResult.take(300))
        } else {
            planningLoop.recordFailure(response.name, toolKind, toolResult.take(300))
        }


        if (success) {
            when (response.name) {
                "read_file" -> {
                    val path = runCatching { JSONObject(response.arguments).getString("path") }.getOrNull()
                    if (!path.isNullOrBlank()) state.readPaths += path.trim().trimStart('/')
                }
                "search_project", "list_files" -> state.searchedProject = true
            }
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
                        append("File: ${path.trim().trimStart('/')}\n\u2500\u2500\u2500\n")
                        append(toolResult.take(12_000))
                        if (toolResult.length > 12_000) append("\n\u2026 (truncated)")
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
                    "SYSTEM: ${response.name} returned the result above. " +
                        "Use that result to identify specific files, then call read_file on each one you need."
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
