package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.workspace.OpenJobStore
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectWorkspace

class OpenJobStoreTest {
    @Test
    fun survivesReloadAndKeepsSameGoal() {
        val root = Files.createTempDirectory("open-job").toFile()
        val first = OpenJobStore.openOrKeep(root, "create a autonomous agent")
        val second = OpenJobStore.openOrKeep(root, "try again")
        assertEquals(first.id, second.id)
        assertEquals("create a autonomous agent", second.goal)
        OpenJobStore.markWaiting(root, "proposal-1", listOf("src/AutonomousAgent.kt"), null)
        val loaded = OpenJobStore.load(root)!!
        assertEquals("waiting-approval", loaded.status)
        assertEquals("proposal-1", loaded.proposalId)
        assertTrue(loaded.promptBlock().contains("OPEN JOB"))
        assertTrue(loaded.promptBlock().contains("create a autonomous agent"))
    }

    @Test
    fun startNewReplacesExistingOpenJob() {
        val root = Files.createTempDirectory("open-job-new-goal").toFile()
        val first = OpenJobStore.openOrKeep(root, "fix the parser")
        val second = OpenJobStore.startNew(root, "build the terminal tool")

        assertTrue(first.id != second.id)
        assertEquals("build the terminal tool", second.goal)
        assertEquals("open", second.status)
        assertEquals(null, second.proposalId)
    }


    @Test
    fun startNewCannotDiscardWaitingOrApplyingMutationJobs() {
        listOf("waiting-approval", "applying").forEach { status ->
            val root = Files.createTempDirectory("open-job-protected-$status").toFile()
            OpenJobStore.startNew(root, "original mutation goal")
            OpenJobStore.markWaiting(root, "proposal-$status", listOf("Main.kt"), "original mutation goal")
            if (status == "applying") {
                OpenJobStore.markApplying(root, "proposal-$status", listOf("Main.kt"), "original mutation goal")
            }
            val before = OpenJobStore.load(root)!!

            val replacement = runCatching { OpenJobStore.startNew(root, "unrelated new goal") }

            assertTrue("Replacing $status state must be rejected", replacement.isFailure)
            assertEquals("The durable job must remain unchanged", before, OpenJobStore.load(root))
        }
    }

    @Test
    fun markWaitingCannotReplaceAnotherUnresolvedProposal() {
        val root = Files.createTempDirectory("open-job-waiting-replacement").toFile()
        OpenJobStore.startNew(root, "first mutation")
        OpenJobStore.markWaiting(root, "proposal-original", listOf("Main.kt"), "first mutation")
        val before = OpenJobStore.load(root)!!

        val replacement = runCatching {
            OpenJobStore.markWaiting(root, "proposal-stale", listOf("Other.kt"), "unrelated mutation")
        }

        assertTrue("A second proposal must not replace the durable waiting proposal", replacement.isFailure)
        assertEquals(before, OpenJobStore.load(root))
    }

    @Test
    fun markApplyingRequiresTheMatchingWaitingProposal() {
        val root = Files.createTempDirectory("open-job-applying-authority").toFile()
        OpenJobStore.startNew(root, "approved mutation")
        OpenJobStore.markWaiting(root, "proposal-authorized", listOf("Main.kt"), "approved mutation")

        val staleAttempt = runCatching {
            OpenJobStore.markApplying(root, "proposal-stale", listOf("Other.kt"), "stale request")
        }
        assertTrue("A stale proposal must not enter the apply phase", staleAttempt.isFailure)
        assertEquals("waiting-approval", OpenJobStore.load(root)?.status)
        assertEquals("proposal-authorized", OpenJobStore.load(root)?.proposalId)

        OpenJobStore.markApplying(root, "proposal-authorized", listOf("Main.kt"), "approved mutation")
        assertEquals("applying", OpenJobStore.load(root)?.status)
    }

    @Test
    fun markAppliedRequiresTheMatchingApplyingProposal() {
        val root = Files.createTempDirectory("open-job-applied-authority").toFile()
        OpenJobStore.startNew(root, "approved mutation")
        OpenJobStore.markWaiting(root, "proposal-authorized", listOf("Main.kt"), "approved mutation")
        OpenJobStore.markApplying(root, "proposal-authorized", listOf("Main.kt"), "approved mutation")

        val staleAttempt = runCatching { OpenJobStore.markApplied(root, "proposal-stale") }
        assertTrue("A different proposal must not mark the current mutation applied", staleAttempt.isFailure)
        assertEquals("applying", OpenJobStore.load(root)?.status)
        assertEquals("proposal-authorized", OpenJobStore.load(root)?.proposalId)

        OpenJobStore.markApplied(root, "proposal-authorized")
        assertEquals("applied", OpenJobStore.load(root)?.status)
        assertEquals("proposal-authorized", OpenJobStore.load(root)?.appliedProposalId)
    }

