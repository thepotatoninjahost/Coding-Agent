package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.RollbackResult

class RepairCycleTest {
    @Test fun failedVerificationRevertsChanges() {
        val root = Files.createTempDirectory("repair-cycle").toFile()
        val file = root.resolve("Main.kt").apply { writeText("class Main") }
        val workspace = ProjectWorkspace(root)
        val changeSet = workspace.append("Main.kt", "\nTODO", "test")
        val reverted = workspace.rollback(changeSet)
        assertTrue(reverted == RollbackResult.Restored)
        assertTrue(file.readText() == "class Main")
    }
}
