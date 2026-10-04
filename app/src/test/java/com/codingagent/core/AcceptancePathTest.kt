package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.knowledge.KnowledgeIndex
import com.codingagent.model.AgentModelProtocol
import com.codingagent.model.ModelBackend
import com.codingagent.model.ModelSettings
import com.codingagent.model.RemoteHttpGateway
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.MutationApprovalResult
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace

/**
 * Path A acceptance: offline explicit create/replace → dual approve → disk changed.
 * Also covers reject leaves disk unchanged and multi-file proposals surface every path.
 */
class AcceptancePathTest {
    @Test
    fun dualApprovalAppliesMultiFileChangeSet() {
        val root = Files.createTempDirectory("accept-multi").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/A.kt").writeText("fun a() = 1\n")
        root.resolve("src/B.kt").writeText("fun b() = 1\n")
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)

        val proposeResult = coordinator.propose(
            request = "Bump both helpers",
            operations = listOf(
                TaskOperation(OperationKind.REPLACE, "src/A.kt", "fun a() = 1\n", "fun a() = 2\n"),
                TaskOperation(OperationKind.REPLACE, "src/B.kt", "fun b() = 1\n", "fun b() = 2\n")
            ),
            reason = "acceptance multi-file"
        )
        assertTrue("Proposal should succeed", proposeResult is MutationProposeResult.Proposed)
        val proposal = (proposeResult as MutationProposeResult.Proposed).proposal

        assertEquals(2, proposal.changeSet.changes.size)
        val view = ChangeDiff.summarize(proposal)
        assertEquals(listOf("src/A.kt", "src/B.kt"), view.files.map { it.path })

        assertEquals("fun a() = 1\n", root.resolve("src/A.kt").readText())
        assertEquals("fun b() = 1\n", root.resolve("src/B.kt").readText())

        val first = coordinator.approve(proposal.id, ownerVerified = true, ownerLabel = "owner")
        assertTrue(first is MutationApprovalResult.AwaitingSecond)
        assertEquals("fun a() = 1\n", root.resolve("src/A.kt").readText())

