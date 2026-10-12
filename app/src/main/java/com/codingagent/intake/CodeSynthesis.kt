package com.codingagent.intake

import java.io.File
import com.codingagent.agent.AgentKnowledge
import com.codingagent.workspace.KnowledgeHit

/**
 * ONE JOB: Turn intake into a deterministic proposal only when the requested operation
 * contains enough information to implement without inventing behavior.
 */
sealed class SynthesisResult {
    data class Ready(val proposal: SynthesisProposal) : SynthesisResult()
    data class NeedsInput(val question: String) : SynthesisResult()
}

data class SynthesisProposal(
    val goal: String,
    val operations: List<TaskOperation>,
    val rationale: String,
    val knowledgeUsed: List<KnowledgeHit>
)

class CodeSynthesisEngine(
    private val root: File,
    private val knowledge: AgentKnowledge
) {
    fun synthesize(intake: TaskIntake): SynthesisResult {
        val evidence = knowledge.search(intake.contract.goal, 6)
        val operation = intake.operation
        if (operation.kind != OperationKind.NONE) {
            return SynthesisResult.Ready(
                SynthesisProposal(
                    goal = intake.contract.goal,
                    operations = listOf(operation),
                    rationale = "Preserved the explicit operation from the task request.",
                    knowledgeUsed = evidence
                )
            )
        }

        if (intake.intent == TaskIntent.CREATE) {
            val path = intake.contract.targetPaths.singleOrNull() ?: defaultCreatePath(intake.contract.goal)
            if (!isSafePath(path)) {
                return SynthesisResult.NeedsInput("Choose a safe project-relative target file.")
            }
            if (root.resolve(path).exists()) {
                return SynthesisResult.NeedsInput("$path already exists. Specify whether to replace it or edit it.")
            }
            return SynthesisResult.NeedsInput(
                "A target path does not define the implementation. Specify the required behavior, inputs, outputs, and expected results. No generic pass-through scaffold was generated."
            )
        }

        return SynthesisResult.NeedsInput("Specify the exact file operation, target file, or requested code behavior.")
    }

    private fun defaultCreatePath(goal: String): String {
        val stop = setOf(
            "create", "make", "build", "write", "add", "new", "a", "an", "the",
            "file", "project", "app", "please", "just", "simple", "my"
        )
        val parts = goal.split(Regex("[^A-Za-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in stop }
            .take(3)
        val className = parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            .ifBlank { "AppCore" }
        return "src/$className.kt"
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith('/') && !path.contains("..") && !path.contains('\\')
}
