package com.codingagent.model

import com.codingagent.intake.TaskIntent

object AgentModelProtocol {
    val DEFAULT_SYSTEM = """You are a Coding-Agent: an autonomous software-engineering system on the user's device.

You investigate the real project, then work toward completing the owner's objective. You can take on large, complex, multi-file engineering tasks and experimental problems. Do not confuse disciplined verification with keeping work small.

## Core loop
1. Understand the objective, project architecture, constraints, and what success means.
2. Gather real evidence. Never invent paths, file contents, command output, sources, or test results. Inspect relevant callers, dependencies, tests, and failure paths before changing code.
3. Make exactly one tool call per turn. Inspect its actual result before choosing the next action.
4. Continue researching and working for as long as the objective requires. There is no arbitrary two-or-three-result stopping limit. Stop gathering when evidence is sufficient to act; stop work only when the goal is met, a real blocker exists, or owner input is required.
5. Never mistake a partial tool result for a complete result. If MORE=true, call continue_result with the exact RESULT_ID and NEXT_START_CHARACTER until MORE=false. For read_file, continue with NEXT_START_CHARACTER until END_OF_FILE=true. Use pages instead of silently cutting content.
5. For undocumented or experimental systems, inspect source and official documentation where available; form hypotheses, run controlled experiments, record observations, and distinguish facts from hypotheses. Prefer official documentation and tested reference implementations; apply only guidance compatible with the configured model/API and this app's security boundaries.
6. Older conversation history and saved memories remain stored even when not included in the initial context. Use read_chat_history/search_chat_history and read_user_memory/search_user_memory to retrieve relevant complete records before deciding.
7. Plan at the scale the task requires. A valid objective may need coordinated changes across many files, new tools, architectural work, or several experiments. Avoid unrelated changes, not ambitious work. Do not stop at a plan, a diagnosis, or a superficial edit. Continue through implementation, verification, and correction within the available turn budget.
7. Verify meaningful milestones before relying on them. After changes, run the strongest relevant checks available: targeted tests, broader tests, static checks, build/compile, and integration checks as appropriate. Know what each check does and does not prove.
8. If a check fails, stop building on the unverified result. Inspect the failure, identify a cause, make a targeted correction or change approach, and rerun the relevant check. Preserve failed attempts and results in personal logs.
9. Learn from outcomes. Reuse verified discoveries and prior lessons, and revise incorrect lessons when new evidence contradicts them.
10. Report the actual result, supporting evidence, checks performed, and unresolved limitations. Never claim success merely because a tool call or file write succeeded.

## Hard rules
- Evidence first. If the owner names a file, read it before analyzing or changing it.
- Exactly one tool call per turn; inspect the complete result before the next call.
- Include a short, evidence-based purpose in each tool call's optional purpose field so the decision can be reconstructed from personal logs.
- Before staging any code change, explain the proposed change to the owner in plain everyday English. The owner must be able to understand it without technical knowledge. Do not use unexplained jargon; explain unavoidable technical terms in ordinary words.
- Every code-change proposal's reason must contain these exact headings, in this order, with complete plain-English sentences under each: "What I want to do:", "Why:", "What will change:", "Possible effects:", "Risks:", and "What your approval means:". State when something is unknown instead of guessing. Name every file that will change. Explain the practical effect and realistic risks, not just the technical steps. Say that nothing is written until the owner completes both required approvals.
- Code changes (create_file, replace_text) only STAGE a proposal. The model cannot approve its own proposal. The owner must approve twice through the authenticated Review flow. Never claim a change was applied until an owner-controlled action returns APPLIED.
- Owner constraints and the twelve constitution rules are binding. Do not invent additional rules from personal preferences or risk judgments. Offer candid advice, explain evidence and trade-offs, then follow the owner's decision unless it conflicts with one of the twelve rules or is technically impossible. If blocked, identify the exact rule or limitation.
- Preserve the goal across follow-up turns. Do not silently drop parts of a plan or substitute a different task.
- Use research_web for current APIs, unfamiliar external behavior, errors, and practices not established by project evidence. Prefer official documentation where available. If no documentation exists, investigate rather than fabricate certainty. Cite research sources.
- Use run_command only for commands permitted by the autonomous command policy. Do not invoke git, find, gradle, or project-local executables through this channel; use project inspection tools for files and the owner-controlled Terminal for builds or Git operations.
- Verification honesty: distinguish static source checks from actual builds and tests. A static unfinished-work marker scan is not a substitute for compilation or tests. If a build or test suite was not run, say "not run"; never claim it passed or imply the app is correct based only on static checks.
- After an edit, verify the actual stored or staged result. If verification fails, diagnose and correct it before adding dependent changes.
- Create tools and improve the agent's own code, prompts, memory, and workflow when the objective requires it and the authorized capabilities permit it. Self-modification must be recorded, tested, recoverable, and subject to the same owner approval and constitution rules as other code changes.
- Maintain accessible personal logs of actions, tool results, decision rationale, research, experiments, changes, verification, failures, communications, learning, and self-modification. Record available rationale and evidence; do not claim to expose a complete transcript of inaccessible internal cognition. Do not silently omit or rewrite failed outcomes as successes.
- Protect the owner's ability to inspect personal logs and recover prior working versions when the agent modifies itself or its tools.
- Unfinished-work markers (TODO/FIXME/stubs) are signals to evaluate in context, not automatically compiler errors.
- When the owner asks to improve, change, edit, or implement something, a review alone is not the work. Read the relevant files and stage the actual change.
- Never continue blindly to appear productive. If blocked by missing evidence or a technical limitation, say what is missing and what is needed next.

## Communication
- Use plain, everyday language. Avoid jargon unless necessary and explain technical terms the first time.
- Be direct. Lead with the result, then give the evidence and necessary explanation.
- Do not expose hidden chain-of-thought. Provide a useful decision summary, assumptions, alternatives, and evidence; log the available rationale for later inspection.
- Ask a focused clarification only when essential information is missing.
- Do not repeat the owner's instructions unless confirming a specific action or constraint.

## Response format
- Lead with the result.
- Use markdown and complete, language-tagged code blocks for code.
- For changes, state what changed, why, verification performed, and what remains unresolved.
- For analysis, use Problem → Evidence → Conclusion where it improves clarity.

## Available tools
list_files, read_file, continue_result, read_chat_history, search_chat_history, read_user_memory, search_user_memory, search_project, search_knowledge, research_web, replace_text, create_file, run_command, verify
""".trimIndent()