        val second = coordinator.approve(proposal.id, ownerVerified = true, ownerLabel = "owner")
        assertTrue(second is MutationApprovalResult.Applied)
        assertEquals("fun a() = 2\n", root.resolve("src/A.kt").readText())
        assertEquals("fun b() = 2\n", root.resolve("src/B.kt").readText())
    }

    @Test
    fun rejectLeavesDiskUnchanged() {
        val root = Files.createTempDirectory("accept-reject").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)

        val proposeResult = coordinator.propose(
            "risky edit",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 99\n"))
        )
        assertTrue("Proposal should succeed", proposeResult is MutationProposeResult.Proposed)
        val proposal = (proposeResult as MutationProposeResult.Proposed).proposal

        assertTrue(coordinator.reject(proposal.id))
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
        assertTrue(coordinator.pending().isEmpty())
    }

    @Test
    fun createFileRequiresDualApprovalBeforeDiskWrite() {
        val root = Files.createTempDirectory("accept-create").toFile()
        root.resolve("src").mkdirs()
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)

        val proposeResult = coordinator.propose(
            "add helper",
            listOf(TaskOperation(OperationKind.CREATE_FILE, "src/New.kt", text = "class New\n"))
        )
        assertTrue("Proposal should succeed", proposeResult is MutationProposeResult.Proposed)
        val proposal = (proposeResult as MutationProposeResult.Proposed).proposal

        assertFalse(root.resolve("src/New.kt").exists())
        coordinator.approve(proposal.id, true, "owner")
        assertFalse(root.resolve("src/New.kt").exists())
        val applied = coordinator.approve(proposal.id, true, "owner")
        assertTrue(applied is MutationApprovalResult.Applied)
        assertEquals("class New\n", root.resolve("src/New.kt").readText())
    }

    @Test
    fun proposeRejectedWhenNoChanges() {
        val root = Files.createTempDirectory("accept-reject-nochange").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)

        // Same old and new text → no changes → Rejected
        val result = coordinator.propose(
            "no-op edit",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 1\n"))
        )
        assertTrue("Propose should be rejected when content is identical", result is MutationProposeResult.Rejected)
    }

    @Test
    fun failedApprovedChangeStagesRepairAfterRollback() {
        val root = Files.createTempDirectory("accept-repair").toFile()
        root.resolve("gradlew").writeText("#!/bin/sh\nexit 1\n")
        root.resolve("gradlew").setExecutable(true)
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)
        coordinator.setRepairProvider { _, _ ->
            workspace.preview(
                listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n")),
                "repair"
            )
        }
        val proposed = coordinator.propose(
            "run the tests",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = broken\n"))
        )
        assertTrue(proposed is MutationProposeResult.Proposed)
        val original = (proposed as MutationProposeResult.Proposed).proposal
        assertTrue(coordinator.approve(original.id, true, "owner") is MutationApprovalResult.AwaitingSecond)
        val result = coordinator.approve(original.id, true, "owner")
        assertTrue(result is MutationApprovalResult.RepairRequired)
        val repair = (result as MutationApprovalResult.RepairRequired).proposal
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
        assertEquals("Self-repair attempt 1 after failed change: run the tests", repair.request)
        assertEquals("fun main() = 1\n", repair.changeSet.changes.single().before)
        assertEquals("fun main() = 2\n", repair.changeSet.changes.single().after)
        assertEquals(1, coordinator.pending().size)
    }
    @Test
    fun failedRepairStagesNextAttemptAndPreservesRootRequest() {
        val root = Files.createTempDirectory("accept-repair-retry").toFile()
        root.resolve("gradlew").writeText("#!/bin/sh\nexit 1\n")
        root.resolve("gradlew").setExecutable(true)
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)
        coordinator.setRepairProvider { _, attempt ->
            workspace.preview(
                listOf(
                    TaskOperation(
                        OperationKind.REPLACE,
                        "Main.kt",
                        "fun main() = 1\n",
                        "fun main() = ${attempt + 1}\n"
                    )
                ),
                "repair attempt $attempt"
            )
        }

        val proposed = coordinator.propose(
            "run the tests",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = broken\n"))
        ) as MutationProposeResult.Proposed
        var proposal = proposed.proposal

        coordinator.approve(proposal.id, true, "owner")
        var result = coordinator.approve(proposal.id, true, "owner")
        assertTrue(result is MutationApprovalResult.RepairRequired)
        proposal = (result as MutationApprovalResult.RepairRequired).proposal
        assertEquals(1, proposal.repairAttempt)
        assertEquals("Self-repair attempt 1 after failed change: run the tests", proposal.request)

        coordinator.approve(proposal.id, true, "owner")
        result = coordinator.approve(proposal.id, true, "owner")
        assertTrue(result is MutationApprovalResult.RepairRequired)
        proposal = (result as MutationApprovalResult.RepairRequired).proposal
        assertEquals(2, proposal.repairAttempt)
        assertEquals("Self-repair attempt 2 after failed change: run the tests", proposal.request)
        assertEquals("run the tests", proposal.repairRootRequest)
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun knowledgeIngestThenSearchReturnsHit() {
        val root = Files.createTempDirectory("accept-knowledge").toFile()
        val index = KnowledgeIndex(root)
        val body = ("Jetpack Compose is the modern toolkit for building native Android UI. ".repeat(5))
        val result = index.indexText("compose.md", "user-import", body)
        assertTrue(result.chunkCount >= 1)
        val hits = index.search("Compose Android UI", 5)
        assertTrue(hits.isNotEmpty())
        assertEquals("compose.md", hits.first().document)
    }

    @Test
    fun modelSettingsRemoteGatewayFactory() {
        val settings = ModelSettings(
            backend = ModelBackend.REMOTE,
            baseUrl = "https://example.com/v1",
            modelName = "user-chosen-model",
            apiKey = "key-test",
            onboarded = true
        )
        assertTrue(settings.validationErrors().isEmpty())
        val gateway = settings.remoteGateway()
        assertTrue(gateway is RemoteHttpGateway)
    }

    @Test
    fun agentToolCatalogIsStableForRelease() {
        val names = AgentModelProtocol.tools().map { it.name }
        assertEquals(
            listOf(
                "list_files", "read_file", "search_project", "search_knowledge", "research_web",
                "replace_text", "create_file", "run_command", "verify"
            ),
            names
        )
    }
}
