package com.codingagent.core

import java.io.File
import java.time.Instant

/**
 * ONE JOB: Persist chat history and format task results for the UI.
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: String,
    val taskId: String? = null
)

enum class ChatRole { USER, AGENT, SYSTEM }

data class ChatTaskRecord(
    val id: String,
    val request: String,
    val status: String,
    val summary: String,
    val verification: VerificationReport,
    val events: List<String>,
    val createdAt: String,
    val completedAt: String?
)

class ChatWorkspace(private val root: File) {
    private val dir = root.resolve(".coding-agent/chat")
    private val messagesFile = dir.resolve("messages.tsv")
    private val tasksFile = dir.resolve("tasks.tsv")

    init { dir.mkdirs() }

    fun messages(): List<ChatMessage> {
        if (!messagesFile.isFile) return emptyList()
        return messagesFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            ChatMessage(
                id = parts[0],
                role = runCatching { ChatRole.valueOf(parts[1]) }.getOrElse { ChatRole.SYSTEM },
                text = parts[2].replace("\\n", "\n").replace("\\t", "\t"),
                createdAt = parts[3],
                taskId = parts.getOrNull(4)?.ifBlank { null }
            )
        }
    }

    fun append(role: ChatRole, text: String, taskId: String? = null): ChatMessage {
        val msg = ChatMessage(
            id = "msg-${System.currentTimeMillis()}-${(0..9999).random()}",
            role = role,
            text = text,
            createdAt = Instant.now().toString(),
            taskId = taskId
        )
        val escaped = msg.text.replace("\t", "\\t").replace("\n", "\\n")
        messagesFile.appendText(listOf(msg.id, msg.role.name, escaped, msg.createdAt, msg.taskId.orEmpty()).joinToString("\t") + "\n")
        return msg
    }

    fun recordTask(task: ChatTaskRecord) {
        val escapedSummary = task.summary.replace("\t", "\\t").replace("\n", "\\n")
        val escapedEvents = task.events.joinToString(" | ").replace("\t", "\\t").replace("\n", "\\n")
        tasksFile.appendText(
            listOf(
                task.id,
                task.request.replace("\t", "\\t").replace("\n", "\\n"),
                task.status,
                escapedSummary,
                if (task.verification.passed) "pass" else "fail",
                task.verification.issues.size.toString(),
                escapedEvents,
                task.createdAt,
                task.completedAt.orEmpty()
            ).joinToString("\t") + "\n"
        )
    }

    fun formatTaskResult(task: ChatTaskRecord): String = buildString {
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
        return deduped.joinToString("\n").take(4_000)
    }
}
