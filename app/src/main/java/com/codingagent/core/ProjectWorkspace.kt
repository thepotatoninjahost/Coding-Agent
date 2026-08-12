package com.codingagent.core

import java.io.File
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TODO_MARKER = "TO" + "DO"
private const val FIXME_MARKER = "FIX" + "ME"
private const val STUB_MARKER = "IMP" + "LEMENT_ME"

sealed class RollbackResult {
    object Restored : RollbackResult()
    data class Rejected(val reason: String) : RollbackResult()
}

/**
 * ONE JOB: File state, transactional edits, checksums, verify, and rollback.
 */
class ProjectWorkspace(private val root: File) {
    private val indexer = ProjectIndexer()
    private val metadataDir = root.resolve(".coding-agent")
    private val transactionDir = metadataDir.resolve("transactions")
    private val lessonsFile = metadataDir.resolve("lessons.tsv")

    init {
        require(root.isDirectory) { "Project root is not a directory" }
        transactionDir.mkdirs()
    }

    fun projectRoot(): File = root
    fun summary(): ProjectSummary = indexer.summarize(root)
    fun search(query: String): List<SearchHit> = indexer.search(root, query)

    @Synchronized
    fun transaction(reason: String, block: Transaction.() -> Unit): ChangeSet {
        val transaction = Transaction(reason)
        try {
            transaction.block()
            return transaction.commit()
        } catch (error: Exception) {
            transaction.abort()
            throw error
        }
    }

    fun replace(path: String, oldText: String, newText: String, reason: String): ChangeSet = transaction(reason) { replace(path, oldText, newText) }
    fun create(path: String, text: String, reason: String): ChangeSet = transaction(reason) { create(path, text) }
    fun append(path: String, text: String, reason: String): ChangeSet = transaction(reason) { append(path, text) }
    fun remove(path: String, oldText: String, reason: String): ChangeSet = transaction(reason) { remove(path, oldText) }

    fun preview(operations: List<TaskOperation>, reason: String): ChangeSet {
        require(operations.isNotEmpty()) { "At least one operation is required" }
        val transaction = Transaction("Preview: $reason")
        return try {
            operations.forEach { operation ->
                when (operation.kind) {
                    OperationKind.REPLACE -> transaction.replace(requireNotNull(operation.path), requireNotNull(operation.oldText), requireNotNull(operation.newText))
                    OperationKind.APPEND -> transaction.append(requireNotNull(operation.path), requireNotNull(operation.text).trimEnd() + "\n")
                    OperationKind.REMOVE -> transaction.remove(requireNotNull(operation.path), requireNotNull(operation.oldText))
                    OperationKind.CREATE_FILE -> transaction.create(requireNotNull(operation.path), requireNotNull(operation.text))
                    OperationKind.NONE -> error("Cannot preview an empty operation")
                }
            }
            transaction.commitPreview()
        } catch (error: Exception) {
            transaction.abort()
            throw error
        }
    }

    @Synchronized
    fun applyApproved(changeSet: ChangeSet): ChangeSet {
        require(changeSet.changes.isNotEmpty()) { "Approved change set is empty" }
        changeSet.changes.forEach { record ->
            require(checksum(diskContent(record.path)) == record.beforeChecksum) { "Approved content changed before apply: ${record.path}" }
        }
        val written = mutableListOf<ChangeRecord>()
        try {
            changeSet.changes.forEach { record ->
                writeAtomically(requireSafePath(record.path), requireNotNull(record.after))
                written += record
            }
            persist(changeSet)
            return changeSet
        } catch (error: Exception) {
            written.asReversed().forEach { record ->
                val file = requireSafePath(record.path)
                if (record.before == null) file.delete() else writeAtomically(file, record.before)
            }
            throw error
        }
    }

    inner class Transaction(private val reason: String) {
        private val staged = linkedMapOf<String, StagedChange>()
        private var committed = false

        fun replace(path: String, oldText: String, newText: String) {
            require(oldText.isNotEmpty()) { "Replacement target cannot be empty" }
            val current = staged[path]?.after ?: diskContent(path)
            require(current != null) { "File does not exist: $path" }
            require(current.countOccurrences(oldText) == 1) { "Expected exactly one match in $path" }
            stage(path, ChangeOperation.REPLACE, current.replace(oldText, newText))
        }

        fun create(path: String, text: String) {
            requireSafePath(path)
            require(staged[path]?.after == null && diskContent(path) == null) { "File already exists: $path" }
            stage(path, ChangeOperation.CREATE, text, null)
        }

        fun append(path: String, text: String) {
            val current = staged[path]?.after ?: diskContent(path)
            require(current != null) { "File does not exist: $path" }
            stage(path, ChangeOperation.APPEND, current + text)
        }

        fun remove(path: String, oldText: String) {
            require(oldText.isNotEmpty()) { "Removal target cannot be empty" }
            val current = staged[path]?.after ?: diskContent(path)
            require(current != null) { "File does not exist: $path" }
            require(current.countOccurrences(oldText) == 1) { "Expected exactly one match in $path" }
            stage(path, ChangeOperation.REMOVE, current.replace(oldText, ""))
        }

        fun commit(): ChangeSet = commitInternal(true)
        fun commitPreview(): ChangeSet = commitInternal(false)
        fun abort() { if (!committed) staged.clear() }

        private fun commitInternal(write: Boolean): ChangeSet {
            check(!committed) { "Transaction already completed" }
            val records = staged.values.map { it.toRecord() }
            val changeSet = ChangeSet(UUID.randomUUID().toString(), records, System.currentTimeMillis(), reason)
            if (write) applyApproved(changeSet)
            committed = true
            return changeSet
        }

        private fun stage(path: String, operation: ChangeOperation, after: String, beforeOverride: String? = null) {
            val existing = staged[path]
            staged[path] = if (existing == null) {
                StagedChange(path, operation, if (beforeOverride != null) beforeOverride else diskContent(path), after, reason)
            } else {
                existing.copy(after = after)
            }
        }

        private fun StagedChange.toRecord() = ChangeRecord(path, operation, before, after, reason, checksum(before), checksum(after))
    }

