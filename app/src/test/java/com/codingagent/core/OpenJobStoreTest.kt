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
