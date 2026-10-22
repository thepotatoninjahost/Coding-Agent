package com.codingagent.agent

import org.json.JSONObject
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.research.DeepResearchProvider
import com.codingagent.research.ResearchBriefBuilder
import com.codingagent.research.ResearchMode
import com.codingagent.research.ResearchModeDetector
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.TerminalSession

/**
 * ONE JOB: Execute one named agent tool and return its text result.
 */
class AgentToolDispatch(
    private val files: ProjectFileService,
    private val workspace: ProjectWorkspace,
    private val knowledge: AgentKnowledge,
    private val research: DeepResearchProvider,
    private val mutations: MutationCoordinator,
    private val terminal: TerminalSession,
    private val maxOutputCharacters: Int,
    private val onResearchProgress: (String) -> Unit
) {
    @Volatile
    private var requestContext: String = ""
    @Volatile
    private var ownerContextProvider: () -> AgentOwnerContext = { AgentOwnerContext() }
    private val resultPager = ToolResultPager(maxOutputCharacters)

    fun setRequestContext(request: String) {
        requestContext = request.trim()
        resultPager.clear()
    }

    fun setOwnerContextProvider(provider: () -> AgentOwnerContext) {
        ownerContextProvider = provider
    }

    fun presentEvidence(label: String, completeEvidence: String): String =
        resultPager.present(label, completeEvidence)

    fun execute(name: String, rawArguments: String): String {
        return try {
            val arguments = JSONObject(rawArguments)
            if (name == "continue_result") {
                return resultPager.continueResult(arguments.getString("resultId"), arguments.getInt("startCharacter"))
            }
            val result = when (name) {
                "list_files" -> listFiles(arguments)
                "read_file" -> readFileChunk(arguments)
                "search_project" -> workspace.search(arguments.getString("query")).joinToString("\n") { "${it.path}:${it.line}: ${it.text}" }
                "search_knowledge" -> {
                    val hits = knowledge.search(arguments.getString("query"), KNOWLEDGE_RESULT_CAP + 1)
                    val shown = hits.take(KNOWLEDGE_RESULT_CAP)
                    val body = shown.joinToString("\n") { "${it.document}/${it.section}: ${it.excerpt}" }
                    if (hits.size > KNOWLEDGE_RESULT_CAP) {
                        body + "\nShowing $KNOWLEDGE_RESULT_CAP matches. Ask a tighter question for the rest."
                    } else {
                        body
                    }
                }
                "research_web" -> researchWeb(arguments)
                "read_chat_history" -> readChatHistory(arguments)
                "search_chat_history" -> searchChatHistory(arguments)
                "read_user_memory" -> readUserMemory(arguments)
                "search_user_memory" -> searchUserMemory(arguments)
                "replace_text" -> replaceText(arguments)
                "create_file" -> createFile(arguments)
                "run_command" -> runCommand(arguments)
                "verify" -> {
                    val report = workspace.verify()
                    "passed=${report.passed}\n" + report.issues.joinToString("\n") { "${it.path}:${it.line}: ${it.message}" }
                }
                "approve_change" -> ownerOnlyMutationControl("approve_change")
                "reject_change" -> ownerOnlyMutationControl("reject_change")
                else -> "ERROR: Unknown tool '$name'"
            }
            if (name == "read_file") result else resultPager.present(name, result)
        } catch (error: Exception) {
            "ERROR: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun readChatHistory(arguments: JSONObject): String {
        val history = ownerContextProvider().history
        val start = arguments.optInt("offset", 0).coerceAtLeast(0)
        val limit = arguments.optInt("limit", 10).coerceIn(1, 100)
        val end = minOf(start + limit, history.size)
        return buildString {
            append("CHAT_HISTORY_TOTAL=").append(history.size).append('\n')
            append("MESSAGES_START=").append(start).append('\n')
            append("NEXT_OFFSET=").append(end).append('\n')
            append("MORE_MESSAGES=").append(end < history.size).append('\n')
            for (index in start until end) {
                val message = history[index]
                append("\nMESSAGE_INDEX=").append(index).append('\n')
                append("ROLE=").append(message.role).append('\n')
                append("CREATED_AT=").append(message.createdAt).append('\n')
                append("TASK_ID=").append(message.taskId.orEmpty()).append('\n')
                append("CONTENT_START\n").append(message.content).append("\nCONTENT_END\n")
            }
        }
    }

    private fun searchChatHistory(arguments: JSONObject): String {
        val query = arguments.getString("query").trim()
        require(query.isNotEmpty()) { "A conversation-history search phrase is required" }
        val matches = ownerContextProvider().history.withIndex().filter { (_, message) ->
            message.content.contains(query, ignoreCase = true)
        }
        return buildString {
            append("CHAT_HISTORY_MATCHES=").append(matches.size).append('\n')
            matches.forEach { (index, message) ->
                append("\nMESSAGE_INDEX=").append(index).append(" ROLE=").append(message.role)
                    .append(" CREATED_AT=").append(message.createdAt).append('\n')
                append(message.content).append('\n')
            }
        }
    }

    private fun readUserMemory(arguments: JSONObject): String {
        val memories = ownerContextProvider().memories
        val start = arguments.optInt("offset", 0).coerceAtLeast(0)
        val limit = arguments.optInt("limit", 20).coerceIn(1, 100)
        val end = minOf(start + limit, memories.size)
        return buildString {
            append("SAVED_MEMORIES_TOTAL=").append(memories.size).append('\n')
            append("MEMORIES_START=").append(start).append('\n')
            append("NEXT_OFFSET=").append(end).append('\n')
            append("MORE_MEMORIES=").append(end < memories.size).append('\n')
            for (index in start until end) {
                val memory = memories[index]
                append("\nMEMORY_INDEX=").append(index).append('\n')
                append("KIND=").append(memory.kind).append('\n')
                append("IMPORTANCE=").append(memory.importance).append('\n')
                append("CONTENT_START\n").append(memory.text).append("\nCONTENT_END\n")
            }
        }
    }

    private fun searchUserMemory(arguments: JSONObject): String {
        val query = arguments.getString("query").trim()
        require(query.isNotEmpty()) { "A saved-memory search phrase is required" }
        val matches = ownerContextProvider().memories.withIndex().filter { (_, memory) ->
            memory.text.contains(query, ignoreCase = true)
        }
        return buildString {
            append("SAVED_MEMORY_MATCHES=").append(matches.size).append('\n')
            matches.forEach { (index, memory) ->
                append("\nMEMORY_INDEX=").append(index).append(" KIND=").append(memory.kind)
                    .append(" IMPORTANCE=").append(memory.importance).append('\n')
                append(memory.text).append('\n')
            }
        }
    }

    private fun readFileChunk(arguments: JSONObject): String {
        val path = arguments.getString("path")
        val start = arguments.optInt("startCharacter", 0)
        val size = arguments.optInt("maxCharacters", maxOutputCharacters).coerceIn(1, maxOutputCharacters.coerceAtLeast(1))
        val part = files.readChunk(path, start, size)
        return "FILE_PATH=${part.path}\nSTART_CHARACTER=${part.startCharacter}\nNEXT_START_CHARACTER=${part.nextCharacter}\nEND_OF_FILE=${!part.hasMore}\nCONTENT_START\n${part.content}\nCONTENT_END"
    }

    private fun listFiles(arguments: JSONObject): String {
        val rawPath = arguments.optString("path").trim()
        val pathArg = when {
            rawPath.isEmpty() || rawPath == "." || rawPath == "./" || rawPath == "/" -> ""
            else -> rawPath
        }
        val listed = if (pathArg.isEmpty()) {
            files.listSourceFilePaths().ifEmpty { files.listSourceFileNames() }
        } else {
            files.list(pathArg)
        }
        val result = if (listed.isEmpty()) "(no files)" else listed.joinToString("\n")
        return result
    }

    private fun researchWeb(arguments: JSONObject): String {
        val query = arguments.getString("query")
        val mode = runCatching {
            ResearchMode.valueOf(arguments.optString("mode", "BROAD").uppercase())
        }.getOrDefault(ResearchModeDetector.detect(query))
        val sources = arguments.optInt("sources", 8).coerceIn(1, 12)
        var progressLine = "not started"
        val sessionResult = runCatching {
            research.deepResearch(query, sources, mode) { progress ->
                progressLine =
                    "${progress.stage}: ${progress.completed}/${progress.total}; " +
                        "learned ${progress.successful}, failed ${progress.failed}"
                onResearchProgress(progressLine)
            }
        }
        val session = sessionResult.getOrNull()
        return when {
            sessionResult.isFailure -> {
                val err = sessionResult.exceptionOrNull()
                val why = com.codingagent.research.ResearchErrors.plain(
                    err?.message ?: err?.javaClass?.simpleName ?: "unknown"
                )
                "I could not look that up. $why I will keep going with the project. I will not invent pages I did not read."
            }
            session == null || session.sources.isEmpty() -> {
                val why = com.codingagent.research.ResearchErrors.plain(
                    session?.errors.orEmpty()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(4)
                        .joinToString(" | ")
                        .ifBlank { "No error was recorded." }
                )
                "I looked this up and did not get usable pages. $why Keep going with the project. Do not invent documentation."
            }
            else -> {
                val brief = ResearchBriefBuilder.build(session)
                val header =
                    "Learned ${brief.sourceCount} distinct full sources across ${brief.laneCount} lanes, " +
                        "${brief.wordCount} words, ${brief.codeExampleCount} code examples.\n" +
                        "Progress: $progressLine\n"
                (header + brief.evidence)
            }
        }
    }

    private fun replaceText(arguments: JSONObject): String {
        val path = arguments.getString("path")
        val reason = arguments.optString("reason").trim()
        if (!ChangeDiff.hasCompletePlainEnglishExplanation(reason)) {
            return "ERROR: I cannot stage this change yet. Explain it in plain English under all six headings: What I want to do, Why, What will change, Possible effects, Risks, and What your approval means."
        }
        val oldText = arguments.getString("oldText")
        val newText = arguments.getString("newText")
        val existing = runCatching { files.read(path).content }.getOrNull()
        val proposed = if (existing != null && oldText.isNotEmpty() && existing.contains(oldText)) {
            existing.replace(oldText, newText)
        } else {
            newText
        }
        StagedSourceCheck.problem(path, proposed)?.let { return "ERROR: $it" }
        return when (val result = mutations.propose(
            request = requestContext.ifBlank { "replace_text $path" },
            operations = listOf(
                TaskOperation(
                    OperationKind.REPLACE,
                    path,
                    oldText,
                    newText
                )
            ),
            reason = reason
        )) {
            is MutationProposeResult.Proposed ->
                "PROPOSAL_READY id=${result.proposal.id} path=$path " +
                    "changes=${result.proposal.changeSet.changes.size} approval_required=2 " +
                    "Confirm twice in the authenticated Review flow to APPLY this change to disk. " +
                    "I checked that the file is not cut off. I have not run it. Do not say it works."
            is MutationProposeResult.Rejected ->
                "ERROR: replace_text proposal rejected — ${result.reason}"
        }
    }

    private fun createFile(arguments: JSONObject): String {
        val path = arguments.getString("path")
        val reason = arguments.optString("reason").trim()
        if (!ChangeDiff.hasCompletePlainEnglishExplanation(reason)) {
            return "ERROR: I cannot stage this change yet. Explain it in plain English under all six headings: What I want to do, Why, What will change, Possible effects, Risks, and What your approval means."
        }
        val content = arguments.getString("content")
        StagedSourceCheck.problem(path, content)?.let { return "ERROR: $it" }
        return when (val result = mutations.propose(
            request = requestContext.ifBlank { "create_file $path" },
            operations = listOf(
                TaskOperation(
                    OperationKind.CREATE_FILE,
                    path,
                    text = content
                )
            ),
            reason = reason
        )) {
            is MutationProposeResult.Proposed ->
                "PROPOSAL_READY id=${result.proposal.id} path=$path " +
                    "changes=${result.proposal.changeSet.changes.size} approval_required=2 " +
                    "Confirm twice in the authenticated Review flow to APPLY this file to disk. " +
                    "I checked that the file is not cut off. I have not run it. Do not say it works."
            is MutationProposeResult.Rejected ->
                "ERROR: create_file proposal rejected — ${result.reason}"
        }
    }

    private fun runCommand(arguments: JSONObject): String {
        val command = arguments.getString("command")
        AgentCommandPolicy.rejectionReason(command, workspace.projectRoot())?.let { reason ->
            return "ERROR: run_command blocked — $reason"
        }
        val entry = terminal.execute(command)
        return "exit=${entry.exitCode} timeout=${entry.timedOut}\n${entry.stdout}\n${entry.stderr}"
            
    }

    private fun ownerOnlyMutationControl(name: String): String =
        "ERROR: $name is owner-only. Use Review or chat for owner-controlled proposal decisions."

    private companion object {
        const val KNOWLEDGE_RESULT_CAP = 30
    }
}
