package com.codingagent.workspace

import com.codingagent.domain.ChangeRecord
import com.codingagent.domain.ChangeSet
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

sealed class RollbackResult { data object Restored : RollbackResult(); data class Rejected(val reason: String) : RollbackResult() }

class ProjectFileStore(private val root: File) {
    init { require(root.isDirectory) { "Project root is not a directory" } }
    fun root(): File = root
    fun read(path: String): String? = safe(path).takeIf(File::isFile)?.readText()
    fun write(path: String, content: String) = writeAtomically(safe(path), content)
    fun delete(path: String) { val file = safe(path); require(file.delete() || !file.exists()) { "Could not delete $path" } }
    fun checksum(content: String?): String = content?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) } } ?: "<missing>"
    fun safe(path: String): File { require(path.isNotBlank() && !path.startsWith('/') && !path.contains("..") && !path.contains('\\')) { "Unsafe project path" }; val file = root.resolve(path); require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Unsafe project path" }; return file }
    fun apply(changeSet: ChangeSet) { changeSet.changes.forEach { require(checksum(read(it.path)) == it.beforeChecksum) { "Approved content changed before apply: ${it.path}" } }; val written = mutableListOf<ChangeRecord>(); try { changeSet.changes.forEach { write(it.path, requireNotNull(it.after)); written += it } } catch (error: Exception) { written.asReversed().forEach { if (it.before == null) safe(it.path).delete() else write(it.path, requireNotNull(it.before)) }; throw error } }
    fun rollback(changeSets: List<ChangeSet>): RollbackResult { val records = changeSets.asReversed().flatMap { it.changes.asReversed() }; val original = records.associate { it.path to read(it.path) }; records.forEach { if (checksum(read(it.path)) != it.afterChecksum) return RollbackResult.Rejected("Current content changed after transaction: ${it.path}") }; val applied = mutableListOf<ChangeRecord>(); return try { records.forEach { if (it.before == null) delete(it.path) else write(it.path, requireNotNull(it.before)); applied += it }; RollbackResult.Restored } catch (error: Exception) { applied.asReversed().forEach { val content = original[it.path]; if (content == null) safe(it.path).delete() else write(it.path, content) }; RollbackResult.Rejected("Rollback failed: ${error.message.orEmpty()}") } }
    private fun writeAtomically(file: File, content: String) { file.parentFile?.mkdirs(); val temporary = File(file.parentFile ?: root, ".${file.name}.${UUID.randomUUID()}.tmp"); Files.write(temporary.toPath(), content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW); try { try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) } } finally { temporary.delete() } }
}