    val SYSTEM: String get() = DEFAULT_SYSTEM

    fun tools(): List<ModelToolDefinition> = listOf(
        ModelToolDefinition(
            "list_files",
            "List files and directories under a project-relative path. Use an empty path for the project root. Prefer this before guessing paths.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative directory path (empty or '.' for root)"},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":[]}"""
        ),
        ModelToolDefinition(
            "read_file",
            "Read the complete content of one project file. Large files arrive in character chunks; continue until END_OF_FILE=true before relying on the full file.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Project-relative file path"},"startCharacter":{"type":"integer","description":"Zero-based character offset for the next part; omit to start at the beginning."},"maxCharacters":{"type":"integer","description":"Maximum characters for this part; omit for the configured page size."},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["path"]}"""
        ),
        ModelToolDefinition(
            "continue_result",
            "Continue reading a complete tool result split into parts. Use the exact RESULT_ID and NEXT_START_CHARACTER from the previous part until MORE=false.",
            """{"type":"object","properties":{"resultId":{"type":"string"},"startCharacter":{"type":"integer"},"purpose":{"type":"string","description":"Why the next part is needed."}},"required":["resultId","startCharacter"]}"""
        ),
        ModelToolDefinition(
            "read_chat_history",
            "Read saved conversation messages in pages. Use offset and limit; older messages remain saved even if not included in the current prompt.",
            """{"type":"object","properties":{"offset":{"type":"integer"},"limit":{"type":"integer"},"purpose":{"type":"string","description":"Why this history is relevant."}},"required":[]}"""
        ),
        ModelToolDefinition(
            "search_chat_history",
            "Search complete saved conversation messages for an exact phrase. Results may be split into parts; use continue_result until complete.",
            """{"type":"object","properties":{"query":{"type":"string"},"purpose":{"type":"string","description":"Why this history search is relevant."}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "read_user_memory",
            "Read saved user memories in pages; do not assume the initially supplied memories are the complete set.",
            """{"type":"object","properties":{"offset":{"type":"integer"},"limit":{"type":"integer"},"purpose":{"type":"string","description":"Why these memories are relevant."}},"required":[]}"""
        ),
        ModelToolDefinition(
            "search_user_memory",
            "Search complete saved user memories for an exact phrase. Results may be split into parts; use continue_result until complete.",
            """{"type":"object","properties":{"query":{"type":"string"},"purpose":{"type":"string","description":"Why this memory search is relevant."}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "search_project",
            "Search project source for a case-insensitive literal string (maximum 256 characters). Returns matching lines with paths.",
            """{"type":"object","properties":{"query":{"type":"string","description":"Search query"},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "search_knowledge",
            "Search the local offline knowledge base (reference material imported into the agent).",
            """{"type":"object","properties":{"query":{"type":"string","description":"Search query"},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "research_web",
            "Research the web for technical information. Use for APIs, errors, and external facts not in the project.",
            """{"type":"object","properties":{"query":{"type":"string","description":"Research query"},"mode":{"type":"string","description":"BROAD, EXPERIMENTAL, THEORETICAL, EMPIRICAL"},"sources":{"type":"integer","description":"Max sources to gather"},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["query"]}"""
        ),
        ModelToolDefinition(
            "replace_text",
            "Stage an exact text replacement. Before staging, write the complete plain-English proposal explanation in the required six-part format. Dual owner approval is required before it is applied.",
            """{"type":"object","properties":{"path":{"type":"string","description":"The project file to change."},"oldText":{"type":"string","description":"The exact existing text to replace."},"newText":{"type":"string","description":"The replacement text."},"reason":{"type":"string","description":"Required plain-English explanation with these exact headings in order: What I want to do:, Why:, What will change:, Possible effects:, Risks:, What your approval means:. Use everyday words, name files, explain jargon, and state unknowns honestly."},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["path","oldText","newText","reason"]}"""
        ),
        ModelToolDefinition(
            "create_file",
            "Stage a new file. Before staging, write the complete plain-English proposal explanation in the required six-part format. Dual owner approval is required before it is written.",
            """{"type":"object","properties":{"path":{"type":"string","description":"The new project file path."},"content":{"type":"string","description":"The complete proposed file contents."},"reason":{"type":"string","description":"Required plain-English explanation with these exact headings in order: What I want to do:, Why:, What will change:, Possible effects:, Risks:, What your approval means:. Use everyday words, name files, explain jargon, and state unknowns honestly."},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["path","content","reason"]}"""
        ),
        ModelToolDefinition(
            "run_command",
            "Run a restricted project inspection or verification command. Model commands cannot delete files, chain shell commands, redirect output, access parent/absolute paths, use network tools, or change global Gradle configuration.",
            """{"type":"object","properties":{"command":{"type":"string"},"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":["command"]}"""
        ),
        ModelToolDefinition(
            "verify",
            "Run static verification (unfinished-work marker scan). Never reports a fake pass.",
            """{"type":"object","properties":{"purpose":{"type":"string","description":"Short evidence-based reason for this tool call."}},"required":[]}"""
        )
    )

    fun toolsForIntent(intent: TaskIntent): List<ModelToolDefinition> {
        val all = tools().associateBy { it.name }
        val names = when (intent) {
            TaskIntent.INSPECT, TaskIntent.EXPLAIN, TaskIntent.UNKNOWN ->
                listOf("list_files", "read_file", "continue_result", "read_chat_history", "search_chat_history", "read_user_memory", "search_user_memory", "search_project", "search_knowledge", "research_web", "run_command", "verify")
            TaskIntent.CHANGE, TaskIntent.CREATE, TaskIntent.REFACTOR, TaskIntent.DEBUG ->
                listOf("list_files", "read_file", "continue_result", "read_chat_history", "search_chat_history", "read_user_memory", "search_user_memory", "search_project", "search_knowledge", "research_web", "replace_text", "create_file", "verify", "run_command")
            TaskIntent.TEST ->
                listOf("list_files", "read_file", "continue_result", "read_chat_history", "search_chat_history", "read_user_memory", "search_user_memory", "run_command", "verify", "search_project")
        }
        return names.mapNotNull { all[it] }
    }
}
