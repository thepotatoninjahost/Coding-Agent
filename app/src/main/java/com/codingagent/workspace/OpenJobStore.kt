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
        if (!recoveryReason.isNullOrBlank()) append("- recovery reason: ").append(recoveryReason.take(600)).append('\\n')
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

    fun file(root: File): File = File(root, ".coding-agent/open-job.json")

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
        return runCatching {
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
        }.getOrNull()
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
        save(
            root,
            OpenJob(
                id = current?.id ?: UUID.randomUUID().toString(),
                goal = goal?.takeIf { it.isNotBlank() } ?: current?.goal ?: "",
                status = "applying",
                proposalId = proposalId,
                paths = paths.ifEmpty { current?.paths ?: emptyList() },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun markApplied(root: File, proposalId: String? = null) {
        bind(root)
        val current = load(root)
            ?: error("Cannot mark mutation applied: durable open-job state is missing")
        save(
            root,
            current.copy(
                status = "applied",
                proposalId = null,
                updatedAt = System.currentTimeMillis(),
                appliedProposalId = proposalId ?: current.appliedProposalId
            )
        )
    }

    @Synchronized
    fun markRecoveryRequired(root: File, reason: String) {
        bind(root)
        val current = load(root) ?: return
        save(
            root,
            current.copy(
                status = "recovery-required",
                recoveryReason = reason.takeIf { it.isNotBlank() }?.take(600),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun markReady(root: File) {
        bind(root)
        val current = load(root) ?: return
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
