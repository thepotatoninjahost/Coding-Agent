package com.codingagent.core

import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchModeTest {
    @Test
    fun researchLanesCoverPrimaryCodeDocsAndSites() {
        val lanes = QueryLanes.expand("new compiler architecture").map { it.name }
        assertTrue(lanes.contains("primary"))
        assertTrue(lanes.contains("code"))
        assertTrue(lanes.contains("docs"))
        assertTrue(lanes.contains("github") || lanes.contains("so"))
    }

    @Test
    fun experimentalModeAddsExperimentalLane() {
        val lanes = QueryLanes.expand("new compiler architecture", ResearchMode.EXPERIMENTAL).map { it.name }
        assertTrue(lanes.contains("experimental"))
    }

    @Test
    fun theoreticalModeAddsTheoryLane() {
        val lanes = QueryLanes.expand("type systems", ResearchMode.THEORETICAL).map { it.name }
        assertTrue(lanes.contains("theory"))
    }
}
