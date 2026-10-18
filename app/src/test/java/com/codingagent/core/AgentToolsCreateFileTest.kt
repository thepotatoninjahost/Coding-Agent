package com.codingagent.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.ProjectFileService
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.MutationProposeResult
import com.codingagent.workspace.ChangeOperation

class AgentToolsCreateFileTest {
    @Test
    fun proposeCreateFileWhenTargetMissing() {
        val root = Files.createTempDirectory("agenttools-create").toFile()
        // ensure empty project
        val workspace = ProjectWorkspace(root)
        val service = ProjectFileService(workspace)
        val coordinator = MutationCoordinator(workspace)

        val result = service.save("src/NewThing.kt", "package com.codingagent\n\nobject NewThing {}\n", coordinator)
        assertTrue("Expected proposal (create) but got: $result", result is MutationProposeResult.Proposed)
        val proposal = (result as MutationProposeResult.Proposed).proposal
        // confirm at least one change is CREATE
        assertTrue(proposal.changeSet.changes.any { it.operation == ChangeOperation.CREATE })
    }
}
