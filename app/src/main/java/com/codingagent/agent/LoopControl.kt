package com.codingagent.agent

import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Govern the model loop's transition from evidence gathering to execution.
 *
 * The model remains responsible for choosing the concrete tool, but this gate controls
 * when the production path stops gathering and is required to act on sufficient evidence.
 */
data class LoopDecision(
    val toolsOpen: Boolean,
    val demandWrite: Boolean,
    val synthesizeFromEvidence: Boolean
)

object LoopControl {
    fun decide(
        turn: Int,
        maxTurns: Int,
        usefulGathers: Int,
        writeRefusals: Int,
        intent: TaskIntent,
        wholeProjectReview: Boolean
    ): LoopDecision {
        val changeWork = intent in setOf(
            TaskIntent.CHANGE,
            TaskIntent.CREATE,
            TaskIntent.REFACTOR,
            TaskIntent.DEBUG
        )

        if (!changeWork) {
            return LoopDecision(
                toolsOpen = true,
                demandWrite = false,
                synthesizeFromEvidence = false
            )
        }

        val minimumEvidence = when (intent) {
            TaskIntent.DEBUG, TaskIntent.REFACTOR -> 2
            else -> 1
        }
        val evidenceReady = usefulGathers >= minimumEvidence
        val forcedByRefusal = writeRefusals >= 2
        val lateTurn = turn >= (maxTurns - 2).coerceAtLeast(1)
        val shouldWrite = evidenceReady || forcedByRefusal || lateTurn

        if (wholeProjectReview && !forcedByRefusal && !lateTurn && usefulGathers < 3) {
            return LoopDecision(
                toolsOpen = true,
                demandWrite = false,
                synthesizeFromEvidence = false
            )
        }

        return LoopDecision(
            toolsOpen = !shouldWrite,
            demandWrite = shouldWrite,
            synthesizeFromEvidence = shouldWrite && evidenceReady
        )
    }
}
