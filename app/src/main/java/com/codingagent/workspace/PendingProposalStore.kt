package com.codingagent.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject
import com.codingagent.agent.ApprovalRecord

/**
 * ONE JOB: Write pending dual-approval proposals to disk so Review survives process death.
 */
object PendingProposalStore {
    fun file(root: File): File = File(root, ".coding-agent/pending-proposals.json")

    @Synchronized
    fun save(root: File, proposals: List<PendingChangeProposal>) {
        val f = file(root)
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        proposals.forEach { arr.put(toJson(it)) }
        val target = f.toPath()
        val temporary = Files.createTempFile(f.parentFile.toPath(), "pending-proposals-", ".tmp")
        try {
            Files.write(
                temporary,
                arr.toString().toByteArray(Charsets.UTF_8),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic replacement is required for pending proposals", error)
            }
            }
            val onDisk = Files.readAllBytes(target)
            val expected = arr.toString().toByteArray(Charsets.UTF_8)
            require(onDisk.contentEquals(expected)) {
                "Integrity: pending-proposals write did not persist expected bytes for ${f.name}"
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun load(root: File): List<PendingChangeProposal> {
        val f = file(root)
        if (!f.isFile) return emptyList()
        val text = f.readText().trim()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun toJson(p: PendingChangeProposal): JSONObject {
        val changes = JSONArray()
        p.changeSet.changes.forEach { c ->
            changes.put(
                JSONObject()
                    .put("path", c.path)
                    .put("operation", c.operation.name)
                    .put("before", c.before ?: JSONObject.NULL)
                    .put("after", c.after ?: JSONObject.NULL)
                    .put("reason", c.reason)
                    .put("beforeChecksum", c.beforeChecksum)
                    .put("afterChecksum", c.afterChecksum)
            )
        }
        val issues = JSONArray()
        p.verification.issues.forEach { issue ->
            issues.put(
                JSONObject()
                    .put("path", issue.path)
                    .put("line", issue.line)
                    .put("message", issue.message)
            )
        }
        val approvals = JSONArray()
        p.approvals.forEach { a ->
            approvals.put(
                JSONObject()
                    .put("actionId", a.actionId)
                    .put("approvedAt", a.approvedAt)
                    .put("ownerLabel", a.ownerLabel)
                    .put("confirmationNumber", a.confirmationNumber)
            )
        }
        return JSONObject()
            .put("id", p.id)
            .put("request", p.request)
            .put("createdAt", p.createdAt)
            .put("expiresAt", p.expiresAt)
            .put("repairAttempt", p.repairAttempt)
            .put("repairRootRequest", p.repairRootRequest)
            .put("changeSetId", p.changeSet.id)
            .put("changeSetCreatedAt", p.changeSet.createdAt)
            .put("changeSetReason", p.changeSet.reason)
            .put("changes", changes)
            .put("verificationPassed", p.verification.passed)
            .put("issues", issues)
            .put("approvals", approvals)
    }

    private fun fromJson(o: JSONObject): PendingChangeProposal {
        val changesArr = o.getJSONArray("changes")
        val changes = (0 until changesArr.length()).map { i ->
            val c = changesArr.getJSONObject(i)
            ChangeRecord(
                path = c.getString("path"),
                operation = ChangeOperation.valueOf(c.getString("operation")),
                before = if (c.isNull("before")) null else c.optString("before"),
                after = if (c.isNull("after")) null else c.optString("after"),
                reason = c.optString("reason"),
                beforeChecksum = c.optString("beforeChecksum"),
                afterChecksum = c.optString("afterChecksum")
            )
        }
        val issuesArr = o.optJSONArray("issues") ?: JSONArray()
        val issues = (0 until issuesArr.length()).map { i ->
            val n = issuesArr.getJSONObject(i)
            VerificationIssue(n.getString("path"), n.getInt("line"), n.getString("message"))
        }
        val approvalsArr = o.optJSONArray("approvals") ?: JSONArray()
        val approvals = (0 until approvalsArr.length()).map { i ->
            val a = approvalsArr.getJSONObject(i)
            ApprovalRecord(
                actionId = a.getString("actionId"),
                approvedAt = a.getLong("approvedAt"),
                ownerLabel = a.getString("ownerLabel"),
                confirmationNumber = a.getInt("confirmationNumber")
            )
        }
        return PendingChangeProposal(
            id = o.getString("id"),
            request = o.getString("request"),
            changeSet = ChangeSet(
                id = o.optString("changeSetId", o.getString("id")),
                changes = changes,
                createdAt = o.optLong("changeSetCreatedAt", o.getLong("createdAt")),
                reason = o.optString("changeSetReason", o.getString("request"))
            ),
            verification = VerificationReport(o.optBoolean("verificationPassed", true), issues),
            createdAt = o.getLong("createdAt"),
            expiresAt = o.getLong("expiresAt"),
            approvals = approvals,
            repairAttempt = o.optInt("repairAttempt", 0),
            repairRootRequest = o.optString("repairRootRequest").ifBlank { o.getString("request") }
        )
    }
}
