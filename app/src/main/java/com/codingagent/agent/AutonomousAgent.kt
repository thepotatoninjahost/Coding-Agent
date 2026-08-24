package com.codingagent.agent

import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.codingagent.intake.CodeSynthesisEngine
import com.codingagent.intake.GoalInterpreter
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.knowledge.DocumentIngester
import com.codingagent.knowledge.KnowledgeBase
import com.codingagent.model.AgentModelClient
import com.codingagent.model.LiveModelStore
import com.codingagent.model.ModelGateway
import com.codingagent.model.ModelSettings
import com.codingagent.research.DeepResearch
import com.codingagent.research.PersonalResearchProvider
import com.codingagent.research.WebResearch
import com.codingagent.workspace.MutationCoordinator
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.StorageGuard
import com.codingagent.workspace.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single agent spine. All autonomous work, offline mutations, chat, research, and synthesis
 * route through this class. Dual-runtime (CodingAgentRuntime / AgentOrchestrator) was removed.
 */
class AutonomousAgent(
    private val workspace: ProjectWorkspace,
    private val modelClient: AgentModelClient,
    private val modelSettings: ModelSettings,
    private val liveModelStore: LiveModelStore,
    private val knowledgeBase: KnowledgeBase,
    private val mutationCoordinator: MutationCoordinator,
    private val storageGuard: StorageGuard,
    private val terminalSession: TerminalSession,
    private val documentIngester: DocumentIngester,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val running = AtomicBoolean(false)
    private var currentJob: Job? = null

    private val intakeParser = TaskIntakeParser()
    private val goalInterpreter = GoalInterpreter()
    private val codeSynthesis = CodeSynthesisEngine(modelClient)
    private val planningLoop = PlanningLoop(modelClient)
    private val repairCycle = RepairCycle(modelClient)
    private val toolSelection = ToolSelection()
    private val selfEvolution = SelfEvolution()
    private val chatWorkspace = ChatWorkspace(modelClient, workspace)
    private val deepResearch = DeepResearch()
    private val personalResearch = PersonalResearchProvider()
    private val webResearch = WebResearch()

    sealed class AgentEvent {
        data class Status(val message: String) : AgentEvent()
        data class Thought(val text: String) : AgentEvent()
        data class ToolCall(val name: String, val args: String) : AgentEvent()
        data class ToolResult(val name: String, val result: String) : AgentEvent()
        data class Proposal(val id: String, val summary: String, val diff: String) : AgentEvent()
        data class ApprovalRequired(val proposalId: String, val summary: String) : AgentEvent()
        data class Completed(val summary: String) : AgentEvent()
        data class Failed(val error: String) : AgentEvent()
        data class Log(val line: String) : AgentEvent()
    }

    fun isRunning(): Boolean = running.get()

    fun cancel() {
        currentJob?.cancel()
        running.set(false)
        scope.launch { _events.emit(AgentEvent.Status("Cancelled")) }
    }

    fun dispose() {
        cancel()
        scope.cancel()
    }

    /**
     * Primary entry: natural-language goal. Parses intake, plans, executes with approval gates.
     */
    fun runGoal(goalText: String) {
        if (!running.compareAndSet(false, true)) {
            scope.launch { _events.emit(AgentEvent.Failed("Agent already running")) }
            return
        }
        currentJob = scope.launch {
            try {
                _events.emit(AgentEvent.Status("Parsing goal"))
                val intake = intakeParser.parse(goalText)
                val goal = goalInterpreter.interpret(intake)
                _events.emit(AgentEvent.Thought("Interpreted goal: ${goal.summary}"))

                val plan = planningLoop.plan(goal, workspace.snapshot())
                _events.emit(AgentEvent.Thought("Plan steps: ${plan.steps.size}"))

                for ((index, step) in plan.steps.withIndex()) {
                    _events.emit(AgentEvent.Status("Step ${index + 1}/${plan.steps.size}: ${step.description}"))
                    val result = executeStep(step)
                    if (!result.success) {
                        val repaired = repairCycle.attempt(step, result, workspace.snapshot())
                        if (repaired == null || !repaired.success) {
                            _events.emit(AgentEvent.Failed("Step failed: ${result.message}"))
                            return@launch
                        }
                    }
                }
                _events.emit(AgentEvent.Completed("Goal completed"))
            } catch (e: Exception) {
                _events.emit(AgentEvent.Failed(e.message ?: "Unknown error"))
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * Offline explicit mutation: apply a concrete file edit without model loop when possible.
     */
    fun applyExplicitMutation(
        path: String,
        newContent: String,
        reason: String,
        requireDoubleConfirm: Boolean = true,
    ) {
        if (!running.compareAndSet(false, true)) {
            scope.launch { _events.emit(AgentEvent.Failed("Agent already running")) }
            return
        }
        currentJob = scope.launch {
            try {
                val proposalId = UUID.randomUUID().toString()
                val diff = workspace.computeDiff(path, newContent)
                _events.emit(AgentEvent.Proposal(proposalId, reason, diff))
                if (requireDoubleConfirm) {
                    _events.emit(AgentEvent.ApprovalRequired(proposalId, reason))
                    // Caller must call confirmProposal twice; for offline path we emit and wait via external confirm
                    _events.emit(AgentEvent.Status("Awaiting double confirmation for explicit mutation"))
                } else {
                    mutationCoordinator.apply(path, newContent)
                    _events.emit(AgentEvent.Completed("Applied mutation to $path"))
                }
            } catch (e: Exception) {
                _events.emit(AgentEvent.Failed(e.message ?: "Mutation failed"))
            } finally {
                if (!requireDoubleConfirm) running.set(false)
            }
        }
    }

    fun confirmProposal(proposalId: String, confirmed: Boolean) {
        scope.launch {
            if (!confirmed) {
                _events.emit(AgentEvent.Status("Proposal $proposalId rejected"))
                running.set(false)
                return@launch
            }
            // Double-confirm gate is tracked by UI / caller; second confirm applies
            _events.emit(AgentEvent.Status("Proposal $proposalId confirmed (apply on second confirm)"))
        }
    }

    fun applyConfirmedProposal(proposalId: String, path: String, content: String) {
        scope.launch {
            try {
                mutationCoordinator.apply(path, content)
                _events.emit(AgentEvent.Completed("Applied confirmed proposal $proposalId to $path"))
            } catch (e: Exception) {
                _events.emit(AgentEvent.Failed(e.message ?: "Apply failed"))
            } finally {
                running.set(false)
            }
        }
    }

    fun chat(message: String) {
        scope.launch {
            try {
                _events.emit(AgentEvent.Status("Chat"))
                val reply = chatWorkspace.turn(message)
                _events.emit(AgentEvent.Thought(reply))
                _events.emit(AgentEvent.Completed("Chat turn done"))
            } catch (e: Exception) {
                _events.emit(AgentEvent.Failed(e.message ?: "Chat failed"))
            }
        }
    }

    fun runResearch(query: String, mode: ResearchMode = ResearchMode.BROAD) {
        if (!running.compareAndSet(false, true)) {
            scope.launch { _events.emit(AgentEvent.Failed("Agent already running")) }
            return
        }
        currentJob = scope.launch {
            try {
                _events.emit(AgentEvent.Status("Research: $mode"))
                val brief = when (mode) {
                    ResearchMode.BROAD -> personalResearch.research(query)
                    ResearchMode.DEEP, ResearchMode.CODE -> deepResearch.research(query)
                }
                _events.emit(AgentEvent.Thought(brief.toSummary()))
                _events.emit(AgentEvent.Completed("Research complete"))
            } catch (e: Exception) {
                _events.emit(AgentEvent.Failed(e.message ?: "Research failed"))
            } finally {
                running.set(false)
            }
        }
    }

    enum class ResearchMode { BROAD, DEEP, CODE }

    private suspend fun executeStep(step: PlanningLoop.Step): AgentStepResult = withContext(Dispatchers.Default) {
        when (step.kind) {
            PlanningLoop.StepKind.READ -> {
                val content = workspace.readFile(step.targetPath ?: return@withContext AgentStepResult(false, "No path"))
                AgentStepResult(true, "Read ${step.targetPath}", content)
            }
            PlanningLoop.StepKind.WRITE -> {
                val proposalId = UUID.randomUUID().toString()
                val content = step.content ?: return@withContext AgentStepResult(false, "No content")
                val path = step.targetPath ?: return@withContext AgentStepResult(false, "No path")
                val diff = workspace.computeDiff(path, content)
                _events.emit(AgentEvent.Proposal(proposalId, step.description, diff))
                _events.emit(AgentEvent.ApprovalRequired(proposalId, step.description))
                AgentStepResult(true, "Proposal emitted for approval")
            }
            PlanningLoop.StepKind.SHELL -> {
                val out = terminalSession.run(step.command ?: return@withContext AgentStepResult(false, "No command"))
                AgentStepResult(out.exitCode == 0, out.stdout + out.stderr)
            }
            PlanningLoop.StepKind.RESEARCH -> {
                val q = step.query ?: return@withContext AgentStepResult(false, "No query")
                val brief = deepResearch.research(q)
                AgentStepResult(true, brief.toSummary())
            }
            PlanningLoop.StepKind.SYNTHESIZE -> {
                val code = codeSynthesis.synthesize(step.prompt ?: "", workspace.snapshot())
                AgentStepResult(true, "Synthesized", code)
            }
            else -> AgentStepResult(false, "Unknown step kind")
        }
    }
}

// --- Supporting types kept co-located for the single spine ---

data class AgentStepResult(
    val success: Boolean,
    val message: String = "",
    val data: Any? = null,
)
