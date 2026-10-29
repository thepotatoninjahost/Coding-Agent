package com.codingagent.agent

import com.codingagent.model.ModelMessage

/**
 * ONE JOB: Shorten older turns without splitting a tool call from its result.
 */
object TranscriptCompactor {
    fun groupsOf(messages: List<ModelMessage>): List<List<ModelMessage>> {
        val groups = mutableListOf<List<ModelMessage>>()
        var index = 0
        while (index < messages.size) {
            val current = messages[index]
            val next = messages.getOrNull(index + 1)
            if (current.role == "assistant" && current.toolName != null && next != null && next.role == "tool" &&
                (current.toolCallId.isNullOrBlank() || next.toolCallId.isNullOrBlank() || current.toolCallId == next.toolCallId)
            ) {
                groups += listOf(current, next)
                index += 2
            } else {
                groups += listOf(current)
                index += 1
            }
        }
        return groups
    }

    fun compact(transcript: List<ModelMessage>, keepRecentGroups: Int = 2): List<ModelMessage> {
        val groups = groupsOf(transcript)
        val keep = keepRecentGroups.coerceAtLeast(1)
        if (groups.size <= keep) return transcript
        val note = ModelMessage("user", summarize(groups.dropLast(keep)))
        return listOf(note) + groups.takeLast(keep).flatten()
    }

    private fun summarize(groups: List<List<ModelMessage>>): String {
        val actions = linkedSetOf<String>()
        val files = linkedSetOf<String>()
        groups.flatten().forEach { message ->
            message.toolName?.let { actions += it }
            Regex("""[A-Za-z0-9_./-]+\.[A-Za-z0-9]{1,8}""")
                .findAll(message.content)
                .take(6)
                .forEach { files += it.value }
        }
        val actionText = actions.take(8).joinToString(", ").ifBlank { "earlier steps" }
        val fileText = files.take(8).joinToString(", ").ifBlank { "none kept" }
        return "Earlier in this task: $actionText. Files mentioned: $fileText. " +
            "Use the latest tool result. Do not repeat a step that already finished."
    }
}
