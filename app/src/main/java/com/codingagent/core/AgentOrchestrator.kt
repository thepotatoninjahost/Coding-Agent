package com.codingagent.core

import java.time.Instant

sealed class AgentExecutionEvent {
    data class Started(val request: String) : AgentExecutionEvent()
    data class Phase(val name: String, val detail: String) : AgentExecutionEvent()
    data class Output(val text: String) : AgentExecutionEvent()
    data class Completed(val task: AgentTask) : AgentExecutionEvent()
    data class Failed(val message: String, val task: AgentTask? = null) : AgentExecutionEvent()
}

class AgentOrchestrator(
    private val runtime: CodingAgentRuntime,
    private val terminal: TerminalSession,
    private val researchProvider: WebResearchProvider
) {
    fun execute(request: String): List<AgentExecutionEvent> {
        require(request.isNotBlank()) { "A coding request is required" }
        val events = mutableListOf<AgentExecutionEvent>(AgentExecutionEvent.Started(request.trim()))
        events += AgentExecutionEvent.Phase("INTAKE", "Interpreting the request and checking its execution contract")
        val intake = runtime.intake(request)
        events += AgentExecutionEvent.Phase("PLAN", intake.summary)
        if (!intake.executionReady && intake.intent !in setOf(TaskIntent.INSPECT, TaskIntent.EXPLAIN, TaskIntent.TEST)) {
            val question = intake.clarificationQuestion ?: "Clarify the target and intended operation."
            events += AgentExecutionEvent.Failed(question)
            return events
        }
        events += AgentExecutionEvent.Phase("KNOWLEDGE", "Local reference retrieval is active; internet research is available through the research provider")
        events += AgentExecutionEvent.Phase("EXECUTE", "Running the modular runtime")
        return try {
            when (val result = runtime.execute(request)) {
                is AgentRuntimeResult.Completed -> events + AgentExecutionEvent.Completed(result.task)
                is AgentRuntimeResult.NeedsInput -> events + AgentExecutionEvent.Failed(result.question, result.task)
                is AgentRuntimeResult.NeedsApproval -> events + AgentExecutionEvent.Failed(result.question, result.task)
                is AgentRuntimeResult.Failed -> events + AgentExecutionEvent.Failed(result.task.summary, result.task)
            }
        } catch (error: Exception) {
            events + AgentExecutionEvent.Failed("${Instant.now()}: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun runTerminal(command: String): AgentExecutionEvent.Output {
        val result = terminal.execute(command)
        val output = buildString {
            append("$ ").append(result.command).append('\n')
            append(result.stdout)
            if (result.stderr.isNotBlank()) append(result.stderr)
            append("\nexit=").append(result.exitCode)
            if (result.timedOut) append(" timeout=true")
        }
        return AgentExecutionEvent.Output(output.trimEnd())
    }

    fun research(query: String): ResearchResult = researchProvider.search(query)
}
