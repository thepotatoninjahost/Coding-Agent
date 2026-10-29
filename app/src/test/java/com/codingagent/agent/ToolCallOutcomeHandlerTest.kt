package com.codingagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.codingagent.workspace.AgentPlan
import com.codingagent.model.ModelResponse
import com.codingagent.workspace.AgentStep
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.MutationCoordinator

class ToolCallOutcomeHandlerTest {
    @Test
    fun runCommandRequiresZeroExitAndNoTimeout() {
        assertTrue(toolExecutionSucceeded("run_command", "exit=0 timeout=false\noutput"))
        assertFalse(toolExecutionSucceeded("run_command", "exit=3 timeout=false\nerror"))
        assertFalse(toolExecutionSucceeded("run_command", "exit=0 timeout=true\noutput"))
        assertFalse(toolExecutionSucceeded("run_command", "ERROR: command failed"))
    }

    @Test
    fun verifyRequiresExplicitPassedTrue() {
        assertTrue(toolExecutionSucceeded("verify", "passed=true\n"))
        assertTrue(toolExecutionSucceeded("verify", "passed=true\nissues=0"))
        assertFalse(toolExecutionSucceeded("verify", "passed=false\nissue found"))
        assertFalse(toolExecutionSucceeded("verify", "passed=false"))
        assertFalse(toolExecutionSucceeded("verify", "ERROR: verification failed"))
    }


    @Test
    fun cancellationAfterToolReturnsStopsBeforeEvidenceOrCompletion() {
        val root = Files.createTempDirectory("tool-cancel-race").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val mutations = MutationCoordinator(workspace)
        val plan = AgentPlan(
            "inspect Main.kt",
            listOf(
                AgentStep("understand", "understand project"),
                AgentStep("inspect", "inspect target")
            ),
            emptyList()
        )
        val planning = PlanningLoop(plan)
        planning.completePhase("understand")
        val toolPlan = ToolSelectionPlan(
            "inspect Main.kt",
            listOf(
                ToolInvocation(
                    "1-search",
                    ToolKind.SEARCH_PROJECT,
                    "search project",
                    status = ToolStepStatus.COMPLETE
                )
            ),
            "test"
        )
        val selection = ToolSelectionLoop(toolPlan)
        var cancelled = false
        val events = mutableListOf<AutonomousAgentEvent>()
        val handler = ToolCallOutcomeHandler(
            config = AutonomousAgentConfig(),
            workspace = workspace,
            mutations = mutations,
            planningLoop = planning,
            toolSelectionLoop = selection,
            executeTool = { _, _ ->
                cancelled = true
                "fun main() = 1\\n"
            },
            isListingRequest = { false },
            isSourceFileListRequest = { false },
            currentRequestFocus = { "inspect Main.kt" },
            extractExplicitReadPath = { "Main.kt" },
            formatListingSummary = { _, _, _ -> "" },
            synthesizeFromEvidence = { _, _, _ -> "" },
            recordTask = {},
            emit = { events += it },
            isCancelled = { cancelled }
        )

        val outcome = handler.handle(
            response = ModelResponse.ToolCall("read_file", """{"path":"Main.kt"}"""),
            state = ToolTurnState(),
            writeNow = false,
            changeWork = false,
            decision = LoopDecision(true, false, false),
            taskId = "task-1",
            normalized = "inspect Main.kt",
            plan = plan,
            transcript = mutableListOf(),
            changes = { emptyList() }
        )

        assertTrue(outcome === ToolTurnOutcome.Stop)
        assertTrue(events.none { it is AutonomousAgentEvent.ToolFinished })
    }

    @Test
    fun alreadyInASentenceDoesNotFinishAfterOneRead() {
        val events = mutableListOf<AutonomousAgentEvent>()
        val outcome = readHandler(
            focus = "Explain what is already implemented in Main.kt",
            explicitPath = null,
            events = events
        ).handle(
            response = ModelResponse.ToolCall("read_file", """{"path":"Main.kt"}"""),
            state = ToolTurnState(),
            writeNow = false,
            changeWork = false,
            decision = LoopDecision(true, false, false),
            taskId = "task-1",
            normalized = "Explain what is already implemented in Main.kt",
            plan = readPlan(),
            transcript = mutableListOf(),
            changes = { emptyList() }
        )
        assertTrue(outcome === ToolTurnOutcome.Continue)
        assertTrue(events.none { it is AutonomousAgentEvent.Completed })
    }

    @Test
    fun explicitReadFinishesWithTheFileText() {
        val events = mutableListOf<AutonomousAgentEvent>()
        val outcome = readHandler(
            focus = "read Main.kt",
            explicitPath = "Main.kt",
            events = events
        ).handle(
            response = ModelResponse.ToolCall("read_file", """{"path":"Main.kt"}"""),
            state = ToolTurnState(),
            writeNow = false,
            changeWork = false,
            decision = LoopDecision(true, false, false),
            taskId = "task-1",
            normalized = "read Main.kt",
            plan = readPlan(),
            transcript = mutableListOf(),
            changes = { emptyList() }
        )
        assertTrue(outcome === ToolTurnOutcome.Stop)
        val completed = events.filterIsInstance<AutonomousAgentEvent.Completed>().single()
        assertTrue(completed.task.summary.contains("fun main() = 1"))
        assertFalse(completed.task.summary.contains("FILE_PATH="))
    }

    private fun readPlan() = AgentPlan(
        "read Main.kt",
        listOf(AgentStep("understand", "understand project"), AgentStep("inspect", "inspect target")),
        emptyList()
    )

    private fun readHandler(
        focus: String,
        explicitPath: String?,
        events: MutableList<AutonomousAgentEvent>
    ): ToolCallOutcomeHandler {
        val root = Files.createTempDirectory("tool-read-intent").toFile()
        root.resolve("Main.kt").writeText("fun main() = 1\n")
        val workspace = ProjectWorkspace(root)
        val plan = readPlan()
        val planning = PlanningLoop(plan)
        planning.completePhase("understand")
        val selection = ToolSelectionLoop(
            ToolSelectionPlan(
                focus,
                listOf(
                    ToolInvocation(
                        "1-search",
                        ToolKind.SEARCH_PROJECT,
                        "search project",
                        status = ToolStepStatus.COMPLETE
                    )
                ),
                "test"
            )
        )
        return ToolCallOutcomeHandler(
            config = AutonomousAgentConfig(),
            workspace = workspace,
            mutations = MutationCoordinator(workspace),
            planningLoop = planning,
            toolSelectionLoop = selection,
            executeTool = { _, _ ->
                "FILE_PATH=Main.kt\nSTART_CHARACTER=0\nNEXT_START_CHARACTER=16\nEND_OF_FILE=true\nCONTENT_START\nfun main() = 1\nCONTENT_END"
            },
            isListingRequest = { false },
            isSourceFileListRequest = { false },
            currentRequestFocus = { focus },
            extractExplicitReadPath = { explicitPath },
            formatListingSummary = { _, _, _ -> "" },
            synthesizeFromEvidence = { _, _, _ -> "" },
            recordTask = {},
            emit = { events += it },
            isCancelled = { false }
        )
    }

    @Test
    fun otherToolsRetainErrorPrefixSemantics() {
        assertTrue(toolExecutionSucceeded("read_file", "file contents"))
        assertFalse(toolExecutionSucceeded("read_file", "ERROR: file not found"))
    }
}
