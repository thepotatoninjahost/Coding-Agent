package com.codingagent.core

import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchModeTest {
    @Test fun researchLanesCoverTheoryExperimentAndEmpiricalEvidence() {
        val lanes = QueryLanes.expand("new compiler architecture").map { it.name }
        assertTrue(lanes.contains("theoretical foundations"))
        assertTrue(lanes.contains("experimental research"))
        assertTrue(lanes.contains("empirical evidence"))
        assertTrue(lanes.contains("alternatives and criticism"))
    }
}
