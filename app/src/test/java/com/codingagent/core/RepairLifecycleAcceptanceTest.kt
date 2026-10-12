package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.OwnerApprovalToken
import com.codingagent.workspace.ProjectWorkspace

class RepairLifecycleAcceptanceTest {
    private val ownerPhrase = "owner-created test phrase"
    @Test
    fun failedChangeRollsBackThenApprovedRepairPassesVerification() {
        val root = Files.createTempDirectory("repair-lifecycle").toFile()
        val source = root.resolve("Main.kt").apply { writeText("fun main() = 1\n") }
        root.resolve("gradlew").apply {
            writeText(
                "#!/bin/sh\n" +
                    "if grep -q broken Main.kt; then exit 1; fi\n" +
                    "exit 0\n"
            )
            setExecutable(true)
        }

        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)
        coordinator.setRepairProvider { _, _ ->
            workspace.preview(
                listOf(
                    TaskOperation(
                        kind = OperationKind.REPLACE,
                        path = "Main.kt",
                        oldText = "fun main() = 1\n",
                        newText = "fun main() = 2\n"
                    )
                ),
                "verified repair"
            )
        }

        val original = (coordinator.propose(
            request = "run the tests",
            operations = listOf(
                TaskOperation(
                    kind = OperationKind.REPLACE,
                    path = "Main.kt",
                    oldText = "fun main() = 1\n",
                    newText = "fun main() = broken\n"
                )
            )
        ) as MutationProposeResult.Proposed).proposal

        assertEquals("fun main() = 1\n", source.readText())
        assertTrue(
            coordinator.approve(original.id, OwnerApprovalToken.spoken(original.id, ownerPhrase, ownerPhrase)!!)
                is MutationApprovalResult.AwaitingSecond
        )
        assertEquals("fun main() = 1\n", source.readText())

        val failedApply = coordinator.approve(
            original.id,
            OwnerApprovalToken.typed(original.id, ownerPhrase, ownerPhrase)!!
        )
        assertTrue("Expected repair proposal after failed verification: $failedApply", failedApply is MutationApprovalResult.RepairRequired)
        val repair = (failedApply as MutationApprovalResult.RepairRequired).proposal

        assertEquals("fun main() = 1\n", source.readText())
        assertEquals(0, repair.approvalCount)
        assertEquals("fun main() = 2\n", repair.changeSet.changes.single().after)

        assertTrue(
            coordinator.approve(repair.id, OwnerApprovalToken.spoken(repair.id, ownerPhrase, ownerPhrase)!!)
                is MutationApprovalResult.AwaitingSecond
        )
        assertEquals("fun main() = 1\n", source.readText())

        val repaired = coordinator.approve(
            repair.id,
            OwnerApprovalToken.typed(repair.id, ownerPhrase, ownerPhrase)!!
        )
        assertTrue("Expected verified repair to apply: $repaired", repaired is MutationApprovalResult.Applied)
        assertEquals("fun main() = 2\n", source.readText())
    }
}
