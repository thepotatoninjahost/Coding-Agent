package com.codingagent.core

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed class AutonomousAgentEvent {
    data class Started(val goal: String) : AutonomousAgentEvent()
    data class Thinking(val detail: String) : AutonomousAgentEvent()
    data class ToolCall(val name: String, val args: Map<String, Any?>) : AutonomousAgentEvent()
    data class ToolResult(val name: String, val result: String) : AutonomousAgentEvent()
    data class NeedsApproval(val proposal: PendingChangeProposal) : AutonomousAgentEvent()
    data class Message(val role: String, val content: String) : AutonomousAgentEvent()
    data class Completed(val summary: String) : AutonomousAgentEvent()
    data class Failed(val error: String) : AutonomousAgentEvent()
    data class Stopped(val reason: String) : AutonomousAgentEvent()
}

/**
 * Cooperative-cancel autonomous loop with consecutive-tool-failure and identical-tool loop guards.
 * Phase 4.
 */
class AutonomousAgent(
    private val gateway: ModelGateway,
    private val tools: AgentTools,
    private val knowledgeBase: KnowledgeBase,
    private val maxSteps: Int = 24,
    private val maxConsecutiveToolFailures: Int = 3,
    private val maxIdenticalToolCalls: Int = 4
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    suspend fun run(goal: String, onEvent: (AutonomousAgentEvent) -> Unit) {
        cancelled.set(false)
        onEvent(AutonomousAgentEvent.Started(goal))

        val messages = mutableListOf<
            Pair<String, String>
        >()
        messages.add("system" to buildSystemPrompt())
        messages.add("user" to goal)

        var consecutiveFailures = 0
        var lastToolSignature: String? = null
        var identicalCount = 0

        try {
            for (step in 1..maxSteps) {
                if (cancelled.get()) {
                    onEvent(AutonomousAgentEvent.Stopped("Cancelled by user"))
                    return
                }

                onEvent(AutonomousAgentEvent.Thinking("Step $step"))

                val response = gateway.chat(
                    messages = messages.map { (role, content) -> role to content },
                    tools = tools.catalog()
                )

                if (cancelled.get()) {
                    onEvent(AutonomousAgentEvent.Stopped("Cancelled by user"))
                    return
                }

                val text = response.text?.trim().orEmpty()
                val toolCalls = response.toolCalls.orEmpty()

                if (text.isNotEmpty()) {
                    if (DegenerateOutput.isDegenerate(text)) {
                        onEvent(AutonomousAgentEvent.Failed("Degenerate model output detected; stopping"))
                        return
                    }
                    messages.add("assistant" to text)
                    onEvent(AutonomousAgentEvent.Message("assistant", text))
                }

                if (toolCalls.isEmpty()) {
                    onEvent(AutonomousAgentEvent.Completed(text.ifBlank { "Done." }))
                    return
                }

                for (call in toolCalls) {
                    if (cancelled.get()) {
                        onEvent(AutonomousAgentEvent.Stopped("Cancelled by user"))
                        return
                    }

                    val sig = "${call.name}:${call.argumentsJson}"
                    if (sig == lastToolSignature) {
                        identicalCount++
                        if (identicalCount >= maxIdenticalToolCalls) {
                            onEvent(AutonomousAgentEvent.Failed("Identical tool call loop detected ($sig); stopping"))
                            return
                        }
                    } else {
                        lastToolSignature = sig
                        identicalCount = 1
                    }

                    onEvent(AutonomousAgentEvent.ToolCall(call.name, call.arguments))
                    val result = try {
                        tools.execute(call.name, call.arguments)
                    } catch (e: Exception) {
                        consecutiveFailures++
                        if (consecutiveFailures >= maxConsecutiveToolFailures) {
                            onEvent(AutonomousAgentEvent.Failed("Too many consecutive tool failures; stopping"))
                            return
                        }
                        "Error: ${e.message}"
                    }

                    if (result.startsWith("Error:")) {
                        consecutiveFailures++
                        if (consecutiveFailures >= maxConsecutiveToolFailures) {
                            onEvent(AutonomousAgentEvent.Failed("Too many consecutive tool failures; stopping"))
                            return
                        }
                    } else {
                        consecutiveFailures = 0
                    }

                    onEvent(AutonomousAgentEvent.ToolResult(call.name, result))
                    messages.add("tool" to result)

                    // Surface approval needs
                    if (call.name == "propose_edit" || call.name == "propose_create") {
                        // tools layer emits NeedsApproval via side channel if needed;
                        // ChatWorkspace listens for PendingChangeProposal
                    }
                }
            }
            onEvent(AutonomousAgentEvent.Completed("Reached max steps ($maxSteps)"))
        } catch (e: Exception) {
            if (cancelled.get()) {
                onEvent(AutonomousAgentEvent.Stopped("Cancelled by user"))
            } else {
                onEvent(AutonomousAgentEvent.Failed(e.message ?: e.toString()))
            }
        }
    }

    private fun buildSystemPrompt(): String {
        val kb = knowledgeBase.search("", limit = 8).joinToString("\n") { "- ${it.title}: ${it.snippet}" }
        return """
You are an offline-first coding agent on Android. Prefer local tools. Use propose_edit / propose_create for file changes (dual approval required). Be concise. Stop when the goal is met.

Knowledge snippets:
$kb
""".trimIndent()
    }
}
