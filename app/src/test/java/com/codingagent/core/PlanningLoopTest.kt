package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentPlan
import com.codingagent.agent.AgentStep
import com.codingagent.agent.PlanStepStatus
import com.codingagent.agent.PlanningLoop
import com.codingagent.agent.ToolKind

class PlanningLoopTest {
    private fun plan(vararg phases: String) = AgentPlan(
        request = "test",
        steps = phases.map { AgentStep(it, it) },
        checks = emptyList()
    )

    @Test fun executesStepsInDependencyOrderAndRecordsSnapshots() {
        val loop = PlanningLoop(plan("intake", "understand", "verify"))
        assertEquals("intake", loop.next()!!.phase)
        loop.complete("request understood")
        assertEquals("understand", loop.next()!!.phase)
        loop.complete("repository indexed")
        assertEquals("verify", loop.next()!!.phase)
        loop.complete("tests passed")
        assertTrue(loop.finishIfReady())
        assertEquals("complete", loop.currentStatus())
        assertTrue(loop.history().size >= 7)
    }

    @Test fun failureAddsDiagnosisAndRecoveryStepsWithoutLosingCompletedWork() {
        val loop = PlanningLoop(plan("intake", "change"), maxReplans = 1)
        loop.next()
        loop.complete("understood")
        loop.next()
        assertTrue(loop.fail("compiler error"))
        assertEquals(PlanStepStatus.COMPLETE, loop.currentSteps().first().status)
        assertTrue(loop.currentSteps().any { it.phase == "diagnose" })
        assertEquals("diagnose", loop.next()!!.phase)
    }

    @Test fun toolAuthorizationFollowsRealEvidencePhases() {
        val loop = PlanningLoop(plan("intake", "understand", "target", "change", "verify"))
        loop.completePhase("intake", "parsed")
        loop.completePhase("understand", "indexed")
        assertEquals(null, loop.authorizeTool("search_project", ToolKind.SEARCH_PROJECT))
        assertTrue(loop.authorizeTool("replace_text", ToolKind.APPLY_CHANGES)!!.contains("blocked"))
        loop.recordSuccess("search_project", ToolKind.SEARCH_PROJECT, "target found")
        assertEquals(null, loop.authorizeTool("replace_text", ToolKind.APPLY_CHANGES))
        loop.recordSuccess("replace_text", ToolKind.APPLY_CHANGES, "proposal staged")
        assertEquals(null, loop.authorizeTool("verify", ToolKind.VERIFY))
    }

    @Test fun iterationLimitStopsRunawayPlanning() {
        val loop = PlanningLoop(plan("one", "two"), maxIterations = 1)
        loop.next()
        loop.complete()
        assertEquals("running", loop.currentStatus())
        assertEquals(null, loop.next())
        assertEquals("iteration-limit", loop.currentStatus())
    }
}
