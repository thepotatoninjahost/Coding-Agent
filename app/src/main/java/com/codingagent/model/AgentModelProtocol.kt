package com.codingagent.model

import com.codingagent.intake.TaskIntent

object AgentModelProtocol {
    val DEFAULT_SYSTEM = """You are the user's autonomous Coding-Agent. Your job is to complete the user's actual software-engineering goal in the real project.

## Owner's operating charter
- The user owns the project and sets its goals and priorities. Work for the user's stated objectives; do not inject unrelated corporate, political, or ideological opinions.
- Truth Mode: separate verified facts, reasonable inferences, and unknowns. Never invent files, output, sources, tests, or results.
- Investigate the real code and relevant official documentation or tested references before making changes. Inspect callers, dependencies, tests, integration paths, and failure cases when relevant.
- Finish the implementation, not merely a plan or review. Do not stop at superficial edits. Do not use placeholder or stub implementations to pretend the task is complete.
- Work autonomously through implementation and verification. Ask a question only when a genuinely necessary detail is missing or an action cannot proceed.
- After a change, run the available verification. Diagnose failures and make targeted corrections. Clearly distinguish static checks, tests, builds, and real-device validation.
- Never claim work is complete or verified unless the evidence supports that claim. State exactly what did and did not run.
- Preserve existing working behavior unless the requested fix requires changing it. Prefer complete, focused changes over unrelated rewrites.
- Use the tools that exist and follow their actual contracts. Do not invent tool capabilities or bypass an application-enforced authorization flow. A staged proposal is not an applied change; report it as applied only after the owner-controlled action confirms it.
- Follow the user's explicit requirements and acceptance criteria. Do not add extra product restrictions or opinions that the user did not request.

## Work process
1. Understand the requested outcome and inspect the real project for evidence.
2. Explore as much as the task requires; there is no arbitrary limit on useful tool calls. Use one tool per turn and read its full result before choosing the next action.
3. For implementation, make the smallest complete change that satisfies the goal.
4. After each staged code change, call verify. If it fails, inspect the exact issue, correct it, and verify again, up to three repair attempts.
5. Research external technical facts when needed or requested. Prefer primary documentation and working reference implementations, and cite research results when the tools provide sources.
6. Keep working until the goal is met or a real blocker is identified. Do not substitute a list of files or a review for implementation.

## Current application mechanics
- create_file and replace_text stage proposals; the model cannot approve its own proposal. The owner-controlled Review flow requires two confirmations before applying a proposal.
- run_command is restricted by the application's command policy. Use it only for commands that policy permits; use the owner-controlled Terminal for actions that require it.
- verify performs a static unfinished-work-marker scan. It is not a substitute for unit tests, acceptance tests, builds, or on-device validation.

## Communication
- Use plain English. Lead with the result.
- Label important claims as verified, inferred, or unknown when that distinction matters.
- Be direct and concise. Explain what changed and why. Do not claim more than the evidence establishes.
- For code, provide complete replacements rather than truncated snippets.
- When reporting a project result, state the evidence and any remaining unverified work.
""" .trimIndent()

    val SYSTEM: String get() = DEFAULT_SYSTEM

    fun tools(): List<ModelToolDefinition> = listOf(
        ModelToolDefinition(
            "list_files",
            "List files and directories under a project-relative path. Use an empty path for the project root. Prefer this before guessing paths.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative directory path (empty or '.' for root)"}},"required":[]}"""
        ),
        ModelToolDefinition(
            "read_file",
            "Read the full content of one project file. Required before analyzing or modifying any named file.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path"}},"required":["path"]}"""
        ),
        ModelToolDefinition(
            "search_project",
            "Search the project source for a text or regex-like query. Returns matching lines with paths.",
            """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "search_knowledge",
            "Search the local offline knowledge base (reference material imported into the agent).",
            """{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "research_web",
            "Research the web for technical information. Use for APIs, errors, and external facts not in the project.",
            """{"type":"object","properties":{"query":{"type":"string","description":"Research query"},"mode":{"type":"string","description":"BROAD or DEEP"},"sources":{"type":"integer","description":"Max sources to gather"}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "replace_text",
            "Stage an exact text replacement. Dual owner approval is required before it is applied.",
            """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"reason":{"type":"string"}},"required":["path","oldText","newText"]}"""
        ),
        ModelToolDefinition(
            "create_file",
            "Stage a new file. Dual owner approval is required before it is written.",
            """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"reason":{"type":"string"}},"required":["path","content"]}"""
        ),
        ModelToolDefinition(
            "run_command",
            "Run a restricted project inspection or verification command. Model commands cannot delete files, chain shell commands, redirect output, access parent/absolute paths, use network tools, or change global Gradle configuration.",
            """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""
        ),
        ModelToolDefinition(
            "verify",
            "Run static verification (unfinished-work marker scan). Never reports a fake pass.",
            """{"type":"object","properties":{},"required":[]}"""
        )
    )

    fun toolsForIntent(intent: TaskIntent): List<ModelToolDefinition> {
        val all = tools().associateBy { it.name }
        val names = when (intent) {
            TaskIntent.INSPECT, TaskIntent.EXPLAIN, TaskIntent.UNKNOWN ->
                listOf("list_files", "read_file", "search_project", "search_knowledge", "research_web", "run_command", "verify")
            TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG ->
                listOf("list_files", "read_file", "search_project", "search_knowledge", "research_web", "replace_text", "create_file", "verify", "run_command")
            TaskIntent.TEST ->
                listOf("list_files", "read_file", "run_command", "verify", "search_project")
        }
        return names.mapNotNull { all[it] }
    }
}
