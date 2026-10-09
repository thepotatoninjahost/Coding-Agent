package com.codingagent.workspace

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * ONE JOB: Persist the owner's open coding job on disk so later turns attach to it.
 */
data class OpenJob(
    val id: String,
    val goal: String,
    val status: String,
    val proposalId: String?,
    val paths: List<String>,
    val updatedAt: Long,
    val appliedProposalId: String? = null,
    val recoveryReason: String? = null
) {
    fun promptBlock(): String = buildString {
        append("OPEN JOB (do not claim there is no prior task):\n")
        append("- id: ").append(id).append('\n')
        append("- status: ").append(status).append('\n')
        append("- goal: ").append(goal.take(1_200)).append('\n')
        if (!proposalId.isNullOrBlank()) append("- proposal: ").append(proposalId).append('\n')
        if (!recoveryReason.isNullOrBlank()) append("- recovery reason: ").append(recoveryReason.take(600)).append('\n')
        if (paths.isNotEmpty()) {
            append("- staged paths:\n")
            paths.forEach { append("  - ").append(it).append('\n') }
        }
        append("\"try again\" / \"continue\" / \"show the file\" means this job, not a new empty project.\n")
    }
}

object OpenJobStore {
    @Volatile
    private var lastRoot: File? = null

    fun file(root: File): File = ProjectMetadataBoundary.resolve(root, ".coding-agent/open-job.json")

    @Synchronized
    fun bind(root: File) {
        lastRoot = root
    }

    @Synchronized
    fun boundRoot(): File? = lastRoot

    @Synchronized
    fun loadBound(): OpenJob? = lastRoot?.let { load(it) }

    @Synchronized
    fun load(root: File): OpenJob? {
        bind(root)
        val f = file(root)
        val text = AtomicFileWriter.readTextIfExists(f) ?: return null
        return try {
            val o = JSONObject(text)
            OpenJob(
                id = o.getString("id"),
                goal = o.getString("goal"),
                status = o.getString("status"),
                proposalId = o.optString("proposalId").takeIf { it.isNotBlank() && it != "null" },
                paths = o.optJSONArray("paths")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                updatedAt = o.optLong("updatedAt", 0L),
                appliedProposalId = o.optString("appliedProposalId").takeIf { it.isNotBlank() && it != "null" },
                recoveryReason = o.optString("recoveryReason").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "Durable open-job state is unreadable; refusing to treat it as absent",
                error
            )
        }
    }

    @Synchronized
    fun save(root: File, job: OpenJob) {
        bind(root)
        val f = file(root)
        f.parentFile?.mkdirs()
        val o = JSONObject()
            .put("id", job.id)
            .put("goal", job.goal)
            .put("status", job.status)
            .put("proposalId", job.proposalId ?: JSONObject.NULL)
            .put("updatedAt", job.updatedAt)
            .put("appliedProposalId", job.appliedProposalId ?: JSONObject.NULL)
            .put("recoveryReason", job.recoveryReason ?: JSONObject.NULL)
        val paths = JSONArray()
        job.paths.forEach { paths.put(it) }
        o.put("paths", paths)
        writeAtomically(f, o.toString())
    }

    /**
     * Continue the existing job when the caller explicitly intends continuation.
     * New owner goals must use [startNew] so stale persisted work cannot leak
     * into an unrelated request.
     */
    @Synchronized
    fun openOrKeep(root: File, goal: String): OpenJob {
        bind(root)
        val existing = load(root)
        if (existing != null && existing.status != "applied" && existing.status != "abandoned") {
            return existing
        }
        return startNew(root, goal)
    }

