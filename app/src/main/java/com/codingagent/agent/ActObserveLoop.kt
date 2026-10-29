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
        val title = OwnerWords.title(request)
        val focused = SelfContainedProgram.matches(title) || SelfContainedProgram.matches(request)
        val transcript = mutableListOf<ModelMessage>()
        val steps = mutableListOf<StuckDetector.Step>()
        var formatErrors = 0
        var cutOffs = 0
        var lastRun: RunReport? = null
        var condensed = false
        var askedToWrite = false
        var learnedCeiling: Int? = null
        val dailyRemaining = com.codingagent.model.OpenRouterDailyBudget.remaining()
        val stepLimit = if (focused) 8 else config.maxTurns
        val maxSteps = TaskBudget.callsFor(stepLimit, dailyRemaining, changeWork)
        TaskBudget.warning(dailyRemaining, maxSteps)?.let { emit(AutonomousAgentEvent.Phase("MODEL", it)) }
        DebugRunTrace.note("steps=$maxSteps focused=$focused")
        val user = buildString {
            appendLine(if (focused) title else request.trim())
            appendLine()
            appendLine(ContextBudget.todaySentence())
            if (!focused) appendLine(repoMap())
            if (focused) {
                appendLine("Write the whole program in one create_file call. Use JavaScript. Path: compiler.js.")
                appendLine(com.codingagent.workspace.PhoneJavaScript.rules())
                appendLine("Do not look around the project. Do not search. After you write it, the phone runs it.")
                appendLine("If the phone says it did not match, call create_file again with the whole fixed file. That replaces the draft.")
            } else {
                appendLine("Do one thing each turn: a tool, a SEARCH/REPLACE block, or submit.")
                appendLine("When the phone must run the program, write JavaScript.")
                if (changeWork) {
                    appendLine("Start by writing the whole program with create_file. Do not spend the turn exploring.")
                    appendLine("After it is written, the phone runs it. If it does not match, fix the file and write it again.")
                }
            }
            appendLine("Answer the owner in plain everyday English.")
            if (request.contains("one short paragraph", ignoreCase = true)) {
                appendLine("Use one short paragraph. Do not use markdown.")
            }
        }
        for (step in 0 until maxSteps) {
            if (isCancelled()) return stop(taskId, title, plan, events)
            emit(AutonomousAgentEvent.Phase("MODEL", "Thinking about the next step (${step + 1} of $maxSteps)."))
            val system = ContextBudget.systemForRequest(systemPrompt())
            val writingOnly = changeWork || learnedCeiling != null
            val toolList = if (focused) {
                com.codingagent.model.AgentModelProtocol.tools().filter { it.name == "create_file" || it.name == "replace_text" }
            } else {
                com.codingagent.model.AgentModelProtocol.toolsForIntent(intake.intent, writingOnly = writingOnly)
            }
            val ceiling = learnedCeiling ?: if (changeWork) ContextBudget.CHANGE_REQUEST_TOKENS else ContextBudget.inputTarget()
            val room = (ceiling - ContextBudget.toolTokens(toolList) - ContextBudget.estimateTokens(system)).coerceAtLeast(600)
            val fitted = ContextBudget.fit(system, user, transcript.toList(), room)
            val response = ModelCallWithRetry.call(
                gateway = gateway,
                request = {
                    ModelRequest(
                        fitted.system,
                        fitted.user,
                        toolList,
                        fitted.transcript
                    )
                },
                isCancelled = isCancelled,
                onPhase = { emit(AutonomousAgentEvent.Phase("MODEL", it)) }
            ) ?: return stop(taskId, title, plan, events).also { DebugRunTrace.finish("stopped") }
            if (isCancelled()) return stop(taskId, title, plan, events).also { DebugRunTrace.finish("stopped") }
            val action = if (response is ModelResponse.Text) {
                val recovered = JsonModelResponseParser().parse(response.content)
                if (recovered is ModelResponse.ToolCall) recovered else response
            } else {
                response
            }
            when (action) {
                is ModelResponse.Failure -> {
                    if (ModelFailure.isRequestTooLarge(action.message)) {
                        cutOffs++
                        gateway.preferDifferentModel()
                        val limit = ModelFailure.minuteTokenLimit(action.message)
                        learnedCeiling = if (limit != null) {
                            (limit - 2_000).coerceAtLeast(1_200)
                        } else {
                            ((learnedCeiling ?: ContextBudget.CHANGE_REQUEST_TOKENS) * 2 / 3).coerceAtLeast(1_200)
                        }
                        val kept = ContextBudget.condense(transcript.toList())
                        transcript.clear()
                        transcript.addAll(kept)
                        transcript += ModelMessage(
                            "user",
                            "That helper said the request was too big. I shortened it and will try another helper. Send one smaller step."
                        )
                        if (cutOffs >= 3) {
                            val summary = "This is unfinished. The request was too big for the helpers I tried. Nothing was saved."
                            return complete(taskId, title, plan, events, summary, "needs-input")
                        }
                        continue
                    }
                    if (action.message.contains("output-truncated") || action.message.contains("output limit")) {
                        cutOffs++
                        if (cutOffs >= 2) gateway.preferDifferentModel()
                        transcript += ModelMessage("user", "The last reply was cut off. Send one smaller step and do not repeat the part that was already sent.")
                        steps += StuckDetector.Step("cutoff", "cut off", error = true, textOnly = false)
                        continue
                    }
                    if (com.codingagent.model.ModelCaps.demoteForLongWrites("") ) Unit
                    DebugRunTrace.note("model failure: ${action.message.take(300)}")
                    val runNow = lastRun ?: gateStagedJavaScript(request)
                    if (runNow != null) lastRun = runNow
                    if (readyToShow(runNow)) {
                        DebugRunTrace.finish("approval after a passing run")
                        return showApproval(runNow, taskId, title, plan, events)
                    }
                    val friendly = ModelFailure.humanize(action.message)
                    val summary = if (changeWork && "nothing was saved" !in friendly.lowercase()) {
                        "$friendly This is unfinished. Nothing was saved."
                    } else {
                        friendly
                    }
                    val task = AgentTaskBuilders.failed(taskId, title, plan, summary, changes())
                    emit(AutonomousAgentEvent.Failed(task, summary))
                    recordTask(task)
                    DebugRunTrace.finish(summary.take(200))
                    return events
                }
                is ModelResponse.ToolCall -> {
                    formatErrors = 0
                    val key = StuckDetector.actionKey(action.name, action.arguments)
                    if (action.name == "submit") {
                        val done = finishSubmit(action.arguments, changeWork, lastRun, taskId, title, plan, events)
                        if (done != null) return done
                        val note = "The work is not ready to finish. Run the program, or say what is still wrong."
                        transcript += ModelMessage("assistant", action.thought.ifBlank { "submit" }, action.callId, action.name, action.arguments)
                        transcript += ModelMessage("tool", note, action.callId ?: "call_unmatched")
                        steps += StuckDetector.Step(key, note, error = true, textOnly = false)
                    } else {
                        DebugRunTrace.note("tool ${action.name}")
                        val outcome = runTool(action, request)
                        DebugRunTrace.note("tool ${action.name} result: ${outcome.text.take(240)}")
                        if (outcome.report != null) lastRun = outcome.report
                        transcript += ModelMessage("assistant", action.thought.ifBlank { "Calling ${action.name}" }, action.callId, action.name, action.arguments)
                        transcript += ModelMessage("tool", "${action.name}: ${outcome.text}", action.callId ?: "call_unmatched")
                        steps += StuckDetector.Step(key, outcome.text.take(500), outcome.text.startsWith("ERROR:"), textOnly = false)
                        val gated = if (action.name in setOf("create_file", "replace_text", "edit", "append_chunk")) {
                            gateStagedJavaScript(request)
                        } else {
                            null
                        }
                        if (gated != null) {
                            lastRun = gated
                            emit(AutonomousAgentEvent.Phase("RUN", gated.stdout.take(500)))
                            if (!gated.passed) {
                                val brief = fixBrief(gated)
                                if (focused) {
                                    transcript.clear()
                                    transcript += ModelMessage("user", brief)
                                } else {
                                    transcript += ModelMessage("user", brief)
                                }
                            } else if (readyToShow(lastRun)) {
                                return showApproval(lastRun, taskId, title, plan, events)
                            }
                        }
                        if (action.name == "review_examples" || action.name == "run_program") {
                            val ready = approvalIfChecked(outcome, lastRun, taskId, title, plan, events)
                            if (ready != null) return ready
                            if (outcome.report != null && outcome.report.ran && !outcome.report.passed) {
                                transcript += ModelMessage(
                                    "user",
                                    "The examples did not match. Fix the file, then call review_examples again. Include a bad input that should print the word error."
                                )
                            }
                        }
                        if (!changeWork && action.name == "research_web") {
                            sourcedFact(outcome.text)?.let { fact ->
                                emit(AutonomousAgentEvent.ModelMessage(fact))
                                return complete(taskId, title, plan, events, fact, "completed")
                            }
                        }
                        if (isListing(request) && action.name == "list_files" && !outcome.text.startsWith("ERROR:")) {
                            return complete(taskId, title, plan, events, outcome.text.ifBlank { "There are no files yet." }, "completed")
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
                        val gated = gateStagedJavaScript(request)
                        if (gated != null) {
                            lastRun = gated
                            emit(AutonomousAgentEvent.Phase("RUN", gated.stdout.take(500)))
                            if (!gated.passed) {
                                transcript += ModelMessage(
                                    "user",
                                    "The phone ran the program and it did not match.\n${gated.stdout}\nFix the file."
                                )
                            } else if (readyToShow(lastRun)) {
                                return showApproval(lastRun, taskId, title, plan, events)
                            }
                        }
                    } else if (mutations.pending().isNotEmpty()) {
                        if (readyToShow(lastRun)) return showApproval(lastRun, taskId, title, plan, events)
                        formatErrors++
                        transcript += ModelMessage("assistant", action.content)
                        transcript += ModelMessage(
                            "user",
                            "The file is only staged. Call review_examples now with at least two examples. One must be a bad input that expects the word error. If the run fails, fix the file and run it again. Do not describe the file."
                        )
                        steps += StuckDetector.Step("text", action.content.take(120), error = false, textOnly = true)
                        if (formatErrors >= 3) return unfinished(taskId, title, plan, events, lastRun)
                    } else if (!changeWork) {
                        val fact = transcript.mapNotNull { sourcedFact(it.content) }.lastOrNull()
                        val version = fact?.let { Regex("""\d+\.\d+\.\d+""").find(it)?.value }
                        val answer = if (fact != null && version != null && version !in action.content) {
                            fact
                        } else {
                            PlainEnglish.soften(
                                action.content,
                                oneShortParagraph = request.contains("one short paragraph", ignoreCase = true)
                            )
                        }
                        emit(AutonomousAgentEvent.ModelMessage(answer))
                        return complete(taskId, title, plan, events, answer, if (isDegenerate(answer)) "completed-with-warning" else "completed")
                    } else {
                        formatErrors++
                        transcript += ModelMessage("assistant", action.content)
                        transcript += ModelMessage(
                            "user",
                            "Reply with an edit, a tool call, or submit. A description is not the finished program."
                        )
                        steps += StuckDetector.Step("text", action.content.take(120), error = false, textOnly = true)
                        if (formatErrors >= if (focused) 1 else 3) {
                            if (mutations.pending().isNotEmpty()) {
                                return if (readyToShow(lastRun)) showApproval(lastRun, taskId, title, plan, events)
                                else unfinished(taskId, title, plan, events, lastRun)
                            }
                            val summary = "I have not built anything yet. Nothing was saved. " +
                                "The helper only described a plan and asked whether to continue. " +
                                "Tell me to go ahead if you want the files written."
                            return complete(taskId, title, plan, events, summary, "needs-input")
                        }
                    }
                }
            }
            val stuck = StuckDetector.check(steps)
            if (stuck != null) {
                val pendingAnswer = bestEnding(taskId, title, plan, events, lastRun)
                if (pendingAnswer != null) return pendingAnswer
                val hardStop = stuck.contains("same result") || stuck.contains("same file")
                if (!condensed && !hardStop) {
                    condensed = true
                    val kept = ContextBudget.condense(transcript.toList())
                    transcript.clear()
                    transcript.addAll(kept)
                    gateway.preferDifferentModel()
                    transcript += ModelMessage("user", "$stuck Try a different step.")
                    continue
                }
                val fact = bestFact(transcript)
                if (fact != null) return complete(taskId, title, plan, events, fact, "completed")
                val summary = "$stuck I stopped. Nothing new was saved."
                val task = AgentTaskBuilders.failed(taskId, title, plan, summary, changes())
                emit(AutonomousAgentEvent.Failed(task, summary))
                recordTask(task)
                return events
            }
            if (changeWork && mutations.pending().isEmpty() && !askedToWrite && steps.isNotEmpty()) {
                askedToWrite = true
                transcript += ModelMessage(
                    "user",
                    "Write the JavaScript file now with create_file. Put the whole program in that one call. Do not explore any more. The phone will run it and tell you what to fix."
                )
            }
        }
        bestEnding(taskId, title, plan, events, lastRun)?.let { return it }
        if (changeWork && mutations.pending().isNotEmpty() && !readyToShow(lastRun)) {
            return unfinished(taskId, title, plan, events, lastRun)
        }
        val fact = bestFact(transcript)
        if (fact != null) return complete(taskId, title, plan, events, fact, "completed")
        val summary = if (changeWork && mutations.pending().isEmpty()) {
            "I used every step I am allowed and did not finish the change. Nothing was saved."
        } else if (changeWork) {
            "This is unfinished. I stopped before the change was ready, so nothing was saved."
        } else {
            "I used every step I am allowed. This is as far as I got."
        }
        return complete(taskId, title, plan, events, summary, "completed-with-warning")
    }

    private fun bestEnding(
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>,
        lastRun: RunReport?
    ): List<AutonomousAgentEvent>? {
        if (mutations.pending().isEmpty()) return null
        return if (readyToShow(lastRun)) showApproval(lastRun, taskId, request, plan, events)
        else unfinished(taskId, request, plan, events, lastRun)
    }

    private fun readyToShow(lastRun: RunReport?): Boolean {
        val proposal = mutations.pending().firstOrNull() ?: return false
        if (!needsRun(proposal)) return true
        return lastRun != null && lastRun.ran && lastRun.passed
    }

    private fun unfinished(
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>,
        lastRun: RunReport? = null
    ): List<AutonomousAgentEvent> {
        val report = lastRun ?: gateStagedJavaScript(request)
        if (report != null && report.ran && report.passed && readyToShow(report)) {
            DebugRunTrace.finish("approval after a passing run")
            return showApproval(report, taskId, request, plan, events)
        }
        val draft = stagedFile()
        val summary = if (report != null && report.ran) {
            buildString {
                append("This is unfinished. The phone ran the program and it did not match.\n")
                append(report.stdout.take(800))
                if (draft != null) {
                    append("\n\nThe draft is still here. Nothing is saved until you say yes.\nFile: ")
                    append(draft.first)
                    append('\n')
                    append(draft.second.take(1500))
                } else {
                    append("\nNothing was saved.")
                }
            }
        } else {
            "This is unfinished. I have not run it, so nothing was saved."
        }
        DebugRunTrace.finish(summary.take(200))
        return complete(taskId, request, plan, events, summary, "needs-input")
    }

    private fun bestFact(transcript: List<ModelMessage>): String? =
        transcript.mapNotNull { sourcedFact(it.content) }.lastOrNull()

    private fun sourcedFact(text: String): String? {
        if ("was published on" !in text || "Source:" !in text) return null
        return text.lineSequence().firstOrNull { "was published on" in it && "Source:" in it }?.trim()
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

    private fun showApproval(
        lastRun: RunReport?,
        taskId: String,
        request: String,
        plan: AgentPlan,
        events: MutableList<AutonomousAgentEvent>
    ): List<AutonomousAgentEvent> {
        val proposal = mutations.pending().firstOrNull()
            ?: return complete(taskId, request, plan, events, "Nothing is waiting for approval.", "needs-input")
        if (lastRun?.passed == true && lastRun.cases.isNotEmpty()) mutations.noteChecks(proposal.id, true)
        val card = ChangeDiff.approvalCard(proposal, lastRun)
        val task = AgentTaskBuilders.approval(taskId, request, plan, proposal).copy(summary = card)
        emit(AutonomousAgentEvent.ApprovalRequired(task, proposal))
        recordTask(task)
        return events
    }

    private fun needsRun(proposal: com.codingagent.workspace.PendingChangeProposal): Boolean =
        proposal.changeSet.changes.any { it.path.endsWith(".js") || it.path.endsWith(".py") }

    private fun stagedFile(): Pair<String, String>? {
        val change = mutations.pending().firstOrNull()?.changeSet?.changes?.firstOrNull { !it.after.isNullOrBlank() }
            ?: return null
        return change.path to change.after.orEmpty()
    }

    private fun fixBrief(report: RunReport): String {
        val draft = stagedFile()
        return buildString {
            appendLine("The phone ran the program and it did not match.")
            report.cases.take(8).forEach { case ->
                appendLine("${case.label} => ${case.actual} (expected ${case.expected})")
            }
            if (report.cases.isEmpty()) appendLine(report.stdout.take(800))
            if (report.stderr.isNotBlank()) appendLine(report.stderr.take(400))
            if (draft != null) {
                appendLine("Current file ${draft.first}:")
                appendLine(draft.second.take(2500))
            }
            appendLine("Call create_file again with the whole fixed file. That replaces this draft.")
            appendLine("A bad input must print the word error.")
            append(com.codingagent.workspace.PhoneJavaScript.rules())
        }
    }

    private fun gateStagedJavaScript(request: String): com.codingagent.workspace.RunReport? {
        val proposal = mutations.pending().firstOrNull() ?: return null
        val code = proposal.changeSet.changes.firstOrNull { it.path.endsWith(".js") }?.after ?: return null
        if (code.isBlank()) return null
        return com.codingagent.workspace.JsExampleGate.check(code, request)
    }

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
