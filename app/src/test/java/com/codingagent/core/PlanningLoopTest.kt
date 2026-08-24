package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentPlan
import com.codingagent.agent.AgentStep
import com.codingagent.agent.PlanStepStatus
import com.codingagent.agent.PlanningLoop

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

    @Test fun iterationLimitStopsRunawayPlanning() {
        val loop = PlanningLoop(plan("one", "two"), maxIterations = 1)
        loop.next()
        loop.complete()
        assertEquals("running", loop.currentStatus())
        assertEquals(null, loop.next())
        assertEquals("iteration-limit", loop.currentStatus())
    }
}