    /**
     * Start a distinct owner job. This deliberately replaces any prior open job.
     */
    @Synchronized
    fun startNew(root: File, goal: String): OpenJob {
        bind(root)
        val existing = load(root)
        check(existing?.status !in setOf("recovery-required", "waiting-approval", "applying")) {
            when (existing?.status) {
                "waiting-approval" ->
                    "Cannot replace a job with a pending owner-approved proposal; approve or reject that proposal first"
                "applying" ->
                    "Cannot replace a job while a mutation is applying; recover the interrupted mutation first"
                else ->
                    "Cannot replace a recovery-required job before interrupted mutation recovery is resolved"
            }
        }
        val normalized = goal.trim()
        require(normalized.isNotEmpty()) { "A job goal is required" }
        val job = OpenJob(
            id = UUID.randomUUID().toString(),
            goal = normalized,
            status = "open",
            proposalId = null,
            paths = emptyList(),
            updatedAt = System.currentTimeMillis()
        )
        save(root, job)
        return job
    }

    @Synchronized
    fun markWaiting(root: File, proposalId: String, paths: List<String>, goal: String?) {
        bind(root)
        val current = load(root)
        check(
            current?.status !in setOf("recovery-required", "waiting-approval", "applying") ||
                (current.status == "applying" && current.proposalId == proposalId)
        ) {
            "Cannot replace unresolved durable mutation state with a different waiting proposal"
        }
        val job = OpenJob(
            id = current?.id ?: UUID.randomUUID().toString(),
            goal = goal?.takeIf { it.isNotBlank() } ?: current?.goal ?: "",
            status = "waiting-approval",
            proposalId = proposalId,
            paths = paths.ifEmpty { current?.paths ?: emptyList() },
            updatedAt = System.currentTimeMillis()
        )
        save(root, job)
    }

    @Synchronized
    fun markApplying(root: File, proposalId: String, paths: List<String>, goal: String?) {
        bind(root)
        val current = load(root)
            ?: error("Cannot begin mutation apply: durable waiting-approval state is missing")
        check(current.status == "waiting-approval" && current.proposalId == proposalId) {
            "Cannot apply a proposal that is not the currently authorized waiting proposal"
        }
        save(
            root,
            current.copy(
                status = "applying",
                paths = paths.ifEmpty { current.paths },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun markApplied(root: File, proposalId: String? = null) {
        bind(root)
        val current = load(root)
            ?: error("Cannot mark mutation applied: durable open-job state is missing")
        check(current.status == "applying" && !proposalId.isNullOrBlank() && current.proposalId == proposalId) {
            "Cannot mark a mutation applied unless the matching proposal is durably applying"
        }
        save(
            root,
            current.copy(
                status = "applied",
                proposalId = null,
                updatedAt = System.currentTimeMillis(),
                appliedProposalId = proposalId
            )
        )
    }

    @Synchronized
    fun markRecoveryRequired(root: File, reason: String) {
        bind(root)
        // Recovery is the fail-closed state. If the old marker is missing or corrupt,
        // replace it with a minimal recovery record instead of leaving the workspace open.
        val current = runCatching { load(root) }.getOrNull()
        val recoveryReason = reason.takeIf { it.isNotBlank() }?.take(600)
        val recovery = if (current != null) {
            current.copy(
                status = "recovery-required",
                recoveryReason = recoveryReason,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            OpenJob(
                id = UUID.randomUUID().toString(),
                goal = "Interrupted mutation requires recovery",
                status = "recovery-required",
                proposalId = null,
                paths = emptyList(),
                updatedAt = System.currentTimeMillis(),
                recoveryReason = recoveryReason
            )
        }
        save(root, recovery)
    }

    @Synchronized
    fun markReady(root: File) {
        bind(root)
        val current = load(root) ?: return
        check(current.status != "recovery-required") {
            "Cannot clear recovery-required state without explicit recovery resolution"
        }
        save(
            root,
            current.copy(
                status = "open",
                proposalId = null,
                updatedAt = System.currentTimeMillis(),
                appliedProposalId = null,
                recoveryReason = null
            )
        )
    }

    @Synchronized
    fun clear(root: File) {
        AtomicFileWriter.delete(file(root))
    }

    private fun writeAtomically(file: File, content: String) = AtomicFileWriter.write(file, content)

}
