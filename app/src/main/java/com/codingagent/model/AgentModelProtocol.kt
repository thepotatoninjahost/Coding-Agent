package com.codingagent.model

import com.codingagent.intake.TaskIntent

object AgentModelProtocol {
    val DEFAULT_SYSTEM = """You are a Coding-Agent: an autonomous software-engineering system on the user's device.

You look at the real project, then you finish the request. You are not a chatbot that keeps stalling.

## Core loop
1. Understand the goal.
2. Use tools to get real evidence. Never invent paths, file contents, command output, or test results.
3. One tool per turn. Read the full result before the next step.
4. After two or three useful tool results, stop gathering. Write the answer or stage the change.
5. On failure: change approach. Do not repeat the same failing call.
6. After edits, call verify. Never report a fake pass.
7. Research only when the user asked or you truly need current docs.

## Hard rules
- Evidence first. If the user names a file, call read_file on it before analysis or a final answer.
- Exactly one tool per turn.
- Code changes (create_file, replace_text) only STAGE a proposal. The owner must approve twice. Never claim a change was applied until a tool returns APPLIED.
- Prefer small, precise, reversible steps. Prefer truth over guesses.
- Finish. Do not keep listing files. Do not burn the turn budget. When you have enough evidence, write or stage.
- Unfinished-work markers (TODO/FIXME/stubs) are policy flags, not compiler errors.
- When you use research_web or search_knowledge, cite what you found. Do not invent sources.

## Available tools
list_files, read_file, search_project, search_knowledge, research_web, replace_text, create_file, approve_change, reject_change, run_command, verify
""".trimIndent()

    val SYSTEM: String get() = DEFAULT_SYSTEM

    fun tools(): List<ModelToolDefinition> = listOf(
        ModelToolDefinition(
            "list_files",
            "List files and directories under a project-relative path. Use an empty path for the project root. Prefer this before guessing paths.",
            """{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Project-relative directory path (empty or '.' for root)\"}},\"required\":[]}"""
        ),
        ModelToolDefinition(
            "read_file",
            "Read the full content of one project file. Required before analyzing or modifying any named file.",
            """{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Project-relative file path\"}},\"required\":[\"path\"]}"""
        ),
        ModelToolDefinition(
            "search_project",
            "Search the project source for a text or regex-like query. Returns matching lines with paths.",
            """{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"}},\"required\":[\"query\"]}"""
        ),
        ModelToolDefinition(
            "search_knowledge",
            "Search the local offline knowledge base (reference material imported into the agent).",
            """{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Search query\"}},\"required\":[\"query\"]}"""
        ),
        ModelToolDefinition(
            "research_web",
            "Research the web for technical information. Use for APIs, errors, and external facts not in the project.",
            """{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"Research query\"},\"mode\":{\"type\":\"string\",\"description\":\"BROAD or DEEP\"},\"sources\":{\"type\":\"integer\",\"description\":\"Max sources to gather\"}},\"required\":[\"query\"]}"""
        ),
        ModelToolDefinition(
            "replace_text",
            "Stage an exact text replacement. Dual owner approval is required before it is applied.",
            """{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"oldText\":{\"type\":\"string\"},\"newText\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"path\",\"oldText\",\"newText\"]}"""
        ),
        ModelToolDefinition(
            "create_file",
            "Stage a new file. Dual owner approval is required before it is written.",
            """{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"reason\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}"""
        ),
        ModelToolDefinition(
            "approve_change",
            "Record one owner approval for a pending proposal (two approvals required).",
            """{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"ownerVerified\":{\"type\":\"boolean\"},\"ownerLabel\":{\"type\":\"string\"}},\"required\":[\"id\",\"ownerVerified\",\"ownerLabel\"]}"""
        ),
        ModelToolDefinition(
            "reject_change",
            "Reject a pending change proposal.",
            """{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}"""
        ),
        ModelToolDefinition(
            "run_command",
            "Run a shell command in the project root and return stdout/stderr/exit code.",
            """{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}"""
        ),
        ModelToolDefinition(
            "verify",
            "Run static verification (unfinished-work marker scan). Never reports a fake pass.",
            """{\"type\":\"object\",\"properties\":{},\"required\":[]}"""
        )
    )

    fun toolsForIntent(intent: TaskIntent): List<ModelToolDefinition> {
        val all = tools().associateBy { it.name }
        val names = when (intent) {
            TaskIntent.INSPECT, TaskIntent.EXPLAIN, TaskIntent.UNKNOWN ->
                listOf("list_files", "read_file", "search_project", "search_knowledge", "research_web")
            TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG ->
                listOf("list_files", "read_file", "search_project", "search_knowledge", "research_web", "replace_text", "create_file", "approve_change", "reject_change", "verify", "run_command")
            TaskIntent.TEST ->
                listOf("list_files", "read_file", "run_command", "verify", "search_project")
        }
        return names.mapNotNull { all[it] }
    }
}
