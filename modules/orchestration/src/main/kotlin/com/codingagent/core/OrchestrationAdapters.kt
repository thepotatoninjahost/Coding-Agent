package com.codingagent.core

import com.codingagent.knowledge.KnowledgeHit
import com.codingagent.intake.GoalContract
import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntakeParser
import com.codingagent.orchestration.PlannedStep
import com.codingagent.orchestration.PlanningLoop as ModulePlanningLoop
import com.codingagent.orchestration.PlanStepStatus
import com.codingagent.orchestration.PlanSnapshot
import com.codingagent.orchestration.SynthesisProposal as ModuleSynthesisProposal
import com.codingagent.orchestration.SynthesisResult as ModuleSynthesisResult
import com.codingagent.orchestration.ToolInvocation
import com.codingagent.orchestration.ToolKind
import com.codingagent.orchestration.ToolLoopSnapshot
import com.codingagent.orchestration.ToolSelectionLoop as ModuleToolSelectionLoop
import com.codingagent.orchestration.ToolSelectionPlan
import com.codingagent.orchestration.ToolSelector as ModuleToolSelector
import com.codingagent.orchestration.ToolStepStatus
import com.codingagent.orchestration.WorkflowPlanner
import java.io.File

private fun ModuleSynthesisProposal.toCore(): SynthesisProposal = SynthesisProposal(goal, operations, rationale, knowledgeUsed.map { com.codingagent.knowledge.KnowledgeHit(it.document, it.section, 0, it.excerpt) })

sealed class SynthesisResult {
    data class Ready(val proposal: SynthesisProposal) : SynthesisResult()
    data class NeedsInput(val question: String) : SynthesisResult()
}

data class SynthesisProposal(val goal: String, val operations: List<com.codingagent.domain.TaskOperation>, val rationale: String, val knowledgeUsed: List<KnowledgeHit>)

class CodeSynthesisEngine(private val root: File, private val knowledge: AgentKnowledge) {
    private val delegate = com.codingagent.orchestration.CodeSynthesisEngine(root, object : com.codingagent.knowledge.KnowledgeProvider {
        override fun search(query: String, limit: Int) = knowledge.search(query, limit).map { com.codingagent.knowledge.KnowledgeHit(it.document, it.section, 0, it.excerpt) }
    })
    fun synthesize(intake: TaskIntake): SynthesisResult = when (val result = delegate.synthesize(intake)) {
        is ModuleSynthesisResult.Ready -> SynthesisResult.Ready(result.proposal.toCore())
        is ModuleSynthesisResult.NeedsInput -> SynthesisResult.NeedsInput(result.question)
    }
}

data class AgentStep(val phase: String, val detail: String)
data class AgentPlan(val request: String, val steps: List<AgentStep>, val checks: List<List<String>>, val contract: GoalContract? = null)

class AgentPlanner(private val workspace: ProjectWorkspace) {
    private val delegate = WorkflowPlanner()
    fun plan(request: String): AgentPlan = plan(TaskIntakeParser(workspace.projectRoot()).parse(request))
    fun plan(intake: TaskIntake): AgentPlan = delegate.plan(intake).let { AgentPlan(it.request, it.steps.map { step -> AgentStep(step.phase, step.detail) }, it.checks, it.contract) }
}

class PlanningLoop(plan: AgentPlan, maxIterations: Int = 32, maxReplans: Int = 3) {
    private val delegate = ModulePlanningLoop(plan.steps.map { it.phase to it.detail }, maxIterations, maxReplans)
    fun next(): PlannedStep? = delegate.next()
    fun complete(evidence: String = "") = delegate.complete(evidence)
    fun fail(message: String, replan: Boolean = true) = delegate.fail(message, replan)
    fun finishIfReady() = delegate.finishIfReady()
    fun currentStatus() = delegate.currentStatus()
    fun history(): List<PlanSnapshot> = delegate.history()
    fun currentSteps(): List<PlannedStep> = delegate.currentSteps()
}

data class ToolSelectionPlanCompat(val request: String, val tools: List<ToolInvocation>, val rationale: String)
class ToolSelector {
    fun select(intake: TaskIntake): ToolSelectionPlan = ModuleToolSelector().select(intake)
}
class ToolSelectionLoop(plan: ToolSelectionPlan, maxIterations: Int = 32) {
    private val delegate = ModuleToolSelectionLoop(plan, maxIterations)
    fun next() = delegate.next()
    fun complete(evidence: String = "") = delegate.complete(evidence)
    fun fail(message: String) = delegate.fail(message)
    fun currentStatus() = delegate.currentStatus()
    fun currentTools() = delegate.currentTools()
    fun history(): List<ToolLoopSnapshot> = delegate.history()
    fun activeTool() = delegate.activeTool()
    fun isComplete() = delegate.isComplete()
    fun blockPending(reason: String) = delegate.blockPending(reason)
}

typealias ToolKind = ToolKind
typealias ToolStepStatus = ToolStepStatus
typealias ToolInvocation = ToolInvocation
typealias ToolLoopSnapshot = ToolLoopSnapshot
