package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.workspace.AtomicFileWriter

class AtomicFileWriterTest {
    @Test
    fun replacesExistingContentAndLeavesNoRecoveryFiles() {
        val root = Files.createTempDirectory("atomic-file").toFile()
        val file = root.resolve("state.json")
        file.writeText("old")

        AtomicFileWriter.write(file, "new")

        assertEquals("new", file.readText())
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
    }

    @Test
    fun recoversPreviousContentWhenBackupMarksInterruptedWrite() {
        val root = Files.createTempDirectory("atomic-recovery").toFile()
        val file = root.resolve("state.json")
        val backup = File(file.path + ".bak")
        val pending = File(file.path + ".new")
        backup.writeText("last-good")
        file.writeText("partial-new")
        pending.writeText("unfinished")

        assertEquals("last-good", AtomicFileWriter.readTextIfExists(file))
        assertFalse(backup.exists())
        assertFalse(pending.exists())
        assertEquals("last-good", file.readText())
    }

    @Test
    fun newFileCommitUsesPendingFileThenLeavesOnlyCommittedState() {
        val root = Files.createTempDirectory("atomic-new").toFile()
        val file = root.resolve("state.json")

        AtomicFileWriter.write(file, "first")

        assertEquals("first", file.readText())
        assertFalse(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".new").exists())
    }
}
