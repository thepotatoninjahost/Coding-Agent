package com.codingagent.core

import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchModeTest {
    @Test
    fun researchLanesCoverPrimaryCodeDocsAndSites() {
        val lanes = QueryLanes.expand("new compiler architecture").map { it.name }
        assertTrue(lanes.any { it.contains("primary", ignoreCase = true) })
        assertTrue(lanes.any { it.contains("implementation", ignoreCase = true) || it.contains("code", ignoreCase = true) })
        assertTrue(lanes.any { it.contains("documentation", ignoreCase = true) || it.contains("docs", ignoreCase = true) })
        assertTrue(lanes.any { it.contains("community", ignoreCase = true) || it.contains("stackoverflow", ignoreCase = true) || it.contains("github", ignoreCase = true) })
    }

    @Test
    fun experimentalModeAddsExperimentalLane() {
        val lanes = QueryLanes.expand("new compiler architecture", ResearchMode.EXPERIMENTAL).map { it.name }
        assertTrue(lanes.any { it.contains("experimental", ignoreCase = true) })
    }

    @Test
    fun theoreticalModeAddsTheoryLane() {
        val lanes = QueryLanes.expand("type systems", ResearchMode.THEORETICAL).map { it.name }
        assertTrue(lanes.any { it.contains("theory", ignoreCase = true) })
    }
}
