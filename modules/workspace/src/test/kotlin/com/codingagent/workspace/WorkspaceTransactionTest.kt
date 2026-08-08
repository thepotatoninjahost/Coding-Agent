package com.codingagent.workspace

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceTransactionTest {
    @Test fun previewDoesNotWriteAndCommitCanRollback() {
        val root = Files.createTempDirectory("workspace").toFile(); root.resolve("A.kt").writeText("one\n")
        val files = ProjectFileStore(root)
        val preview = WorkspaceTransaction(files, "test").apply { replace("A.kt", "one", "two") }.preview()
        assertEquals("one\n", root.resolve("A.kt").readText())
        files.apply(preview); assertEquals("two\n", root.resolve("A.kt").readText())
        assertTrue(files.rollback(listOf(preview)) is RollbackResult.Restored); assertEquals("one\n", root.resolve("A.kt").readText())
    }
}
