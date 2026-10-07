package com.codingagent.core

import java.nio.file.Files
import org.json.JSONObject
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
import com.codingagent.workspace.OwnerApprovalToken
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.PendingProposalStore

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

        val first = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        assertTrue(first is MutationApprovalResult.AwaitingSecond)
        assertEquals("fun a() = 1\n", root.resolve("src/A.kt").readText())

        val second = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
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
    fun persistedFirstApprovalSurvivesCoordinatorRestartAndSecondConfirmationIsNumberedTwo() {
        val root = Files.createTempDirectory("accept-approval-restart").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")

        val firstCoordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposed = firstCoordinator.propose(
            "persist approval",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed

        val first = firstCoordinator.approve(proposed.proposal.id, OwnerApprovalToken.authenticated(proposed.proposal.id))
        assertTrue(first is MutationApprovalResult.AwaitingSecond)
        assertEquals(1, (first as MutationApprovalResult.AwaitingSecond).approval.confirmationNumber)

        val restartedCoordinator = MutationCoordinator(ProjectWorkspace(root))
        val restored = restartedCoordinator.pending().single()
        assertEquals(1, restored.approvalCount)
        assertEquals(1, restored.approvals.single().confirmationNumber)

        val second = restartedCoordinator.approve(restored.id, OwnerApprovalToken.authenticated(restored.id))
        assertTrue(second is MutationApprovalResult.Applied)
        val applied = second as MutationApprovalResult.Applied
        assertEquals(2, applied.proposal.approvalCount)
        assertEquals(listOf(1, 2), applied.proposal.approvals.map { it.confirmationNumber })
        assertEquals("fun main() = 2\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun pendingProposalStoreReplacesStateAtomicallyAndLeavesNoTemporaryFiles() {
        val root = Files.createTempDirectory("accept-pending-store").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposed = coordinator.propose(
            "persist pending state",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed

        PendingProposalStore.save(root, listOf(proposed.proposal))
        assertEquals(1, PendingProposalStore.load(root).size)

        PendingProposalStore.save(root, emptyList())
        assertEquals(emptyList<Any>(), PendingProposalStore.load(root))
        val envelope = JSONObject(PendingProposalStore.file(root).readText())
        assertEquals(1, envelope.getInt("version"))
        assertTrue(envelope.getString("mac").isNotBlank())
        assertTrue(
            root.resolve(".coding-agent").listFiles()
                .orEmpty()
                .none { it.name.startsWith("pending-proposals-") && it.name.endsWith(".tmp") }
        )
    }

    @Test
    fun tamperedPendingProposalIsRejected() {
        val root = Files.createTempDirectory("accept-pending-tamper").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposed = coordinator.propose(
            "protect pending state",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed

        PendingProposalStore.save(root, listOf(proposed.proposal))
        val envelope = JSONObject(PendingProposalStore.file(root).readText())
        val payload = JSONObject(envelope.getString("payload"))
        // Mutating authenticated payload without updating its Keystore-backed MAC
        // must invalidate the entire persisted approval state.
        val payloadArray = org.json.JSONArray(envelope.getString("payload"))
        val proposalJson = payloadArray.getJSONObject(0)
        proposalJson.put("request", "attacker changed the approved request")
        envelope.put("payload", payloadArray.toString())
        PendingProposalStore.file(root).writeText(envelope.toString())

        assertTrue(PendingProposalStore.load(root).isEmpty())
    }

    @Test
    fun approvalTokenMustBeBoundToThePendingProposal() {
        val root = Files.createTempDirectory("accept-approval-identity").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposal = (coordinator.propose(
            "identity required",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed).proposal

        val result = coordinator.approve(
            proposal.id,
            OwnerApprovalToken.authenticated("different-proposal")
        )
        assertTrue(result is MutationApprovalResult.Rejected)
        assertEquals(0, coordinator.pending().single().approvalCount)
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun approvalTokenCannotBeReplayed() {
        val root = Files.createTempDirectory("accept-approval-replay").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposal = (coordinator.propose(
            "replay protected",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed).proposal
        val token = OwnerApprovalToken.authenticated(proposal.id)

        assertTrue(coordinator.approve(proposal.id, token) is MutationApprovalResult.AwaitingSecond)
        assertTrue(coordinator.approve(proposal.id, token) is MutationApprovalResult.Rejected)
        assertEquals(1, coordinator.pending().single().approvalCount)
    }

    @Test
    fun expiredProposalCannotBeApprovedOrWritten() {
        val root = Files.createTempDirectory("accept-approval-expiry").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        var now = 1_000L
        val coordinator = MutationCoordinator(
            ProjectWorkspace(root),
            now = { now }
        )
        val proposal = (coordinator.propose(
            "expired edit",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed).proposal

        now = proposal.expiresAt + 1L
        val result = coordinator.approve(
            proposal.id,
            OwnerApprovalToken.authenticated(proposal.id, now = now)
        )

        assertTrue(result is MutationApprovalResult.Rejected)
        assertEquals(0, coordinator.pending().size)
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun approvalCannotSurviveExternalChangeBeforeExecution() {
        val root = Files.createTempDirectory("accept-approval-toctou").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val coordinator = MutationCoordinator(ProjectWorkspace(root))
        val proposal = (coordinator.propose(
            "protected edit",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n"))
        ) as MutationProposeResult.Proposed).proposal

        assertTrue(
            coordinator.approve(
                proposal.id,
                OwnerApprovalToken.authenticated(proposal.id)
            ) is MutationApprovalResult.AwaitingSecond
        )

        root.resolve("Main.kt").writeText("fun main() = 99\n")
        val result = coordinator.approve(
            proposal.id,
            OwnerApprovalToken.authenticated(proposal.id)
        )

        assertTrue(result is MutationApprovalResult.Rejected)
        assertEquals("fun main() = 99\n", root.resolve("Main.kt").readText())
        assertEquals(1, coordinator.pending().single().approvalCount)
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
        coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        assertFalse(root.resolve("src/New.kt").exists())
        val applied = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
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
        assertTrue(coordinator.approve(original.id, OwnerApprovalToken.authenticated(original.id)) is MutationApprovalResult.AwaitingSecond)
        val result = coordinator.approve(original.id, OwnerApprovalToken.authenticated(original.id))
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

        coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        var result = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        assertTrue("Unexpected first repair result: $result", result is MutationApprovalResult.RepairRequired)
        proposal = (result as MutationApprovalResult.RepairRequired).proposal
        assertEquals(1, proposal.repairAttempt)
        assertEquals("Self-repair attempt 1 after failed change: run the tests", proposal.request)

        coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        result = coordinator.approve(proposal.id, OwnerApprovalToken.authenticated(proposal.id))
        assertTrue(result is MutationApprovalResult.RepairRequired)
        proposal = (result as MutationApprovalResult.RepairRequired).proposal
        assertEquals(2, proposal.repairAttempt)
        assertEquals("Self-repair attempt 2 after failed change: run the tests", proposal.request)
        assertEquals("run the tests", proposal.repairRootRequest)
        assertEquals("fun main() = 1\n", root.resolve("Main.kt").readText())
    }

    @Test
    fun mutationProposalRetainsVerificationIntent() {
        val root = Files.createTempDirectory("accept-request-context").toFile()
        root.resolve("gradlew").writeText("#!/bin/sh\nexit 1\n")
        root.resolve("gradlew").setExecutable(true)
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val coordinator = MutationCoordinator(workspace)
        val result = coordinator.propose(
            "run ./gradlew test after changing Main.kt",
            listOf(TaskOperation(OperationKind.REPLACE, "Main.kt", "fun main() = 1\n", "fun main() = 2\n")),
            "model mutation"
        )
        assertTrue(result is MutationProposeResult.Proposed)
        assertEquals(
            "run ./gradlew test after changing Main.kt",
            (result as MutationProposeResult.Proposed).proposal.request
        )
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
