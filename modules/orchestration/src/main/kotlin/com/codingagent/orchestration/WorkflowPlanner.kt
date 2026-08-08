package com.codingagent.orchestration

import com.codingagent.domain.OperationKind
import com.codingagent.intake.GoalContract
import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntakeParser
import java.io.File

data class WorkflowStep(val phase: String, val detail: String)
data class WorkflowPlan(val request: String, val steps: List<WorkflowStep>, val checks: List<List<String>>, val contract: GoalContract? = null)

class WorkflowPlanner {
    fun plan(request: String, projectRoot: File): WorkflowPlan = plan(TaskIntakeParser(projectRoot).parse(request))
    fun plan(intake: TaskIntake): WorkflowPlan {
        val contract = intake.contract
        val steps = buildList {
            add(WorkflowStep("intake", "Interpret request as ${contract.intent.name.lowercase()} with ${contract.confidence}% confidence"))
            add(WorkflowStep("understand", "Index repository files, symbols, imports, and checksums"))
            add(WorkflowStep("research", "Search local coding knowledge and prior lessons for the goal"))
            if (contract.targetPaths.isNotEmpty()) add(WorkflowStep("target", "Resolve target paths: ${contract.targetPaths.joinToString()}"))
            if (contract.targetSymbols.isNotEmpty()) add(WorkflowStep("scope", "Resolve target symbols: ${contract.targetSymbols.joinToString()}"))
            add(WorkflowStep("constraints", contract.constraints.ifEmpty { listOf("No explicit constraints") }.joinToString("; ")))
            add(WorkflowStep(if (intake.operation.kind != OperationKind.NONE) "change" else "inspect", contract.goal))
            add(WorkflowStep("acceptance", contract.acceptanceCriteria.joinToString("; ")))
            add(WorkflowStep("verify", if (intake.verificationCommands.isEmpty()) "Run static verification" else "Run static verification and detected project checks"))
            add(WorkflowStep("learn", "Persist the interpreted contract, outcome, and evidence for later tasks"))
        }
        return WorkflowPlan(intake.originalRequest, steps, intake.verificationCommands, contract)
    }
}