    @Test
    fun recoveryRequiredStateCanBeRecreatedWhenPriorMarkerIsMissing() {
        val root = Files.createTempDirectory("open-job-recovery-missing").toFile()

        OpenJobStore.markRecoveryRequired(root, "rollback incomplete")

        val loaded = OpenJobStore.load(root)
        assertEquals("recovery-required", loaded?.status)
        assertEquals("rollback incomplete", loaded?.recoveryReason)
    }

    @Test
    fun recoveryRequiredStateReplacesCorruptMarker() {
        val root = Files.createTempDirectory("open-job-recovery-corrupt").toFile()
        OpenJobStore.file(root).apply {
            parentFile?.mkdirs()
            writeText("not valid json")
        }

        OpenJobStore.markRecoveryRequired(root, "rollback incomplete")

        val loaded = OpenJobStore.load(root)
        assertEquals("recovery-required", loaded?.status)
        assertEquals("rollback incomplete", loaded?.recoveryReason)
    }

    @Test
    fun coordinatorStartsFailClosedAndRepairsCorruptDurableJobState() {
        val root = Files.createTempDirectory("open-job-corrupt-startup").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        OpenJobStore.file(root).apply {
            parentFile?.mkdirs()
            writeText("not valid json")
        }

        val coordinator = MutationCoordinator(ProjectWorkspace(root))

        assertTrue(coordinator.pending().isEmpty())
        assertEquals("recovery-required", OpenJobStore.load(root)?.status)
        assertTrue(OpenJobStore.load(root)?.recoveryReason.orEmpty().contains("unreadable"))
    }

    @Test(expected = IllegalStateException::class)
    fun startNewFailsClosedWhenJobIsRecoveryRequired() {
        val root = Files.createTempDirectory("open-job-recovery-start").toFile()
        OpenJobStore.startNew(root, "interrupted mutation")
        OpenJobStore.markRecoveryRequired(root, "partial apply")
        OpenJobStore.startNew(root, "different goal")
    }

    @Test(expected = IllegalStateException::class)
    fun markReadyCannotClearRecoveryRequiredState() {
        val root = Files.createTempDirectory("open-job-recovery-ready").toFile()
        OpenJobStore.startNew(root, "interrupted mutation")
        OpenJobStore.markRecoveryRequired(root, "partial apply")
        OpenJobStore.markReady(root)
    }

    @Test
    fun recoveryReasonIsDurableWithoutOverwritingOriginalGoal() {
        val root = Files.createTempDirectory("open-job-recovery-reason").toFile()
        OpenJobStore.startNew(root, "interrupted mutation")
        OpenJobStore.markRecoveryRequired(root, "partial apply")
        val loaded = OpenJobStore.load(root)!!
        assertEquals("interrupted mutation", loaded.goal)
        assertEquals("partial apply", loaded.recoveryReason)
        assertTrue(loaded.promptBlock().contains("partial apply"))
    }

    @Test(expected = IllegalStateException::class)
    fun markAppliedFailsClosedWhenJobMarkerIsMissing() {
        val root = Files.createTempDirectory("open-job-missing").toFile()
        OpenJobStore.markApplied(root, "proposal-missing")
    }

    @Test(expected = IllegalStateException::class)
    fun unreadableDurableStateIsNotTreatedAsMissing() {
        val root = Files.createTempDirectory("open-job-corrupt").toFile()
        root.resolve(".coding-agent").mkdirs()
        root.resolve(".coding-agent/open-job.json").writeText("not valid json")
        OpenJobStore.startNew(root, "different goal")
    }

    @Test
    fun saveReplacesJobAtomicallyAndLeavesNoTemporaryFiles() {
        val root = Files.createTempDirectory("open-job-atomic").toFile()
        val first = OpenJobStore.openOrKeep(root, "first goal")
        OpenJobStore.save(
            root,
            first.copy(
                goal = "second goal",
                status = "waiting-approval",
                proposalId = "proposal-2",
                paths = listOf("src/Agent.kt")
            )
        )
        val loaded = OpenJobStore.load(root)!!
        assertEquals("second goal", loaded.goal)
        assertEquals("waiting-approval", loaded.status)
        assertEquals("proposal-2", loaded.proposalId)
        assertEquals(listOf("src/Agent.kt"), loaded.paths)
        val tempFiles = root.resolve(".coding-agent").listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            .orEmpty()
        assertTrue("Atomic save left temporary files: $tempFiles", tempFiles.isEmpty())
    }
}
