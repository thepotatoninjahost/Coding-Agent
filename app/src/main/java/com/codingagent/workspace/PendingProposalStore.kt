package com.codingagent.workspace

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import com.codingagent.agent.ApprovalRecord

/**
 * ONE JOB: Write pending dual-approval proposals to disk so Review survives process death.
 */
object PendingProposalStore {
    fun file(root: File): File = ProjectMetadataBoundary.resolve(root, ".coding-agent/pending-proposals.json")

    @Synchronized
    fun save(root: File, proposals: List<PendingChangeProposal>) {
        val f = file(root)
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        proposals.forEach { arr.put(toJson(it)) }
        val payload = arr.toString()
        val envelope = JSONObject()
            .put("version", 1)
            .put("payload", payload)
            .put("mac", ProposalIntegrity.mac(payload))
        AtomicFileWriter.write(f, envelope.toString())
    }

    @Synchronized
    fun load(root: File): List<PendingChangeProposal> {
        val f = file(root)
        val text = AtomicFileWriter.readTextIfExists(f)?.trim() ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return runCatching {
            val envelope = JSONObject(text)
            val version = envelope.optInt("version", 0)
            if (version == 1) {
                val payload = envelope.getString("payload")
                val mac = envelope.getString("mac")
                if (!ProposalIntegrity.verify(payload, mac)) return emptyList()
                parsePayload(payload)
            } else {
                // Never authenticate legacy proposal state. Older formats had no
                // integrity envelope, so imported or modified JSON could otherwise
                // be re-signed as trusted approval state during migration. Returning
                // no proposals makes the coordinator reconcile any matching durable
                // waiting job into recovery-required instead of applying untrusted data.
                emptyList()
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

    private fun parsePayload(payload: String): List<PendingChangeProposal> {
        val arr = JSONArray(payload)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
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
