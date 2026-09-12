package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentPlan
import com.codingagent.agent.AgentStep
import com.codingagent.agent.SelfRepair
import com.codingagent.intake.OperationKind
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.ProjectWorkspace

class SelfRepairTest {
    @Test
    fun detectsFixYourselfPhrases() {
        assertTrue(SelfRepair.isRequest("fix yourself"))
        assertTrue(SelfRepair.isRequest("Please modify yourself"))
        assertTrue(SelfRepair.isRequest("self-repair the loop"))
        assertFalse(SelfRepair.isRequest("list files"))
        assertFalse(SelfRepair.isRequest("hello"))
    }

    @Test
    fun emptyProjectStagesSelfRepairSpine() {
        val root = Files.createTempDirectory("self-repair").toFile()
        val workspace = ProjectWorkspace(root)
        val files = ProjectFileService(workspace)
        val mutations = MutationCoordinator(workspace)
        val plan = AgentPlan("fix yourself", listOf(AgentStep("repair", "stage")), emptyList())
        val task = SelfRepair.handle("t1", "fix yourself", plan, workspace, files, mutations)
        assertNotNull(task)
        assertEquals("needs-approval", task!!.status)
        assertEquals("src/SelfRepair.kt", task.changes.single().path)
        assertTrue(mutations.pending().isNotEmpty())
    }

    @Test
    fun agentTreeGetsContractStampNotHelloWorld() {
        val root = Files.createTempDirectory("self-repair-agent").toFile()
        val target = root.resolve("ChatWorkspace.kt")
        target.writeText("package com.codingagent.agent\nclass ChatWorkspace\n")
        val workspace = ProjectWorkspace(root)
        val files = ProjectFileService(workspace)
        val op = SelfRepair.chooseOperation(workspace, files, workspace.verify())
        requireNotNull(op)
        assertEquals(OperationKind.REPLACE, op.kind)
        assertEquals("ChatWorkspace.kt", op.path)
        assertTrue(op.newText.orEmpty().contains("SELF_REPAIR_CONTRACT"))
        assertFalse(op.newText.orEmpty().contains("Hello, World"))
    }

    @Test
    fun handleOnAgentTreeStagesStampNotToyFile() {
        val root = Files.createTempDirectory("self-repair-handle").toFile()
        root.resolve("ChatWorkspace.kt").writeText("package com.codingagent.agent\nclass ChatWorkspace\n")
        val workspace = ProjectWorkspace(root)
        val files = ProjectFileService(workspace)
        val mutations = MutationCoordinator(workspace)
        val plan = AgentPlan("modify yourself", listOf(AgentStep("repair", "stage")), emptyList())
        val task = SelfRepair.handle("t2", "modify yourself", plan, workspace, files, mutations)
        assertNotNull(task)
        assertEquals("needs-approval", task!!.status)
        assertEquals("ChatWorkspace.kt", task.changes.single().path)
        assertTrue(task.changes.single().after.orEmpty().contains("SELF_REPAIR_CONTRACT"))
    }

    @Test
    fun handleReturnsNullWhenEveryPriorityFileIsStamped() {
        val root = Files.createTempDirectory("self-repair-stamped").toFile()
        root.resolve("ChatWorkspace.kt").writeText(
            "package com.codingagent.agent\nclass ChatWorkspace\n// SELF_REPAIR_CONTRACT: agent-reviewed\n"
        )
        val workspace = ProjectWorkspace(root)
        val files = ProjectFileService(workspace)
        val mutations = MutationCoordinator(workspace)
        val plan = AgentPlan("improve yourself", listOf(AgentStep("repair", "stage")), emptyList())
        val task = SelfRepair.handle("t3", "improve yourself", plan, workspace, files, mutations)
        assertNull(task)
        assertTrue(mutations.pending().isEmpty())
    }
}
