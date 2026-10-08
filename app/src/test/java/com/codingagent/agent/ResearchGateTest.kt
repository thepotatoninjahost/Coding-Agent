package com.codingagent.agent

import com.codingagent.intake.TaskIntakeParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ResearchGateTest {
    @Test
    fun explicitWebResearchRequiresUsableEvidence() {
        val root = Files.createTempDirectory("research-gate-explicit").toFile()
        val intake = TaskIntakeParser(root).parse("Research the web for Kotlin coroutine cancellation best practices")

        assertTrue(ResearchGate.shouldAutoResearch(intake.originalRequest, intake))
        assertTrue(ResearchGate.requiresUsableEvidence(intake.originalRequest, intake))
    }

    @Test
    fun currentDocumentationRequestsRequireUsableEvidence() {
        val root = Files.createTempDirectory("research-gate-current-docs").toFile()
        val intake = TaskIntakeParser(root).parse("Find the latest Kotlin coroutine API documentation")

        assertTrue(ResearchGate.shouldAutoResearch(intake.originalRequest, intake))
        assertTrue(ResearchGate.requiresUsableEvidence(intake.originalRequest, intake))
    }

    @Test
    fun optionalDebugResearchDoesNotBlockLocalEvidenceWhenWebResearchFails() {
        val root = Files.createTempDirectory("research-gate-debug").toFile()
        val intake = TaskIntakeParser(root).parse("Debug this Kotlin exception in my local project")

        assertTrue(ResearchGate.shouldAutoResearch(intake.originalRequest, intake))
        assertFalse(ResearchGate.requiresUsableEvidence(intake.originalRequest, intake))
    }

    @Test
    fun ordinaryLocalCodingRequestDoesNotRequireWebEvidence() {
        val root = Files.createTempDirectory("research-gate-local").toFile()
        val intake = TaskIntakeParser(root).parse("Refactor this Kotlin function to reduce duplication")

        assertFalse(ResearchGate.requiresUsableEvidence(intake.originalRequest, intake))
    }
}
