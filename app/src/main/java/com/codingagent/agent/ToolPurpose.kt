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
        val explicitPurpose = args?.optString("purpose").orEmpty().trim()
        if (explicitPurpose.isNotBlank() && !isTechnical(explicitPurpose)) return explicitPurpose
        return when (name) {
            "list_files" ->
                "Checking the project files${if (path.isNotBlank()) " under $path" else ""}."
            "read_file" ->
                "Reading ${path.ifBlank { "the requested file" }} before I review or change it."
            "search_project" ->
                "Searching the project for ${query.ifBlank { "the requested code or text" }}."
            "search_knowledge" ->
                "Checking the local reference material."
            "research_web" ->
                "Checking current documentation for ${query.ifBlank { "the technical question" }}."
            "replace_text" ->
                "Preparing an edit to ${path.ifBlank { "the requested file" }} for your approval."
            "create_file" ->
                "Preparing ${path.ifBlank { "the requested file" }} for your approval."
            "run_command" ->
                "Running the requested project check."
            "verify" ->
                "Checking the current changes."
            "approve_change" ->
                "Recording your approval."
            "reject_change" ->
                "Rejecting the pending change."
            else -> "Working on the request."
        }
    }

    private fun isTechnical(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(
            "rhino", "console.log", "syntax", "parser", "lexer", "tokenizer",
            "argv", "stdin", "bytecode", "ast", "command line", "command-line"
        ).any { it in lower }
    }
}

/** A new program the phone can write and run without looking through the project first. */
object SelfContainedProgram {
    fun matches(request: String): Boolean {
        val text = request.lowercase()
        if ("current request:" in text || "owner:" in text || text.contains("open job")) return false
        val writing = listOf("write", "build", "create").any { it in text }
        val program = listOf("javascript", "compiler", "program", "script").any { it in text }
        val aboutExisting = listOf("this project", "the project", "this file", "the file ").any { it in text }
        return writing && program && !aboutExisting
    }
}
