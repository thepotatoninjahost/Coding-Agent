package com.codingagent.intake

import com.codingagent.agent.AgentKnowledge
import com.codingagent.workspace.KnowledgeHit

/**
 * ONE JOB: Turn intake into a deterministic synthesis proposal when ops are explicit.
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

class CodeSynthesisEngine(private val knowledge: AgentKnowledge) {
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
            return SynthesisResult.NeedsInput(
                "This create request needs a configured coding model to generate meaningful implementation code. " +
                    "Configure a model, or specify an explicit file operation with the exact content."
            )
        }

        return SynthesisResult.NeedsInput("Specify the exact file operation, target file, or requested code shape.")
    }


}
