package com.codingagent.workspace

import java.io.File
import java.util.UUID

/**
 * Capability granted when the device owner starts an autonomous coding run.
 *
 * It is scoped to one task, one workspace, and one bounded lifetime. It is not
 * exposed to the model as data; the runtime passes it directly to mutation
 * infrastructure so the model cannot manufacture authority through tool input.
 */
class AutonomousExecutionGrant private constructor(
    private val taskId: String,
    private val rootPath: String,
    private val issuedAt: Long,
    private val expiresAt: Long,
    private val nonce: String
) {
    internal fun isValid(expectedTaskId: String, root: File, now: Long): Boolean =
        taskId == expectedTaskId &&
            root.canonicalPath == rootPath &&
            now >= issuedAt &&
            now <= expiresAt &&
            nonce.isNotBlank()

    companion object {
        private const val MAX_LIFETIME_MS = 2 * 60 * 60 * 1000L

        internal fun forRun(taskId: String, root: File, now: Long = System.currentTimeMillis()): AutonomousExecutionGrant {
            require(taskId.isNotBlank()) { "Task id is required" }
            return AutonomousExecutionGrant(
                taskId = taskId,
                rootPath = root.canonicalPath,
                issuedAt = now,
                expiresAt = now + MAX_LIFETIME_MS,
                nonce = UUID.randomUUID().toString()
            )
        }
    }
}
