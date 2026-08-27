package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.VerificationReport
import com.codingagent.workspace.AgentTask

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

fun interface AgentProgressListener {
    fun onProgress(phase: String, detail: String)
}

class ChatWorkspace(
    private val store: ChatMessageStore,
    private val unavailableMessageProvider: () -> String = { "Model unavailable. Finish model setup before sending coding requests." },
    private val progressListener: AgentProgressListener? = null,
    private val runtimeProvider: () -> AutonomousAgent?
) {
    fun history(limit: Int = 100): List<ChatMessage> = store.recentChatMessages(limit).asReversed()

    fun send(request: String): ChatTurn {
        val trimmed = request.trim()
        require(trimmed.isNotEmpty()) { "A message is required" }
        store.recordChatMessage(ChatMessage(role = ChatRole.USER, content = trimmed))
        val agent = runtimeProvider()
        val approval = agent?.let { ChatApproval.tryApprove(it, trimmed) }
        if (approval != null) {
            progressListener?.onProgress("APPROVAL", approval.toString().take(80))
            val response = when (approval) {
                is AgentRuntimeResult.Completed -> ChatMessage(role = ChatRole.AGENT, content = formatTask(approval.task), taskId = approval.task.id)
                is AgentRuntimeResult.NeedsApproval -> ChatMessage(role = ChatRole.AGENT, content = approval.question, taskId = approval.task.id)
                is AgentRuntimeResult.NeedsInput -> ChatMessage(role = ChatRole.AGENT, content = approval.question, taskId = approval.task.id)
                is AgentRuntimeResult.Failed -> ChatMessage(role = ChatRole.AGENT, content = formatTask(approval.task), taskId = approval.task.id)
            }
            store.recordChatMessage(response)
            return ChatTurn(response, approval)
        }
        progressListener?.onProgress("PLANNING", "Starting request")
        val result = if (agent == null) {
            null
        } else {
            val events = agent.run(trimmed) { event ->
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
                else -> agent.execute(trimmed)
            }
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

    private fun formatTask(task: AgentTask): String = buildString {
        val summary = sanitizeSummary(task.summary)
        if (task.status == "failed" || task.status == "stopped") {
            append(summary)
            return@buildString
        }
        val isDirect =
            task.status == "needs-input" ||
                summary.startsWith("Hello.") ||
                summary.startsWith("Status report") ||
                summary.startsWith("Project files:") ||
                summary.startsWith("Source files:") ||
                summary.startsWith("Indexed source files") ||
                summary.startsWith("Directory listing:") ||
                summary.startsWith("File:") ||
                summary.startsWith("APPLIED") ||
                summary.startsWith("First approval") ||
                summary.startsWith("No pending proposal")

        if (isDirect) {
            append(summary)
            return@buildString
        }

        append("Status: ")
        append(task.status)
        append("\n\nVerification: ")
        append(if (task.verification.passed) "passed" else "FAILED")
        append("; ")
        append(task.verification.issues.size)
        append(" issue(s)\n\nSummary:\n")
        append(summary)
    }

    private fun sanitizeSummary(text: String): String {
        if (text.isBlank()) return "(empty)"
        val head = text.trimStart()
        if (head.startsWith("Project files:") ||
            head.startsWith("Source files:") ||
            head.startsWith("Indexed source files") ||
            head.startsWith("Directory listing:") ||
            head.startsWith("File:") ||
            head.startsWith("Hello.") ||
            head.startsWith("Status report") ||
            head.startsWith("APPLIED")
        ) {
            return text.take(12_000)
        }
        if (text.contains("<tool_call", ignoreCase = true) || text.contains("<function=", ignoreCase = true)) {
            return "The model printed a raw tool call instead of a review. That is not an answer."
        }
        if (DegenerateOutput.isDegenerate(text)) {
            return DegenerateOutput.sanitize(text) + " Rely on the verification section above for the real findings."
        }
        return text.take(2_000)
    }
}

data class ChatTurn(
    val response: ChatMessage,
    val result: AgentRuntimeResult?
)
