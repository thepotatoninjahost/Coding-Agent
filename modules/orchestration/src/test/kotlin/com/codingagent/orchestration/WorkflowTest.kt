package com.codingagent.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import com.codingagent.workspace.ProjectFileStore

class WorkflowTest {
    @Test fun planningHonorsDependencies() {
        val loop = PlanningLoop(listOf("one" to "first", "two" to "second"))
        assertEquals("one", loop.next()?.phase)
        assertEquals(null, loop.next())
        loop.complete("ok")
        assertEquals("two", loop.next()?.phase)
    }

    @Test fun failedStepCreatesBoundedRecoverySteps() {
        val loop = PlanningLoop(listOf("one" to "first"))
        assertNotNull(loop.next())
        assertTrue(loop.fail("compiler failed"))
        assertTrue(loop.currentSteps().any { it.phase == "diagnose" })
    }

    @Test fun workflowHasOneOrderedPhaseList() {
        assertEquals(listOf("intake", "plan", "research", "context", "proposal", "approval", "apply", "verify", "repair"), AgentWorkflow.phases())
    }

    @Test fun proposalPathsRejectAbsoluteAndTraversalPaths() {
        val source = java.nio.file.Files.createTempDirectory("proposal-paths").toFile()
        val files = ProjectFileStore(source)
        assertFailsWith<IllegalArgumentException> { files.safe("../outside.txt") }
        assertFailsWith<IllegalArgumentException> { files.safe("/outside.txt") }
    }
}
