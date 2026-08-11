package com.codingagent.core

import java.util.UUID

/**
 * ONE JOB: Chat turn → agent execution → persisted history.
 */
enum class ChatRole { USER, AGENT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val taskId: String? = null
)

interface ChatMessageStore {
    fun recordChatMessage(message: ChatMessage)
    fun recentChatMessages(limit: Int = 100): List<ChatMessage>
}

fun interface CodingAgentExecutor {
    fun execute(request: String): AgentRuntimeResult
}

/** Optional progress sink used by the UI to show real phases instead of a fake "Researching" label. */
fun interface AgentProgressListener {
    fun onProgress(phase: String, detail: String)
}

class ChatWorkspace(
    private val store: ChatMessageStore,
    private val unavailableMessageProvider: () -> String = { "Model unavailable. Finish model setup before sending coding requests." },
    private val progressListener: AgentProgressListener? = null,
    private val runtimeProvider: () -> CodingAgentExecutor?
) {
    fun history(limit: Int = 100): List<ChatMessage> = store.recentChatMessages(limit).asReversed()

    fun send(request: String): ChatTurn {
        val trimmed = request.trim()
        require(trimmed.isNotEmpty()) { "A message is required" }
        store.recordChatMessage(ChatMessage(role = ChatRole.USER, content = trimmed))
        val runtime = runtimeProvider()
        progressListener?.onProgress("PLANNING", "Starting request")
        val result = when (runtime) {
            is AutonomousAgent -> {
                val events = runtime.run(withConversationContext(trimmed)) { event ->
                    when (event) {
                        is AutonomousAgentEvent.Phase -> progressListener?.onProgress(event.name, event.detail)
                        is AutonomousAgentEvent.ToolStarted -> progressListener?.onProgress("TOOL", "${event.name}: ${event.arguments.take(80)}")
                        is AutonomousAgentEvent.ToolFinished -> progressListener?.onProgress(
                            "TOOL",
                            if (event.success) "${event.name} ok" else "${event.name} failed"
                        )
                        is AutonomousAgentEvent.ModelDelta -> progressListener?.onProgress("MODEL", "Streaming… ${event.text.take(40)}")
                        is AutonomousAgentEvent.ModelMessage -> progressListener?.onProgress("MODEL", "Writing reply")
                        is AutonomousAgentEvent.Started -> progressListener?.onProgress("STARTED", event.request.take(60))
                        is AutonomousAgentEvent.Completed -> progressListener?.onProgress("DONE", "Completed")
                        is AutonomousAgentEvent.Failed -> progressListener?.onProgress("FAILED", event.message.take(120))
                        is AutonomousAgentEvent.Stopped -> progressListener?.onProgress("STOPPED", event.message.take(120))
                        is AutonomousAgentEvent.ApprovalRequired -> progressListener?.onProgress("APPROVAL", "Waiting for owner approval")
                    }
                }
                when (val terminal = events.lastOrNull()) {
                    is AutonomousAgentEvent.ApprovalRequired -> AgentRuntimeResult.NeedsApproval(
                        terminal.task,
                        "Review proposal ${terminal.proposal.id} and confirm twice before applying any code change.",
                        terminal.proposal.id
                    )
                    is AutonomousAgentEvent.Completed -> AgentRuntimeResult.Completed(terminal.task)
                    is AutonomousAgentEvent.Stopped -> AgentRuntimeResult.Failed(terminal.task)
                    is AutonomousAgentEvent.Failed -> terminal.task?.let { AgentRuntimeResult.Failed(it) }
                        ?: AgentRuntimeResult.Failed(
                            AgentTask(
                                id = UUID.randomUUID().toString(),
                                request = trimmed,
                                status = "failed",
                                plan = AgentPlan(trimmed, emptyList(), emptyList()),
                                changes = emptyList(),
                                verification = VerificationReport(false, emptyList()),
                                events = emptyList(),
                                summary = terminal.message
                            )
                        )
                    else -> runtime.execute(withConversationContext(trimmed))
                }
            }
            else -> runtime?.execute(withConversationContext(trimmed))
        }
        val response = when (result) {
            is AgentRuntimeResult.Completed -> ChatMessage(role = ChatRole.AGENT, content = formatTask(result.task), taskId = result.task.id)
            is AgentRuntimeResult.NeedsInput -> ChatMessage(role = ChatRole.AGENT, content = result.question, taskId = result.task.id)
            is AgentRuntimeResult.NeedsApproval -> ChatMessage(role = ChatRole.AGENT, content = result.question, taskId = result.task.id)
            is AgentRuntimeResult.Failed -> ChatMessage(role = ChatRole.AGENT, content = formatTask(result.task), taskId = result.task.id)
            null -> ChatMessage(role = ChatRole.SYSTEM, content = unavailableMessageProvider())
        }
        store.recordChatMessage(response)
        return ChatTurn(response, result)
    }

    private fun withConversationContext(request: String): String {
        // SYSTEM messages are UI gate text ("show active", "choose a project folder").
        // Feeding them into GoalInterpreter flipped intent to INSPECT via the word "show".
        val prior = history(12)
            .filter { it.role == ChatRole.USER || it.role == ChatRole.AGENT }
            .joinToString("\n") { "${it.role.name.lowercase()}: ${it.content.take(400)}" }
        return if (prior.isBlank()) request else "Conversation context:\n$prior\n\nCurrent request:\n$request"
    }

    private fun formatTask(task: AgentTask): String = buildString {
        append("Status: ")
        append(task.status)
        append("\n\nVerification: ")
        append(if (task.verification.passed) "passed" else "FAILED")
        append("; ")
        append(task.verification.issues.size)
        append(" issue(s)")
        if (task.verification.issues.isEmpty()) {
            append("\n- Static scan found no unfinished-work markers in production sources.")
        } else {
            task.verification.issues.forEach { issue ->
                append("\n- ")
                append(issue.path)
                append(":")
                append(issue.line)
                append(" — ")
                append(issue.message)
            }
        }
        append("\n\nSummary:\n")
        append(sanitizeSummary(task.summary))
        if (task.events.isNotEmpty()) {
            append("\n\nActivity log:\n")
            val cleaned = task.events.map { sanitizeSummary(it) }.distinct().take(40)
            append(cleaned.joinToString("\n"))
        }
    }

    private fun sanitizeSummary(text: String): String {
        if (text.isBlank()) return "(empty)"
        if (DegenerateOutput.isDegenerate(text)) {
            return DegenerateOutput.sanitize(text) + " Rely on the verification section above for the real findings."
        }
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val deduped = mutableListOf<String>()
        var prev: String? = null
        var streak = 0
        for (line in lines) {
            if (line == prev) {
                streak++
                if (streak <= 2) deduped += line
            } else {
                if (streak > 2) deduped += "… (repeated ${streak - 2} more times)"
                deduped += line
                prev = line
                streak = 1
            }
        }
        if (streak > 2) deduped += "… (repeated ${streak - 2} more times)"
        return deduped.joinToString("\n").take(2_000)
    }

}

data class ChatTurn(
    val response: ChatMessage,
    val result: AgentRuntimeResult?
)
