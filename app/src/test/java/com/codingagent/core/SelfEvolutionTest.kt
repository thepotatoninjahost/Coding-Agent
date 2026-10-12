package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.agent.PromotionResult
import com.codingagent.agent.SelfEvolution
import com.codingagent.workspace.VerificationReport

class SelfEvolutionTest {
    private fun approvedAction(category: AgentActionCategory = AgentActionCategory.CODE_CHANGE) = AgentAction(
        description = "Promote verified source",
        category = category,
        ownerVerified = true,
        approvalCount = 2,
        sandboxPassed = true,
        clearPermission = true
    )

    @Test
    fun rejectsFailedEvaluationEvenForReadOnlyAction() {
        val root = Files.createTempDirectory("evolution-failed").toFile()
        val evolution = SelfEvolution(root)
        val source = root.resolve("Main.kt").apply { writeText("fun main() = Unit\n") }
        val staged = evolution.stageSource(source, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(false, emptyList()),
            approvedAction(AgentActionCategory.READ_ONLY)
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("passing evaluation"))
        assertEquals(null, evolution.activeVersion())
        assertTrue(evolution.history().isEmpty())
    }

    @Test
    fun rejectsSourceOutsideManagedVersionDirectory() {
        val root = Files.createTempDirectory("evolution-unmanaged").toFile()
        val evolution = SelfEvolution(root)
        val unmanaged = root.resolve("Unmanaged.kt").apply { writeText("class Unmanaged\n") }

        val result = evolution.promoteSource(
            unmanaged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction()
        )

        assertTrue(result is PromotionResult.Rejected)
        assertTrue((result as PromotionResult.Rejected).reason.contains("evolution versions directory"))
        assertEquals(null, evolution.activeVersion())
    }

    @Test
    fun promotesVerifiedManagedSnapshotAndRecordsRecoverablePath() {
        val root = Files.createTempDirectory("evolution-promote").toFile()
        val evolution = SelfEvolution(root)
        val source = root.resolve("Main.kt").apply { writeText("fun main() = Unit\n") }
        val staged = evolution.stageSource(source, "self-repair")

        val result = evolution.promoteSource(
            staged,
            "self-repair",
            VerificationReport(true, emptyList()),
            approvedAction(),
            approvalAt = System.currentTimeMillis()
        )

        assertTrue(result is PromotionResult.Promoted)
        val promoted = (result as PromotionResult.Promoted).version
        assertEquals(promoted.id, evolution.activeVersion())
        assertTrue(promoted.checksum.isNotBlank())
        assertEquals(staged.absolutePath, evolution.history().single().sourcePath)
        assertTrue(evolution.history().single().evaluationPassed)
        assertFalse(evolution.history().single().sourcePath == staged.name)
    }
}
