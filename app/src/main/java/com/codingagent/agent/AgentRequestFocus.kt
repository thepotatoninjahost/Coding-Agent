package com.codingagent.agent

/**
 * ONE JOB: Return the current user line, stripping any conversation wrapper.
 */
object AgentRequestFocus {
    fun current(request: String): String {
        val marker = "Current request:"
        val idx = request.lastIndexOf(marker, ignoreCase = true)
        return if (idx >= 0) request.substring(idx + marker.length).trim().ifBlank { request } else request
    }
}
