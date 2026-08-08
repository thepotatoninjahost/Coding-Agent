package com.codingagent.core

import com.codingagent.policy.ApprovalRecord
import com.codingagent.domain.ChangeRecord
import com.codingagent.domain.ChangeSet
import com.codingagent.domain.ChangeOperation
import com.codingagent.domain.CommandResult
import com.codingagent.domain.VerificationIssue
import com.codingagent.domain.VerificationReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class DurableStateStore(metadataDir: File) {
    private val transactionDir = metadataDir.resolve("transactions")
    private val proposalDir = metadataDir.resolve("proposals")
    private val loadErrors = mutableListOf<String>()

    init {
        require(metadataDir.exists() || metadataDir.mkdirs()) { "Could not create durable state directory: ${metadataDir.path}" }
        require(transactionDir.exists() || transactionDir.mkdirs()) { "Could not create transaction directory: ${transactionDir.path}" }
        require(proposalDir.exists() || proposalDir.mkdirs()) { "Could not create proposal directory: ${proposalDir.path}" }
    }

    fun errors(): List<String> = synchronized(loadErrors) { loadErrors.toList() }

    fun saveChangeSet(changeSet: ChangeSet) {
        writeAtomically(transactionDir.resolve("${changeSet.createdAt}_${changeSet.id}.json"), changeSetJson(changeSet).toString())
    }

    fun deleteChangeSet(changeSet: ChangeSet) {
        transactionFilesFor(changeSet).forEach { file ->
            require(!file.exists() || file.delete()) { "Could not remove transaction snapshot: ${file.path}" }
        }
    }

    fun loadChangeSets(): List<ChangeSet> = transactionDir.listFiles()
        ?.filter { it.isFile && it.extension == "json" }
        ?.sortedBy { it.name }
        ?.mapNotNull { file -> load(file, "transaction") { parseChangeSet(JSONObject(file.readText())) } }
        .orEmpty()

    fun saveProposal(proposal: PendingChangeProposal) {
        writeAtomically(proposalDir.resolve("${proposal.id}.json"), proposalJson(proposal).toString())
    }

    fun deleteProposal(id: String) {
        val file = proposalDir.resolve("$id.json")
        require(!file.exists() || file.delete()) { "Could not remove proposal snapshot: ${file.path}" }
    }

    fun loadProposals(): List<PendingChangeProposal> = proposalDir.listFiles()
        ?.filter { it.isFile && it.extension == "json" }
        ?.sortedBy { it.name }
        ?.mapNotNull { file -> load(file, "proposal") { parseProposal(JSONObject(file.readText())) } }
        .orEmpty()

    private fun transactionFilesFor(changeSet: ChangeSet): List<File> = transactionDir.listFiles()
        ?.filter { it.isFile && it.extension == "json" && it.name.endsWith("_${changeSet.id}.json") }
        .orEmpty()

    private fun <T> load(file: File, kind: String, parser: () -> T): T? = try {
        parser()
    } catch (error: Exception) {
        synchronized(loadErrors) {
            loadErrors += "Could not recover $kind snapshot ${file.name}: ${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}"
        }
        null
    }

    private fun writeAtomically(file: File, content: String) {
        val temporary = file.resolveSibling(".${file.name}.${System.nanoTime()}.tmp")
        Files.write(temporary.toPath(), content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        require(file.isFile && file.length() > 0L) { "Durable snapshot was not persisted: ${file.path}" }
    }

    private fun changeSetJson(changeSet: ChangeSet): JSONObject = JSONObject()
        .put("id", changeSet.id)
        .put("createdAt", changeSet.createdAt)
        .put("reason", changeSet.reason)
        .put("changes", JSONArray().also { array -> changeSet.changes.forEach { array.put(changeJson(it)) } })

    private fun changeJson(change: ChangeRecord): JSONObject = JSONObject()
        .put("path", change.path)
        .put("operation", change.operation.name)
        .putNullable("before", change.before)
        .putNullable("after", change.after)
        .put("reason", change.reason)
        .put("beforeChecksum", change.beforeChecksum)
        .put("afterChecksum", change.afterChecksum)

    private fun verificationJson(report: VerificationReport): JSONObject = JSONObject()
        .put("passed", report.passed)
        .put("issues", JSONArray().also { array -> report.issues.forEach { array.put(JSONObject().put("path", it.path).put("line", it.line).put("message", it.message)) } })
        .put("commands", JSONArray().also { array -> report.commands.forEach { array.put(commandJson(it)) } })

    private fun commandJson(command: CommandResult): JSONObject = JSONObject()
        .put("command", command.command)
        .put("exitCode", command.exitCode)
        .put("stdout", command.stdout)
        .put("stderr", command.stderr)
        .put("timedOut", command.timedOut)

    private fun proposalJson(proposal: PendingChangeProposal): JSONObject = JSONObject()
        .put("id", proposal.id)
        .put("request", proposal.request)
        .put("changeSet", changeSetJson(proposal.changeSet))
        .put("verification", verificationJson(proposal.verification))
        .put("createdAt", proposal.createdAt)
        .put("expiresAt", proposal.expiresAt)
        .put("approvals", JSONArray().also { array -> proposal.approvals.forEach { array.put(JSONObject().put("actionId", it.actionId).put("approvedAt", it.approvedAt).put("ownerLabel", it.ownerLabel).put("confirmationNumber", it.confirmationNumber)) } })

    private fun parseChangeSet(json: JSONObject): ChangeSet = ChangeSet(
        id = json.getString("id"),
        changes = json.getJSONArray("changes").toChanges(),
        createdAt = json.getLong("createdAt"),
        reason = json.getString("reason")
    )

    private fun parseProposal(json: JSONObject): PendingChangeProposal = PendingChangeProposal(
        id = json.getString("id"),
        request = json.getString("request"),
        changeSet = parseChangeSet(json.getJSONObject("changeSet")),
        verification = parseVerification(json.getJSONObject("verification")),
        createdAt = json.getLong("createdAt"),
        expiresAt = json.getLong("expiresAt"),
        approvals = json.getJSONArray("approvals").toApprovals()
    )

    private fun parseVerification(json: JSONObject): VerificationReport = VerificationReport(
        passed = json.getBoolean("passed"),
        issues = json.getJSONArray("issues").let { array -> (0 until array.length()).map { index -> array.getJSONObject(index).let { VerificationIssue(it.getString("path"), it.getInt("line"), it.getString("message")) } } },
        commands = json.getJSONArray("commands").let { array -> (0 until array.length()).map { index -> array.getJSONObject(index).let { CommandResult(it.getString("command"), it.getInt("exitCode"), it.getString("stdout"), it.getString("stderr"), it.getBoolean("timedOut")) } } }
    )

    private fun JSONArray.toChanges(): List<ChangeRecord> = (0 until length()).map { index -> getJSONObject(index).let { json -> ChangeRecord(json.getString("path"), ChangeOperation.valueOf(json.getString("operation")), json.nullableString("before"), json.nullableString("after"), json.getString("reason"), json.getString("beforeChecksum"), json.getString("afterChecksum")) } }

    private fun JSONArray.toApprovals(): List<ApprovalRecord> = (0 until length()).map { index -> getJSONObject(index).let { json -> ApprovalRecord(json.getString("actionId"), json.getLong("approvedAt"), json.getString("ownerLabel"), json.getInt("confirmationNumber")) } }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
}
