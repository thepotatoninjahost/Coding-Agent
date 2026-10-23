package com.codingagent.agent

import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntent
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelMessage
import com.codingagent.model.ModelRequest
import com.codingagent.model.ModelResponse
import com.codingagent.model.JsonModelResponseParser
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.RunReport
import com.codingagent.workspace.VerificationReport
import java.time.Instant

/**
 * ONE JOB: Ask the helper for one action, run it, and repeat.
 * A question can be answered in plain text. A code change has to be run before it is shown for approval.
 */
class ActObserveLoop(
    private val config: AutonomousAgentConfig,
    private val gateway: ModelGateway,
    private val tools: AgentToolDispatch,
    private val mutations: MutationCoordinator,
    private val systemPrompt: () -> String,
    private val repoMap: () -> String,
    private val isCancelled: () -> Boolean,
    private val emit: (AutonomousAgentEvent) -> Unit,
    private val recordTask: (AgentTask) -> Unit,
    private val changes: () -> List<com.codingagent.workspace.ChangeRecord>
) {
    fun run(
        taskId: String,
        request: String,
        intake: TaskIntake,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>
    ): List<AutonomousAgentEvent> {
        val changeWork = intake.intent in setOf(TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG)
        val transcript = mutableListOf<ModelMessage>()
        val steps = mutableListOf<StuckDetector.Step>()
        var formatErrors = 0
        var cutOffs = 0
        var lastRun: RunReport? = null
        var condensed = false
        val user = buildString {
            appendLine(request.trim())
            appendLine()
            appendLine(repoMap())
            appendLine("Do one thing each turn: a tool, a SEARCH/REPLACE block, or submit.")
            appendLine("When the phone must run the program, write JavaScript.")
            appendLine("Answer the owner in plain everyday English.")
        }
        val maxSteps = config.maxTurns.coerceAtLeast(1)
        for (step in 0 until maxSteps) {
            if (isCancelled()) return stop(taskId, request, plan, events)
            emit(AutonomousAgentEvent.Phase("MODEL", "Thinking about the next step (${step + 1} of $maxSteps)."))
            val system = ContextBudget.systemForRequest(systemPrompt())
            val fitted = ContextBudget.fit(system, user, transcript.toList(), ContextBudget.inputTarget())
            val response = ModelCallWithRetry.call(
                gateway = gateway,
                request = {
                    ModelRequest(
                        fitted.system,
                        fitted.user,
                        com.codingagent.model.AgentModelProtocol.toolsForIntent(intake.intent),
                        fitted.transcript
                    )
                },
                isCancelled = isCancelled,
                onPhase = { emit(AutonomousAgentEvent.Phase("MODEL", it)) }
            ) ?: return stop(taskId, request, plan, events)
            if (isCancelled()) return stop(taskId, request, plan, events)
            val action = if (response is ModelResponse.Text) {
                val recovered = JsonModelResponseParser().parse(response.content)
                if (recovered is ModelResponse.ToolCall) recovered else response
            } else {
                response
            }
            when (action) {
                is ModelResponse.Failure -> {
                    if (action.message.contains("output-truncated") || action.message.contains("output limit")) {
                        cutOffs++
                        if (cutOffs >= 2) gateway.preferDifferentModel()
                        transcript += ModelMessage("user", "The last reply was cut off. Send one smaller step and do not repeat the part that was already sent.")
                        steps += StuckDetector.Step("cutoff", "cut off", error = true, textOnly = false)
                        continue
                    }
                    if (com.codingagent.model.ModelCaps.demoteForLongWrites("") ) Unit
                    val friendly = ModelFailure.humanize(action.message)
                    val task = AgentTaskBuilders.failed(taskId, request, plan, friendly, changes())
                    emit(AutonomousAgentEvent.Failed(task, friendly))
                    recordTask(task)
                    return events
                }
                is ModelResponse.ToolCall -> {
                    formatErrors = 0
                    val key = StuckDetector.actionKey(action.name, action.arguments)
                    if (action.name == "submit") {
                        val done = finishSubmit(action.arguments, changeWork, lastRun, taskId, request, plan, events)
                        if (done != null) return done
                        val note = "The work is not ready to finish. Run the program, or say what is still wrong."
                        transcript += ModelMessage("assistant", action.thought.ifBlank { "submit" }, action.callId, action.name, action.arguments)
                        transcript += ModelMessage("tool", note, action.callId ?: "call_unmatched")
                        steps += StuckDetector.Step(key, note, error = true, textOnly = false)
                    } else {
                        val outcome = runTool(action, request)
                        if (outcome.report != null) lastRun = outcome.report
                        transcript += ModelMessage("assistant", action.thought.ifBlank { "Calling ${action.name}" }, action.callId, action.name, action.arguments)
                        transcript += ModelMessage("tool", "${action.name}: ${outcome.text}", action.callId ?: "call_unmatched")
                        steps += StuckDetector.Step(key, outcome.text.take(240), outcome.text.startsWith("ERROR:"), textOnly = false)
                        if (action.name == "review_examples" || action.name == "run_program") {
                            val ready = approvalIfChecked(outcome, lastRun, taskId, request, plan, events)
                            if (ready != null) return ready
                        }
                        if (isListing(request) && action.name == "list_files" && !outcome.text.startsWith("ERROR:")) {
                            return complete(taskId, request, plan, events, outcome.text.ifBlank { "There are no files yet." }, "completed")
                        }
                    }
                }
                is ModelResponse.Text -> {
                    val blocks = com.codingagent.workspace.SearchReplace.parse(action.content)
                    if (blocks.isNotEmpty()) {
                        formatErrors = 0
                        val applied = tools.applySearchBlocks(request, blocks)
                        transcript += ModelMessage("assistant", action.content)
                        transcript += ModelMessage("user", applied)
                        steps += StuckDetector.Step("search-replace", applied.take(240), applied.startsWith("ERROR:"), textOnly = false)
                    } else if (!changeWork) {
                        val fact = transcript.map { it.content }.lastOrNull { line ->
                            "was published on" in line && "Source:" in line
                        }
                        val version = fact?.let { Regex("""\d+\.\d+\.\d+""").find(it)?.value }
                        val answer = if (fact != null && version != null && version !in action.content) {
                            fact.lineSequence().first { "was published on" in it }.trim()
                        } else {
                            PlainEnglish.soften(action.content)
                        }
                        emit(AutonomousAgentEvent.ModelMessage(answer))
                        return complete(taskId, request, plan, events, answer, if (isDegenerate(answer)) "completed-with-warning" else "completed")
                    } else {
                        formatErrors++
                        transcript += ModelMessage("assistant", action.content)
                        transcript += ModelMessage(
                            "user",
                            "Reply with an edit, a tool call, or submit. A description is not the finished program."
                        )
                        steps += StuckDetector.Step("text", action.content.take(120), error = false, textOnly = true)
                        if (formatErrors >= 3) {
                            val summary = "I have not built anything yet. Nothing was saved. " +
                                "The helper only described a plan and asked whether to continue. " +
                                "Tell me to go ahead if you want the files written."
                            return complete(taskId, request, plan, events, summary, "needs-input")
                        }
                    }
                }
            }
            val stuck = StuckDetector.check(steps)
            if (stuck != null) {
                if (!condensed) {
                    condensed = true
                    val kept = ContextBudget.condense(transcript.toList())
                    transcript.clear()
                    transcript.addAll(kept)
                    gateway.preferDifferentModel()
                    transcript += ModelMessage("user", "$stuck Try a different step.")
                    continue
                }
                val summary = "$stuck I stopped. Nothing new was saved."
                val task = AgentTaskBuilders.failed(taskId, request, plan, summary, changes())
                emit(AutonomousAgentEvent.Failed(task, summary))
                recordTask(task)
                return events
            }
        }
        val summary = if (changeWork && mutations.pending().isEmpty()) {
            "I used every step I am allowed and did not finish the change. Nothing was saved."
        } else {
            "I used every step I am allowed. This is as far as I got."
        }
        return complete(taskId, request, plan, events, summary, "completed-with-warning")
    }

    private fun finishSubmit(
        arguments: String,
        changeWork: Boolean,
        lastRun: RunReport?,
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>
    ): List<AutonomousAgentEvent>? {
        val json = runCatching { org.json.JSONObject(arguments) }.getOrNull()
        val status = json?.optString("status").orEmpty().ifBlank { "done" }
        val answer = json?.optString("answer").orEmpty().ifBlank { "I finished this step." }
        val proposal = mutations.pending().firstOrNull()
        if (status == "could_not_fix") {
            val why = PlainEnglish.soften(answer)
            val runNote = lastRun?.let { ChangeDiff.runSentence(it) }.orEmpty()
            return complete(taskId, request, plan, events, "$why $runNote".trim(), "needs-input")
        }
        if (changeWork && proposal == null) return null
        if (changeWork && lastRun != null && lastRun.ran && !lastRun.passed) return null
        if (changeWork && proposal != null && needsRun(proposal) && (lastRun == null || !lastRun.ran)) return null
        if (proposal != null) {
            val card = ChangeDiff.approvalCard(proposal, lastRun)
            val task = AgentTaskBuilders.approval(taskId, request, plan, proposal).copy(summary = card)
            emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
            recordTask(task)
            return events
        }
        return complete(taskId, request, plan, events, PlainEnglish.soften(answer), "completed")
    }

    private fun approvalIfChecked(
        outcome: ToolOutcome,
        lastRun: RunReport?,
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>
    ): List<AutonomousAgentEvent>? {
        if (outcome.text.startsWith("ERROR:")) return null
        val proposal = mutations.pending().firstOrNull() ?: return null
        val report = outcome.report ?: lastRun
        val failed = report != null && report.ran && !report.passed
        if (failed) return null
        val card = ChangeDiff.approvalCard(proposal, report)
        val task = AgentTaskBuilders.approval(taskId, request, plan, proposal).copy(summary = card)
        emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
        recordTask(task)
        return events
    }

    private fun needsRun(proposal: com.codingagent.workspace.PendingChangeProposal): Boolean =
        proposal.changeSet.changes.any { it.path.endsWith(".js") || it.path.endsWith(".py") }

    private data class ToolOutcome(val text: String, val report: RunReport?)

    private fun runTool(action: ModelResponse.ToolCall, request: String): ToolOutcome {
        emit(AutonomousAgentEvent.ToolStarted(action.name, action.arguments, ToolPurpose.of(action.name, action.arguments)))
        if (isCancelled()) return ToolOutcome("Stopped", null)
        val text = tools.execute(action.name, action.arguments)
        val report = tools.lastRun()
        emit(AutonomousAgentEvent.ToolFinished(action.name, text, !text.startsWith("ERROR:")))
        if (action.name == "research_web") {
            emit(AutonomousAgentEvent.Phase("RESEARCH", text.take(400)))
        }
        return ToolOutcome(text, report)
    }

    private fun complete(
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>,
        summary: String,
        status: String
    ): List<AutonomousAgentEvent> {
        val task = AgentTask(
            taskId, request, status, plan, changes(),
            VerificationReport(false, emptyList()),
            listOf("${Instant.now()}: $status"),
            summary
        )
        recordTask(task)
        emit(AutonomousAgentEvent.Completed(task))
        return events
    }

    private fun stop(
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>
    ): List<AutonomousAgentEvent> {
        val task = AgentTaskBuilders.stopped(taskId, request, plan, changes(), "Stopped by owner")
        recordTask(task)
        emit(AutonomousAgentEvent.Stopped(task, "Stopped by owner"))
        return events
    }

    private fun isListing(request: String): Boolean = AgentRequestKind.isListing(request)

    private fun isDegenerate(text: String): Boolean = DegenerateOutput.isDegenerate(text)
}
