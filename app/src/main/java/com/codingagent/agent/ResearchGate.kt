package com.codingagent.agent

import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Decide whether this user line should auto-run blocking web research.
 * History / agent copy is ignored. research_web remains a model tool either way.
 */
object ResearchGate {
    fun shouldAutoResearch(focus: String, intake: TaskIntake): Boolean {
        val lower = focus.lowercase()
        if (Regex("""\\b(research|look up|search the web|google|web search|docs online)\\b""").containsMatchIn(lower)) {
            return true
        }
        if (intake.intent == TaskIntent.EXPLAIN &&
            Regex("""\\b(look into|investigate|documentation)\\b""").containsMatchIn(lower)
        ) {
            return true
        }
        return false
    }
}
