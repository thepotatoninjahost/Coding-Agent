package com.codingagent.core

import java.io.File
import com.codingagent.domain.*
import java.nio.file.Files
import com.codingagent.workspace.ProjectFileStore
import com.codingagent.workspace.ProjectIndex
import com.codingagent.workspace.VerificationService
import com.codingagent.workspace.WorkspaceTransaction
import java.time.Instant

sealed class RollbackResult {
    object Restored : RollbackResult()
    data class Rejected(val reason: String) : RollbackResult()
}

class ProjectWorkspace(private val root: File) {
    private val indexer = ProjectIndex()
    private val files = ProjectFileStore(root)
    private val verifier = VerificationService(root, indexer)
    private val metadataDir = root.resolve(".coding-agent")
    private val transactionDir = metadataDir.resolve("transactions")
    private val lessonsFile = metadataDir.resolve("lessons.tsv")
    private val history = mutableListOf<ChangeSet>()

    init {
        require(root.isDirectory) { "Project root is not a directory" }
        transactionDir.mkdirs()
    }

    fun projectRoot(): File = root
    fun summary(): ProjectSummary = indexer.summarize(root)
    fun search(query: String): List<SearchHit> = indexer.search(root, query)

    @Synchronized
    fun transaction(reason: String, block: Transaction.() -> Unit): ChangeSet {
        val transaction = Transaction(WorkspaceTransaction(files, reason))
        transaction.block()
        val changeSet = transaction.commit()
        persist(changeSet)
        history.removeAll { it.id == changeSet.id }
        history += changeSet
        return changeSet
    }

    fun replace(path: String, oldText: String, newText: String, reason: String): ChangeSet = transaction(reason) { replace(path, oldText, newText) }
    fun create(path: String, text: String, reason: String): ChangeSet = transaction(reason) { create(path, text) }
    fun append(path: String, text: String, reason: String): ChangeSet = transaction(reason) { append(path, text) }
    fun remove(path: String, oldText: String, reason: String): ChangeSet = transaction(reason) { remove(path, oldText) }

    fun preview(operations: List<TaskOperation>, reason: String): ChangeSet =
        WorkspaceTransaction.preview(files, operations, "Preview: $reason")

    @Synchronized
    fun applyApproved(changeSet: ChangeSet): ChangeSet {
        require(changeSet.changes.isNotEmpty()) { "Approved change set is empty" }
        files.apply(changeSet)
        persist(changeSet)
        history.removeAll { it.id == changeSet.id }
        history += changeSet
        return changeSet
    }

    inner class Transaction(private val delegate: WorkspaceTransaction) {
        fun replace(path: String, oldText: String, newText: String) = delegate.replace(path, oldText, newText)
        fun create(path: String, text: String) = delegate.create(path, text)
        fun append(path: String, text: String) = delegate.append(path, text)
        fun remove(path: String, oldText: String) = delegate.remove(path, oldText)
        fun commit(): ChangeSet = delegate.commit()
    }

    @Synchronized
    fun rollback(changeSet: ChangeSet): RollbackResult = rollback(listOf(changeSet))

    @Synchronized
    fun rollback(changeSets: List<ChangeSet>): RollbackResult = when (val result = files.rollback(changeSets)) {
        com.codingagent.workspace.RollbackResult.Restored -> {
            history.removeAll { applied -> changeSets.any { it.id == applied.id } }
            RollbackResult.Restored
        }
        is com.codingagent.workspace.RollbackResult.Rejected -> RollbackResult.Rejected(result.reason)
    }

    @Synchronized
    fun recentTransactions(limit: Int = 10): List<ChangeSet> = history.takeLast(limit).reversed()

    @Synchronized
    fun undoLast(): RollbackResult = recentTransactions(1).firstOrNull()?.let(::rollback) ?: RollbackResult.Rejected("No applied transaction is available to undo")

    private fun persist(changeSet: ChangeSet) {
        transactionDir.mkdirs()
        val file = transactionDir.resolve("${changeSet.createdAt}_${changeSet.id}.tsv")
        val lines = mutableListOf("${changeSet.id}\t${changeSet.createdAt}\t${sanitize(changeSet.reason)}")
        changeSet.changes.forEach { change ->
            lines += listOf(
                change.path,
                change.operation.name,
                change.beforeChecksum,
                change.afterChecksum,
                sanitize(change.reason)
            ).joinToString("\t")
        }
        val temporary = transactionDir.resolve(".${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            Files.write(
                temporary.toPath(),
                (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
            )
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporary.delete()
        }
        require(file.isFile && file.length() > 0L) { "Transaction journal was not persisted: ${file.path}" }
    }

    fun verify(): VerificationReport = verifier.verify()

    fun verifyProposal(changeSet: ChangeSet): VerificationReport = verifier.verifyProposal(changeSet)

    fun runChecks(commands: List<List<String>>, timeoutSeconds: Long = 90): VerificationReport = verifier.runChecks(commands, timeoutSeconds)

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

