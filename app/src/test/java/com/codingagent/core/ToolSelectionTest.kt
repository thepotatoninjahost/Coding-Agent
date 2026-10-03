package com.codingagent.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.ToolInvocation
import com.codingagent.agent.ToolKind
import com.codingagent.agent.ToolSelectionLoop
import com.codingagent.agent.ToolSelectionPlan
import com.codingagent.agent.ToolSelector
import com.codingagent.intake.TaskIntakeParser

class ToolSelectionTest {
    @Test
    fun selectsToolsFromIntentAndDependencies() {
        val root = Files.createTempDirectory("tools").toFile()
        val intake = TaskIntakeParser(root).parse("create a Kotlin helper in src/Helper.kt")
        val plan = ToolSelector().select(intake)
        assertEquals(ToolKind.INDEX_REPOSITORY, plan.tools.first().kind)
        assertTrue(plan.tools.any { it.kind == ToolKind.SYNTHESIZE_CODE })
        assertTrue(plan.tools.any { it.kind == ToolKind.APPLY_CHANGES })
        assertEquals(plan.tools[0].id, plan.tools[1].dependsOn.single())
    }

    @Test
    fun loopExecutesOnlyAfterDependenciesAndPersistsEvidence() {
        val plan = ToolSelectionPlan(
            "test",
            listOf(
                ToolInvocation("one", ToolKind.INDEX_REPOSITORY, "index"),
                ToolInvocation("two", ToolKind.SEARCH_PROJECT, "search", listOf("one"))
            ),
            "test"
        )
        val loop = ToolSelectionLoop(plan)
        assertEquals("one", loop.next()!!.id)
        loop.complete("indexed 3 files")
        assertEquals("two", loop.next()!!.id)
        loop.complete("found target")
        assertEquals(null, loop.next())
        assertEquals("complete", loop.currentStatus())
        assertTrue(loop.history().flatMap { it.tools }.any { it.evidence == "indexed 3 files" })
    }

    @Test
    fun productionGateRejectsMutationUntilProjectEvidence() {
        val plan = ToolSelectionPlan(
            "change",
            listOf(
                ToolInvocation("index", ToolKind.INDEX_REPOSITORY, "index"),
                ToolInvocation("search", ToolKind.SEARCH_PROJECT, "search", listOf("index")),
                ToolInvocation("synth", ToolKind.SYNTHESIZE_CODE, "synthesize", listOf("search")),
                ToolInvocation("apply", ToolKind.APPLY_CHANGES, "apply", listOf("synth"))
            ),
            "test"
        )
        val loop = ToolSelectionLoop(plan)
        loop.completeKind(ToolKind.INDEX_REPOSITORY, "indexed")
        assertTrue(loop.authorize("replace_text", ToolKind.APPLY_CHANGES)!!.contains("blocked"))
        loop.completeKind(ToolKind.SEARCH_PROJECT, "found target")
        loop.completeKind(ToolKind.SYNTHESIZE_CODE, "proposal")
        assertEquals(null, loop.authorize("replace_text", ToolKind.APPLY_CHANGES))
    }

    @Test
    fun repeatedProjectReadsRemainAuthorizedAfterFirstSearch() {
        val plan = ToolSelectionPlan(
            "inspect",
            listOf(
                ToolInvocation("index", ToolKind.INDEX_REPOSITORY, "index"),
                ToolInvocation("search", ToolKind.SEARCH_PROJECT, "search", listOf("index"))
            ),
            "test"
        )
        val loop = ToolSelectionLoop(plan)
        loop.completeKind(ToolKind.INDEX_REPOSITORY, "indexed")
        assertEquals(null, loop.authorize("search_project", ToolKind.SEARCH_PROJECT))
        loop.completeKind(ToolKind.SEARCH_PROJECT, "first result")
        assertEquals(null, loop.authorize("read_file", ToolKind.SEARCH_PROJECT))
    }

    @Test
    fun failuresStopTheLoopWithEvidence() {
        val plan = ToolSelectionPlan("test", listOf(ToolInvocation("one", ToolKind.VERIFY, "verify")), "test")
        val loop = ToolSelectionLoop(plan)
        loop.next()
        loop.fail("compiler failed")
        assertEquals("failed", loop.currentStatus())
        assertEquals("compiler failed", loop.currentTools().single().evidence)
        assertEquals(null, loop.next())
    }
}
