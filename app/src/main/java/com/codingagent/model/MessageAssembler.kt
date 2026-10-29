package com.codingagent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * ONE JOB: Build the message list the helper expects, and reject a broken order.
 */
object MessageAssembler {
    fun messages(request: ModelRequest): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", request.system))
        messages.put(JSONObject().put("role", "user").put("content", request.user))
        separateOwnerNotes(request.transcript).forEach { message ->
            val serialized = JSONObject().put("role", message.role)
            val resolvedToolCallId = message.toolCallId?.takeIf { it.isNotBlank() } ?: "call_unmatched"
            if (message.role == "assistant" && message.toolName != null) {
                val thought = message.content.trim()
                serialized.put("content", if (thought.isBlank()) JSONObject.NULL else thought)
                serialized.put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject()
                            .put("id", resolvedToolCallId)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", message.toolName).put("arguments", message.toolArguments ?: "{}")
                            )
                    )
                )
            } else {
                serialized.put("content", message.content)
                if (message.role == "tool") serialized.put("tool_call_id", resolvedToolCallId)
            }
            messages.put(serialized)
        }
        return messages
    }

    /**
     * Gemma rejects two owner messages in a row, and a tool result with no call.
     * Fold those notes together before the list is sent.
     */
    fun separateOwnerNotes(transcript: List<ModelMessage>): List<ModelMessage> {
        val out = mutableListOf<ModelMessage>()
        for (message in transcript) {
            val previous = out.lastOrNull()
            if (message.role == "user" && previous?.role == "user") {
                out[out.lastIndex] = previous.copy(content = previous.content + "\n" + message.content)
            } else if (message.role == "user" && previous == null) {
                out += ModelMessage("assistant", "Noted.")
                out += message
            } else if (message.role == "tool" && previous?.toolName == null) {
                continue
            } else {
                out += message
            }
        }
        return out
    }

    /** Empty when the list is safe to send. */
    fun problem(messages: JSONArray): String? {
        var sawUser = false
        var pendingToolId: String? = null
        var previousRole = ""
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: return "A message was missing."
            val role = message.optString("role")
            if (role == "user") {
                if (pendingToolId != null) return "A tool result is missing."
                if (previousRole == "user") return "Two owner messages are next to each other."
                sawUser = true
            }
            if (role == "assistant" && message.has("tool_calls")) {
                if (!sawUser) return "A tool call has to follow the owner's message."
                val call = message.optJSONArray("tool_calls")?.optJSONObject(0)
                pendingToolId = call?.optString("id").orEmpty().ifBlank { return "A tool call is missing its id." }
            } else if (role == "tool") {
                val id = message.optString("tool_call_id")
                if (pendingToolId == null || id != pendingToolId) return "The tool result does not match the tool call."
                pendingToolId = null
            } else if (pendingToolId != null) {
                return "A tool result is missing."
            }
            previousRole = role
        }
        if (pendingToolId != null) return "A tool result is missing."
        return null
    }
}
