package com.codingagent.agent

import org.json.JSONObject

/**
 * ONE JOB: One owner-facing purpose line for a tool call.
 */
object ToolPurpose {
    fun of(name: String, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrNull()
        val path = args?.optString("path").orEmpty().trim()
        val query = args?.optString("query").orEmpty().trim()
        val command = args?.optString("command").orEmpty().trim()
        val reason = args?.optString("reason").orEmpty().trim()
        return when (name) {
            "list_files" ->
                "Purpose: list project files${if (path.isNotBlank()) " under $path" else " at the repo root"} so later reads use real paths"
            "read_file" ->
                "Purpose: read ${path.ifBlank { "the named file" }} before reviewing or changing it"
            "search_project" ->
                "Purpose: search the project for ${query.ifBlank { "the requested symbol or text" }}"
            "search_knowledge" ->
                "Purpose: look up local reference material for ${query.ifBlank { "this request" }}"
            "research_web" ->
                "Purpose: fetch current docs for ${query.ifBlank { "an external API or error" }}"
            "replace_text" ->
                "Purpose: stage an exact edit in ${path.ifBlank { "the target file" }}" +
                    if (reason.isNotBlank()) " ($reason)" else " (dual approval still required)"
            "create_file" ->
                "Purpose: stage a new file at ${path.ifBlank { "the requested path" }} (dual approval still required)"
            "run_command" ->
                "Purpose: run in the project terminal: ${command.ifBlank { "(command)" }}"
            "verify" ->
                "Purpose: run static verification on the current tree"
            "approve_change" ->
                "Purpose: record one owner approval on a pending proposal"
            "reject_change" ->
                "Purpose: reject a pending proposal"
            else -> "Purpose: $name"
        }
    }
}
