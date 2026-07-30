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

class ChatWorkspace(
    private val store: ChatMessageStore,
    private val runtimeProvider: () -> CodingAgentExecutor?
) {
    fun history(limit: Int = 100): List<ChatMessage> = store.recentChatMessages(limit).asReversed()

    fun send(request: String): ChatTurn {
        val trimmed = request.trim()
        require(trimmed.isNotEmpty()) { "A message is required" }
        store.recordChatMessage(ChatMessage(role = ChatRole.USER, content = trimmed))
        val runtime = runtimeProvider()
        val result = runtime?.execute(withConversationContext(trimmed))
        val response = when (result) {
            is AgentRuntimeResult.Completed -> ChatMessage(role = ChatRole.AGENT, content = formatTask(result.task), taskId = result.task.id)
            is AgentRuntimeResult.NeedsInput -> ChatMessage(role = ChatRole.AGENT, content = result.question, taskId = result.task.id)
            is AgentRuntimeResult.NeedsApproval -> ChatMessage(role = ChatRole.AGENT, content = result.question, taskId = result.task.id)
            is AgentRuntimeResult.Failed -> ChatMessage(role = ChatRole.AGENT, content = formatTask(result.task), taskId = result.task.id)
            null -> ChatMessage(role = ChatRole.SYSTEM, content = "Choose a project folder before sending coding requests.")
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
