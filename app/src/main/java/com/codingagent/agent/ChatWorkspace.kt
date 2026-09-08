package com.codingagent.agent

import java.util.UUID
import com.codingagent.workspace.AgentPlan
import com.codingagent.workspace.AgentTask
import com.codingagent.workspace.OpenJobStore
import com.codingagent.workspace.VerificationReport

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
        if (agent != null && looksLikeNewGoal(trimmed)) {
            OpenJobStore.boundRoot()?.let { OpenJobStore.openOrKeep(it, trimmed) }
        }
        val approval = agent?.let { ChatApproval.tryApprove(it, trimmed) }
        if (approval != null) {
            progressListener?.onProgress("APPROVAL", approval.toString().take(80))
            return persist(result = approval)
        }
        val lastAgent = store.recentChatMessages(20).firstOrNull { it.role == ChatRole.AGENT }?.content
        val resumed = agent?.let { PendingWorkResume.tryResume(it, trimmed, lastAgent) }
        if (resumed != null) {
            progressListener?.onProgress("RESUME", "Local pending work / rate-limit gate")
            return persist(result = resumed)
        }
        progressListener?.onProgress("PLANNING", "Starting request")
        val packaged = packageWithMemory(trimmed)
        val result = if (agent == null) {
            null
        } else {
            val events = agent.run(packaged) { event ->
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
                    com.codingagent.workspace.ChangeDiff.ownerReviewText(terminal.proposal),
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
                else -> agent.execute(packaged)
            }
        }
        return persist(result)
    }

    private fun persist(result: AgentRuntimeResult?): ChatTurn {
        val root = OpenJobStore.boundRoot()
        if (root != null) {
            when (result) {
                is AgentRuntimeResult.NeedsApproval -> OpenJobStore.markWaiting(
                    root,
                    result.proposalId,
                    result.task.changes.map { it.path }.distinct(),
                    result.task.request
                )
                is AgentRuntimeResult.Completed -> {
                    if (result.task.status.contains("applied", ignoreCase = true)) {
                        OpenJobStore.markApplied(root)
                    }
                }
                else -> Unit
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

    private fun looksLikeNewGoal(text: String): Boolean {
        val t = text.lowercase()
        if (PendingWorkResume.isResumeRequest(text)) return false
        if (t.length <= 24 && t in setOf("hello", "hi", "status", "list", "list files")) return false
        return t.contains("create") || t.contains("build") || t.contains("implement") ||
            t.contains("fix") || t.contains("add") || t.contains("write")
    }

    private fun packageWithMemory(current: String): String {
        val job = OpenJobStore.loadBound()
        val prior = store.recentChatMessages(16)
            .asReversed()
            .filter { it.role == ChatRole.USER || it.role == ChatRole.AGENT }
            .takeLast(10)
        if (prior.size <= 1 && job == null) return current
        return buildString {
            if (job != null && job.status != "applied") {
                append(job.promptBlock())
                append('\n')
            }
            if (prior.size > 1) {
                append("Conversation so far (oldest first). The last Current request is what to do now.\n")
                for (msg in prior.dropLast(1)) {
                    val who = if (msg.role == ChatRole.USER) "OWNER" else "AGENT"
                    append(who).append(": ").append(msg.content.take(700)).append('\n')
                }
            }
            append("Current request:\n")
            append(current)
        }
    }

    private fun formatTask(task: AgentTask): String = buildString {
        val summary = sanitizeSummary(task.summary)
        if (task.status == "failed" || task.status == "stopped") {
            append(summary)
            return@buildString
        }
        val isDirect =
            task.status == "needs-input" ||
                task.status == "waiting-approval" ||
                summary.startsWith("PROPOSED CHANGES") ||
                summary.startsWith("Hello.") ||
                summary.startsWith("Status report") ||
                summary.startsWith("Project files:") ||
                summary.startsWith("Source files:") ||
                summary.startsWith("Indexed source files") ||
                summary.startsWith("Directory listing:") ||
                summary.startsWith("File:") ||
                summary.startsWith("APPLIED") ||
                summary.startsWith("First approval") ||
                summary.startsWith("No pending proposal") ||
                summary.startsWith("There is no pending") ||
                summary.startsWith("OPEN JOB") ||
                summary.startsWith("Open job is still")

        if (isDirect) {
            append(summary)
            return@buildString
        }

        if (task.changes.isEmpty()) {
            append(summary)
            return@buildString
        }

        append("Status: ")
        append(task.status)
        append("\n\nProposed files:\n")
        task.changes.distinctBy { it.path }.forEach { change ->
            append("- ")
            append(change.path)
            append('\n')
        }
        append("\nVerification: ")
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
            head.startsWith("APPLIED") ||
            head.startsWith("PROPOSED CHANGES") ||
            head.startsWith("OPEN JOB") ||
            head.startsWith("Open job is still")
        ) {
            return text.take(12_000)
        }
        if (text.contains("<tool_call", ignoreCase = true) || text.contains("<function=", ignoreCase = true)) {
            return "The model printed a raw tool call instead of a review. That is not an answer."
        }
        if (DegenerateOutput.isDegenerate(text)) {
            return DegenerateOutput.sanitize(text)
        }
        return text.take(2_000)
    }
}

data class ChatTurn(
    val response: ChatMessage,
    val result: AgentRuntimeResult?
)
