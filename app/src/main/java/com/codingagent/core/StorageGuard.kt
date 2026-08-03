package com.codingagent.core

import java.io.File

/**
 * Pre-flight disk checks so large model downloads fail with a clear message
 * instead of filling the device and crashing mid-shard.
 */
object StorageGuard {
    /** Extra headroom beyond the remaining download payload (OS + unpack). */
    const val SAFETY_MARGIN_BYTES: Long = 512L * 1024L * 1024L

    data class SpaceReport(
        val path: File,
        val usableBytes: Long,
        val requiredBytes: Long,
        val remainingDownloadBytes: Long
    ) {
        val ok: Boolean get() = usableBytes >= requiredBytes
        val shortfallBytes: Long get() = (requiredBytes - usableBytes).coerceAtLeast(0L)

        fun humanMessage(): String {
            if (ok) {
                return "Storage OK: ${formatBytes(usableBytes)} free; need ${formatBytes(requiredBytes)} " +
                    "(${formatBytes(remainingDownloadBytes)} model + ${formatBytes(SAFETY_MARGIN_BYTES)} margin)"
            }
            return "Not enough free space on ${path.absolutePath}. " +
                "Need about ${formatBytes(requiredBytes)} " +
                "(${formatBytes(remainingDownloadBytes)} remaining model data + ${formatBytes(SAFETY_MARGIN_BYTES)} safety margin), " +
                "but only ${formatBytes(usableBytes)} is available. " +
                "Free at least ${formatBytes(shortfallBytes)} and try again, or import a local model folder / use a remote gateway in Settings."
        }
    }

    fun report(
        targetDir: File,
        remainingDownloadBytes: Long,
        marginBytes: Long = SAFETY_MARGIN_BYTES
    ): SpaceReport {
        targetDir.mkdirs()
        val usable = targetDir.usableSpace
        val required = remainingDownloadBytes + marginBytes
        return SpaceReport(targetDir, usable, required, remainingDownloadBytes)
    }

    fun requireSpace(targetDir: File, remainingDownloadBytes: Long, marginBytes: Long = SAFETY_MARGIN_BYTES): SpaceReport {
        val space = report(targetDir, remainingDownloadBytes, marginBytes)
        if (!space.ok) throw InsufficientStorageException(space.humanMessage(), space)
        return space
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
}

class InsufficientStorageException(
    message: String,
    val report: StorageGuard.SpaceReport
) : Exception(message)
