package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {
    @Test
    fun formatBytesUsesHumanUnits() {
        assertEquals("512 B", StorageGuard.formatBytes(512))
        assertTrue(StorageGuard.formatBytes(5_000).contains("KB"))
        assertTrue(StorageGuard.formatBytes(5_000_000).contains("MB"))
        assertTrue(StorageGuard.formatBytes(5_000_000_000L).contains("GB"))
    }

    @Test
    fun reportOkWhenEnoughSpace() {
        val dir = Files.createTempDirectory("storage-ok").toFile()
        val report = StorageGuard.report(dir, remainingDownloadBytes = 1_024L, marginBytes = 1_024L)
        assertTrue(report.ok)
        assertTrue(report.humanMessage().contains("Storage OK"))
    }

    @Test
    fun requireSpaceThrowsWithActionableMessage() {
        val dir = Files.createTempDirectory("storage-fail").toFile()
        val absurd = Long.MAX_VALUE / 4
        val error = runCatching {
            StorageGuard.requireSpace(dir, remainingDownloadBytes = absurd, marginBytes = absurd)
        }.exceptionOrNull()
        assertTrue(error is InsufficientStorageException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("Not enough free space"))
        assertTrue(message.contains("remote gateway") || message.contains("local model"))
    }

    @Test
    fun remainingDownloadBytesCountsMissingShards() {
        val root = Files.createTempDirectory("nexa-remaining").toFile()
        val provisioner = NexaModelProvisioner(
            assetOpener = { error("no assets") },
            root = root,
            connectionFactory = { error("no network") }
        )
        val dir = root.resolve(Qwen3NpuPackage.packageName).apply { mkdirs() }
        assertEquals(Qwen3NpuPackage.totalBytes, provisioner.remainingDownloadBytes(dir))
        val first = Qwen3NpuPackage.files.first()
        dir.resolve(first.name).writeBytes(ByteArray(100))
        val remaining = provisioner.remainingDownloadBytes(dir)
        assertEquals(Qwen3NpuPackage.totalBytes - 100L, remaining)
        assertFalse(remaining == 0L)
    }
}
