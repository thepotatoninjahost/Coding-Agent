package com.codingagent.agent

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.research.DeepResearchProvider
import com.codingagent.research.ResearchBriefBuilder
import com.codingagent.research.ResearchMode
import com.codingagent.research.ResearchModeDetector
import com.codingagent.research.StructuredSources
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.DeviceCodeRunner
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProgramRequest
import com.codingagent.workspace.RunReport
import com.codingagent.workspace.SearchReplace
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
    private val commandTimeoutSeconds: Long = 180,
    private val sourceConnect: (String) -> java.net.HttpURLConnection = { java.net.URL(it).openConnection() as java.net.HttpURLConnection },
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

    private var lastRunReport: RunReport? = null

    fun lastRun(): RunReport? = lastRunReport

    fun applySearchBlocks(request: String, blocks: List<SearchReplace.Block>): String {
        val notes = blocks.map { block ->
            val base = stagedOrSaved(block.path)
            when (val applied = SearchReplace.apply(base, block.search, block.replace)) {
                is SearchReplace.ApplyResult.Failed -> "ERROR: ${block.path}: ${applied.reason}"
                is SearchReplace.ApplyResult.Applied -> stageContent(request, block.path, applied.content)
            }
        }
        return notes.joinToString("\n")
    }

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
                "edit" -> editText(arguments)
                "append_chunk" -> appendChunk(arguments)
                "review_examples" -> reviewExamples(arguments)
                "run_program" -> runProgram(arguments)
                "submit" -> "SUBMIT status=${arguments.optString("status", "done")}"
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
        val hint = if (part.hasMore) {
            "More of this file remains. Call read_file again with the same path and startCharacter=${part.nextCharacter}. Do not call continue_result. Do not start again at 0."
        } else {
            "The whole file is here. Do not read it again."
        }
        return "FILE_PATH=${part.path}\nSTART_CHARACTER=${part.startCharacter}\nNEXT_START_CHARACTER=${part.nextCharacter}\nEND_OF_FILE=${!part.hasMore}\n$hint\nCONTENT_START\n${part.content}\nCONTENT_END"
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
        StructuredSources.answer(query, sourceConnect)?.let { return it }
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
        val oldText = arguments.getString("oldText")
        val newText = arguments.getString("newText")
        if (oldText.length > ARG_LIMIT || newText.length > ARG_LIMIT) {
            return "ERROR: That edit is too big for one call. Split it into smaller pieces of about $ARG_LIMIT characters."
        }
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
                proposalReady(result.proposal.id, path, result.proposal.changeSet.changes.size)
            is MutationProposeResult.Rejected ->
                "ERROR: replace_text proposal rejected — ${result.reason}"
        }
    }

    private fun createFile(arguments: JSONObject): String {
        val path = arguments.getString("path")
        val reason = arguments.optString("reason").trim()
        val content = arguments.getString("content")
        if (content.lines().size > 150) {
            return "ERROR: That file is too long for one write. Create the first part, then use append_chunk for the rest."
        }
        StagedSourceCheck.problem(path, content)?.let { return "ERROR: $it" }
        val pending = mutations.pending().firstOrNull()
        if (pending != null) {
            val sameFile = pending.changeSet.changes.any { it.path.trim().trimStart('/') == path.trim().trimStart('/') }
            if (!sameFile) {
                return "ERROR: Another change is waiting for a yes or a no. Finish that one before starting a different file."
            }
            mutations.reject(pending.id)
        }
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
                proposalReady(result.proposal.id, path, result.proposal.changeSet.changes.size)
            is MutationProposeResult.Rejected ->
                "ERROR: create_file proposal rejected — ${result.reason}"
        }
    }

    private fun reviewExamples(arguments: JSONObject): String {
        val path = arguments.optString("path").trim()
        val examples = arguments.optString("examples")
        val cases = examples.lines().map { it.trim() }.filter { it.contains("=>") }.map { line ->
            val rawInput = line.substringBefore("=>").trim()
            val input = rawInput.removePrefix("STDIN:").removePrefix("ARGS:").trim().replace("||", "\n")
            val expected = line.substringAfter("=>").trim().replace("||", "\n")
            val asArgs = rawInput.startsWith("ARGS:")
            Triple(input, expected, asArgs)
        }
        if (cases.size < 2) {
            return "ERROR: Give at least two examples, written as INPUT => EXPECTED. Use || for a newline. A bad input can expect the word error."
        }
        val content = stagedOrSaved(path)
        if (content != null && (path.endsWith(".js") || path.endsWith(".py") || content.contains("console.log"))) {
            val report = DeviceCodeRunner.cases(
                code = content,
                examples = cases.map { it.first to it.second },
                argsFor = { input ->
                    val match = cases.firstOrNull { it.first == input }
                    if (match?.third == true) input.lines() else emptyList()
                },
                stdinFor = { input ->
                    val match = cases.firstOrNull { it.first == input }
                    if (match?.third == true) "" else input
                }
            )
            lastRunReport = report
            val matched = report.passed
            return "CHECKED examples=${cases.size} ran=true matched=$matched\n" +
                ChangeDiff.runSentence(report) + "\n" + report.stdout
        }
        lastRunReport = RunReport("none", 0, "", "This phone did not run this file.", false)
        return "CHECKED examples=${cases.size} ran=false matched=false\n" +
            "I could not run this file here. A review of ${cases.size} examples was recorded. Do not say it prints the correct result."
    }

    private fun editText(arguments: JSONObject): String {
        val path = arguments.getString("path")
        val oldText = arguments.optString("old_text", arguments.optString("oldText"))
        val newText = arguments.optString("new_text", arguments.optString("newText"))
        if (oldText.length > ARG_LIMIT || newText.length > ARG_LIMIT) {
            return "ERROR: That edit is too big for one call. Use several smaller edits."
        }
        val base = stagedOrSaved(path)
        return when (val applied = SearchReplace.apply(base, oldText, newText)) {
            is SearchReplace.ApplyResult.Failed -> "ERROR: ${applied.reason}"
            is SearchReplace.ApplyResult.Applied -> stageContent(requestContext, path, applied.content)
        }
    }

    private fun appendChunk(arguments: JSONObject): String {
        val path = arguments.getString("path")
        val text = arguments.optString("text", arguments.optString("content"))
        if (text.length > ARG_LIMIT) return "ERROR: That piece is too big. Send a shorter piece."
        val base = stagedOrSaved(path).orEmpty()
        return when (val applied = SearchReplace.apply(base, "", text)) {
            is SearchReplace.ApplyResult.Failed -> "ERROR: ${applied.reason}"
            is SearchReplace.ApplyResult.Applied -> stageContent(requestContext, path, applied.content)
        }
    }

    private fun runProgram(arguments: JSONObject): String {
        val path = arguments.optString("path", arguments.optString("file")).trim()
        val inline = arguments.optString("code")
        val code = inline.ifBlank { path.let { stagedOrSaved(it) }.orEmpty() }
        if (code.isBlank()) return "ERROR: There is no program to run."
        val stdin = arguments.optString("stdin")
        val args = arguments.optJSONArray("args")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        val expected = arguments.optString("expected")
        val report = if (expected.isNotBlank()) {
            DeviceCodeRunner.cases(code, listOf(stdin.ifBlank { args.joinToString("\n") } to expected))
        } else {
            DeviceCodeRunner.run(ProgramRequest(arguments.optString("language", "javascript"), code, args, stdin))
        }
        lastRunReport = report
        val body = if (report.stdout.isBlank() && report.exitCode == 0 && !report.timedOut) {
            "The program ran and produced no output."
        } else {
            report.stdout
        }
        return "exit=${report.exitCode} timed_out=${report.timedOut} runner=${report.runner}\n$body\n${report.stderr}\n${ChangeDiff.runSentence(report)}"
    }

    private fun stageContent(request: String, path: String, content: String): String {
        StagedSourceCheck.problem(path, content)?.let { return "ERROR: $it" }
        val pending = mutations.pending().firstOrNull()
        if (pending != null) {
            val sameFile = pending.changeSet.changes.any { it.path.trim().trimStart('/') == path.trim().trimStart('/') }
            if (!sameFile) {
                return "ERROR: Another change is waiting for a yes or a no. Finish that one before starting a different file."
            }
            mutations.reject(pending.id)
        }
        val onDisk = runCatching { files.read(path).content }.getOrNull()
        val operation = if (onDisk == null) {
            TaskOperation(OperationKind.CREATE_FILE, path, text = content)
        } else {
            TaskOperation(OperationKind.REPLACE, path, onDisk, content)
        }
        return when (val result = mutations.propose(request.ifBlank { "edit $path" }, listOf(operation), request.ifBlank { "edit $path" })) {
            is MutationProposeResult.Proposed ->
                proposalReady(result.proposal.id, path, result.proposal.changeSet.changes.size)
            is MutationProposeResult.Rejected -> "ERROR: edit proposal rejected — ${result.reason}"
        }
    }

    private fun stagedOrSaved(path: String): String? {
        val staged = mutations.pending().firstOrNull()?.changeSet?.changes?.firstOrNull { change ->
            change.path.trim().trimStart('/') == path.trim().trimStart('/')
        }?.after
        if (!staged.isNullOrBlank()) return staged
        return runCatching { files.read(path).content }.getOrNull()
    }

    private fun runPython(content: String, cases: List<Pair<String, String>>): List<Boolean>? {
        val python = listOf("python3", "python").firstOrNull { name ->
            runCatching {
                val probe = ProcessBuilder(name, "--version").redirectErrorStream(true).start()
                probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0
            }.getOrDefault(false)
        } ?: return null
        val dir = Files.createTempDirectory("code-check").toFile()
        return try {
            val file = File(dir, "check.py")
            file.writeText(content)
            cases.map { (input, expected) ->
                val process = ProcessBuilder(python, file.absolutePath).directory(dir).redirectErrorStream(true).start()
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(input)
                    if (!input.endsWith("\n")) writer.write("\n")
                }
                val finished = process.waitFor(3, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    false
                } else {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    process.exitValue() == 0 && output == expected.trim()
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runCommand(arguments: JSONObject): String {
        val command = arguments.getString("command")
        AgentCommandPolicy.rejectionReason(command, workspace.projectRoot())?.let { reason ->
            return "ERROR: run_command blocked — $reason"
        }
        val entry = terminal.execute(command, timeoutSeconds = commandTimeoutSeconds)
        return "exit=${entry.exitCode} timeout=${entry.timedOut}\n${entry.stdout}\n${entry.stderr}"
            
    }

    private fun ownerOnlyMutationControl(name: String): String =
        "ERROR: $name is owner-only. Use Review or chat for owner-controlled proposal decisions."

    private fun proposalReady(id: String, path: String, changes: Int): String =
        "PROPOSAL_READY id=$id path=$path changes=$changes approval_required=2. " +
            "The file is staged. Next, call review_examples with at least two examples, or run_program. " +
            "Include a negative number, a decimal, a bad input that should print the word error, and a divide by zero. " +
            "Do not describe the file. I have not run it. Do not say it works."

    private companion object {
        const val KNOWLEDGE_RESULT_CAP = 30
        const val ARG_LIMIT = 6_000
    }
}
