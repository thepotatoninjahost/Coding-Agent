package com.codingagent.core

import java.util.UUID

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
    private val runtimeProvider: () -> CodingAgentExecutor?,
    private val progressListener: AgentProgressListener? = null
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
                // Prefer event stream so the UI can show INTAKE / PLAN / RESEARCH / MODEL / tools live.
                val events = runtime.run(withConversationContext(trimmed)) { event ->
                    when (event) {
                        is AutonomousAgentEvent.Phase -> progressListener?.onProgress(event.name, event.detail)
                        is AutonomousAgentEvent.ToolStarted -> progressListener?.onProgress("TOOL", "${event.name}: ${event.arguments.take(80)}")
                        is AutonomousAgentEvent.ToolFinished -> progressListener?.onProgress(
                            "TOOL",
                            if (event.success) "${event.name} ok" else "${event.name} failed"
                        )
                        is AutonomousAgentEvent.ModelDelta -> { /* stream noise; keep last phase */ }
                        is AutonomousAgentEvent.ModelMessage -> progressListener?.onProgress("MODEL", "Writing reply")
                        is AutonomousAgentEvent.Started -> progressListener?.onProgress("STARTED", event.request.take(60))
                        is AutonomousAgentEvent.Completed -> progressListener?.onProgress("DONE", "Completed")
                        is AutonomousAgentEvent.Failed -> progressListener?.onProgress("FAILED", event.message.take(120))
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
                    is AutonomousAgentEvent.Failed -> terminal.task?.let { AgentRuntimeResult.Failed(it) }
                        ?: AgentRuntimeResult.Failed(
                            AgentTask(
                                id = UUID.randomUUID().toString(),
                                request = trimmed,
                                status = "failed",
                                plan = AgentPlan(emptyList(), emptyList()),
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
        val prior = history(12).joinToString("\n") { "${it.role.name.lowercase()}: ${it.content}" }
        return if (prior.isBlank()) request else "Conversation context:\n$prior\n\nCurrent request:\n$request"
    }

    private fun formatTask(task: AgentTask): String = buildString {
        append(task.status)
        append(": ")
        append(task.summary)
        append("\n\nVerification: ")
        append(if (task.verification.passed) "passed" else "failed")
        append("; ")
        append(task.verification.issues.size)
        append(" issue(s)")
        task.verification.issues.forEach { issue ->
            append("\n- ")
            append(issue.path)
            append(":")
            append(issue.line)
            append(" — ")
            append(issue.message)
        }
        if (task.events.isNotEmpty()) {
            append("\n\nActivity log:\n")
            append(task.events.joinToString("\n"))
        }
    }
}

data class ChatTurn(
    val response: ChatMessage,
    val result: AgentRuntimeResult?
)