    @Synchronized
    fun rollback(changeSet: ChangeSet): RollbackResult = rollback(listOf(changeSet))

    @Synchronized
    fun rollback(changeSets: List<ChangeSet>): RollbackResult {
        val records = changeSets.asReversed().flatMap { it.changes.asReversed() }
        if (records.isEmpty()) return RollbackResult.Restored
        val virtual = records.associate { it.path to diskContent(it.path) }.toMutableMap()
        for (record in records) {
            val current = virtual[record.path]
            if (checksum(current) != record.afterChecksum) return RollbackResult.Rejected("Current content changed after transaction: ${record.path}")
            virtual[record.path] = record.before
        }
        val applied = mutableListOf<Pair<ChangeRecord, String?>>()
        return try {
            records.forEach { record ->
                val current = diskContent(record.path)
                applyRecord(record)
                applied += record to current
            }
            RollbackResult.Restored
        } catch (error: Exception) {
            applied.asReversed().forEach { (record, content) ->
                if (content == null) requireSafePath(record.path).delete() else writeAtomically(requireSafePath(record.path), content)
            }
            RollbackResult.Rejected("Rollback failed: ${error.message.orEmpty()}")
        }
    }

    private fun applyRecord(record: ChangeRecord) {
        val file = requireSafePath(record.path)
        if (record.before == null) require(file.delete() || !file.exists()) { "Could not delete ${record.path}" }
        else writeAtomically(file, record.before)
    }

    private fun diskContent(path: String): String? = requireSafePath(path).takeIf { it.isFile }?.readText()

