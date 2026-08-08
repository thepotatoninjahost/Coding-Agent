package com.codingagent.workspace

import com.codingagent.domain.*
import java.util.UUID

class WorkspaceTransaction(private val files: ProjectFileStore, private val reason: String) {
    private val staged = linkedMapOf<String, ChangeRecord>(); private var completed = false
    fun replace(path: String, oldText: String, newText: String) { require(oldText.isNotEmpty()); val current = current(path) ?: error("File does not exist: $path"); require(current.windowed(oldText.length).count { it == oldText } == 1) { "Expected exactly one match in $path" }; stage(path, ChangeOperation.REPLACE, current.replace(oldText, newText)) }
    fun append(path: String, text: String) = stage(path, ChangeOperation.APPEND, (current(path) ?: error("File does not exist: $path")) + text)
    fun remove(path: String, oldText: String) { replace(path, oldText, ""); staged[path] = staged.getValue(path).copy(operation = ChangeOperation.REMOVE) }
    fun create(path: String, text: String) { require(current(path) == null) { "File already exists: $path" }; stage(path, ChangeOperation.CREATE, text, null) }
    fun preview(): ChangeSet = finish()
    fun commit(): ChangeSet = finish().also(files::apply)
    private fun finish(): ChangeSet { check(!completed); completed = true; return ChangeSet(UUID.randomUUID().toString(), staged.values.toList(), System.currentTimeMillis(), reason) }
    private fun current(path: String): String? = staged[path]?.after ?: files.read(path)
    private fun stage(path: String, operation: ChangeOperation, after: String, before: String? = current(path)) { files.safe(path); val existing = staged[path]; val actualBefore = existing?.before ?: before; staged[path] = ChangeRecord(path, operation, actualBefore, after, reason, files.checksum(actualBefore), files.checksum(after)) }
    companion object { fun preview(files: ProjectFileStore, operations: List<TaskOperation>, reason: String): ChangeSet { require(operations.isNotEmpty()); val transaction = WorkspaceTransaction(files, reason); operations.forEach { when (it.kind) { OperationKind.REPLACE -> transaction.replace(requireNotNull(it.path), requireNotNull(it.oldText), requireNotNull(it.newText)); OperationKind.APPEND -> transaction.append(requireNotNull(it.path), requireNotNull(it.text).trimEnd() + "\n"); OperationKind.REMOVE -> transaction.remove(requireNotNull(it.path), requireNotNull(it.oldText)); OperationKind.CREATE_FILE -> transaction.create(requireNotNull(it.path), requireNotNull(it.text)); OperationKind.NONE -> error("Cannot preview an empty operation") } }; return transaction.preview() } }
}
