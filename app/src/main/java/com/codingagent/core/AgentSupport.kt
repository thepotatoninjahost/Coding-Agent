package com.codingagent.core

import java.io.File

interface AgentKnowledge {
    fun search(query: String, limit: Int = 6): List<KnowledgeHit>
}

data class AgentStep(val phase: String, val detail: String)
data class AgentPlan(
    val request: String,
    val steps: List<AgentStep>,
    val checks: List<List<String>>,
    val contract: GoalContract? = null
)

class AgentPlanner(private val workspace: ProjectWorkspace) {
    fun plan(request: String): AgentPlan = plan(TaskIntakeParser(workspace.projectRoot()).parse(request))

    fun plan(intake: TaskIntake): AgentPlan {
        val contract = intake.contract
        val steps = buildList {
            add(AgentStep("intake", "Interpret request as ${contract.intent.name.lowercase()} with ${contract.confidence}% confidence"))
            add(AgentStep("understand", "Index repository files, symbols, imports, and checksums"))
            add(AgentStep("research", "Search local coding knowledge and prior lessons for the goal"))
            if (contract.targetPaths.isNotEmpty()) add(AgentStep("target", "Resolve target paths: ${contract.targetPaths.joinToString()}"))
            if (contract.targetSymbols.isNotEmpty()) add(AgentStep("scope", "Resolve target symbols: ${contract.targetSymbols.joinToString()}"))
            add(AgentStep("constraints", if (contract.constraints.isEmpty()) "No explicit constraints" else contract.constraints.joinToString("; ")))
            if (intake.operation.kind != OperationKind.NONE) add(AgentStep("change", contract.goal))
            else add(AgentStep("inspect", contract.goal))
            add(AgentStep("acceptance", contract.acceptanceCriteria.joinToString("; ")))
            add(AgentStep("verify", if (intake.verificationCommands.isEmpty()) "Run static verification" else "Run static verification and detected project checks"))
            add(AgentStep("learn", "Persist the interpreted contract, outcome, and evidence for later tasks"))
        }
        return AgentPlan(intake.originalRequest, steps, intake.verificationCommands, contract)
    }
}

class AgentJournal(private val root: File) {
    private val file = root.resolve(".coding-agent/tasks.tsv")

    @Synchronized
    fun record(task: AgentTask) {
        file.parentFile?.mkdirs()
        val line = listOf(task.id, task.status, task.request, task.changes.size, task.verification.passed, task.summary, task.events.joinToString(" | "))
            .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
        file.appendText(line + "\n")
    }

    fun recent(limit: Int = 20): List<String> = if (!file.isFile) emptyList() else file.readLines().asReversed().take(limit)
}