    private fun requireSafePath(path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains("..") && !path.contains('\\')) { "Unsafe project path" }
        val file = root.resolve(path)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe project path" }
        return file
    }

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile ?: root, ".${file.name}.${UUID.randomUUID()}.tmp")
        Files.write(temporary.toPath(), content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW)
        try {
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun checksum(content: String?): String = content?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    } ?: "<missing>"

    private fun String.countOccurrences(target: String): Int = windowed(target.length, partialWindows = false).count { it == target }

    private data class StagedChange(val path: String, val operation: ChangeOperation, val before: String?, val after: String, val reason: String)

    private fun persist(changeSet: ChangeSet) {
        val file = transactionDir.resolve("${changeSet.createdAt}_${changeSet.id}.tsv")
        val lines = listOf("${changeSet.id}\t${changeSet.createdAt}\t${sanitize(changeSet.reason)}") + changeSet.changes.map { listOf(it.path, it.operation, it.beforeChecksum, it.afterChecksum, sanitize(it.reason)).joinToString("\t") }
        Files.write(file.toPath(), (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
    }

    fun verify(): VerificationReport {
        val issues = mutableListOf<VerificationIssue>()
        for (metadata in indexer.index(root)) {
            val path = metadata.path
            if (path.contains("/test/") || path.contains("\\test\\") ||
                path.endsWith("Test.kt") || path.endsWith("Tests.kt")) {
                continue
            }
            // Docs describe the scanner; do not treat those mentions as unfinished work.
            if (path.endsWith(".md") || path.endsWith(".markdown") || path.endsWith(".txt")) {
                continue
            }
            val file = root.resolve(path)
            file.readLines().forEachIndexed { index, line ->
                val marker = unfinishedMarkerMessage(line)
                if (marker != null) issues += VerificationIssue(path, index + 1, marker)
            }
        }
        return VerificationReport(issues.isEmpty(), issues)
    }

    /**
     * Detect real unfinished-work annotations only.
     * Matches comment forms (//, #, block-comment or star prefix) and explicit Kotlin/Java call forms Marker("...").
     * Does not treat prose, UI copy, or documentation about the scanner as unfinished work.
     */
    private fun unfinishedMarkerMessage(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        // Comment annotation forms: line-comment / hash / block-comment prefix + marker token
        val commentMarker = Regex(
            """(?://|#|/\\*|\\*)\\s*($TODO_MARKER|$FIXME_MARKER|$STUB_MARKER)\\b""",
            RegexOption.IGNORE_CASE
        )
        commentMarker.find(trimmed)?.groupValues?.getOrNull(1)?.let { hit ->
            return "${hit.uppercase()} marker remains"
        }
        // Explicit unfinished call form: MarkerName("...")
        val callMarker = Regex(
            """\\b($TODO_MARKER|$FIXME_MARKER)\\s*\\(""",
            RegexOption.IGNORE_CASE
        )
        callMarker.find(trimmed)?.groupValues?.getOrNull(1)?.let { hit ->
            return "${hit.uppercase()} marker remains"
        }
        if (trimmed.contains(STUB_MARKER)) return "$STUB_MARKER marker remains"
        if (trimmed.contains(listOf("throw", "Not" + "ImplementedError").joinToString(" "))) {
            return "unimplemented code remains"
        }
        return null
    }

    fun verifyProposal(changeSet: ChangeSet): VerificationReport {
        val issues = mutableListOf<VerificationIssue>()
        changeSet.changes.forEach { record ->
            if (record.after.isNullOrEmpty()) return@forEach
            record.after.lineSequence().forEachIndexed { index, line ->
                if (unfinishedMarkerMessage(line) != null) {
                    issues += VerificationIssue(record.path, index + 1, "unfinished implementation marker remains")
                }
            }
        }
        return VerificationReport(issues.isEmpty(), issues)
    }

    fun runChecks(commands: List<List<String>>, timeoutSeconds: Long = 90): VerificationReport {
        val commandResults = commands.map { CommandRunner(root).run(it, timeoutSeconds) }
        val issues = commandResults.filter { it.timedOut || it.exitCode != 0 }.map { VerificationIssue("<command>", 0, "${it.command}: exit=${it.exitCode} ${it.stderr.take(400)}") }
        val staticReport = verify()
        return VerificationReport(staticReport.passed && issues.isEmpty(), staticReport.issues + issues, commandResults)
    }

    fun recordLesson(request: String, status: String, evidence: String) {
        lessonsFile.parentFile?.mkdirs()
        lessonsFile.appendText("${Instant.now()}\t${status}\t${sanitize(request)}\t${sanitize(evidence)}\n")
    }

    fun lessons(): List<Lesson> = if (!lessonsFile.isFile) emptyList() else lessonsFile.readLines().mapNotNull { line ->
        val parts = line.split('\t', limit = 4)
        if (parts.size == 4) Lesson(parts[1], parts[2], parts[3], Instant.parse(parts[0]).toEpochMilli()) else null
    }

    private fun sanitize(value: String): String = value.replace('\t', ' ').replace('\n', ' ').trim()
}

class CommandRunner(private val directory: File) {
    private val activeProcess = java.util.concurrent.atomic.AtomicReference<Process?>(null)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancel(reason: String = "cancelled") {
        cancelled.set(true)
        activeProcess.getAndSet(null)?.let { process ->
            runCatching {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun run(
        command: List<String>,
        timeoutSeconds: Long,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null
    ): CommandResult {
        require(command.isNotEmpty()) { "Command cannot be empty" }
        cancelled.set(false)
        return try {
            val process = ProcessBuilder(command).directory(directory).redirectErrorStream(false).start()
            activeProcess.set(process)
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.use { input -> readLimited(input, stdout, onStdout) } }
            val errThread = Thread { process.errorStream.use { input -> readLimited(input, stderr, onStderr) } }
            outThread.start()
            errThread.start()
            var completed = false
            val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                if (cancelled.get()) break
                if (!process.isAlive) { completed = true; break }
                process.waitFor(200, TimeUnit.MILLISECONDS)
            }
            if (!completed) {
                if (!cancelled.get() && !process.isAlive) completed = true
            }
            if (!completed) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            outThread.join(2_000)
            errThread.join(2_000)
            val timedOut = !completed && !cancelled.get()
            val exit = when {
                completed -> runCatching { process.exitValue() }.getOrDefault(-1)
                cancelled.get() -> 130
                else -> -1
            }
            val note = when {
                cancelled.get() && stderr.isEmpty() -> "command cancelled"
                else -> ""
            }
            CommandResult(
                command.joinToString(" "),
                exit,
                stdout.toString().trimEnd('\n'),
                (stderr.toString().trimEnd('\n') + if (note.isNotEmpty()) (if (stderr.isNotEmpty()) "\n" else "") + note else "").trimEnd('\n'),
                timedOut
            )
        } catch (error: Exception) {
            CommandResult(command.joinToString(" "), -1, "", error.message.orEmpty(), false)
        } finally {
            activeProcess.set(null)
        }
    }

    private fun readLimited(input: java.io.InputStream, output: StringBuilder, onChunk: ((String) -> Unit)? = null) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            val chunk = String(buffer, 0, count, Charsets.UTF_8)
            appendLimited(output, chunk)
            onChunk?.invoke(chunk)
        }
    }

    private fun appendLimited(output: StringBuilder, value: String) {
        if (output.length >= 256 * 1024) return
        output.append(value.take(256 * 1024 - output.length))
    }
}
