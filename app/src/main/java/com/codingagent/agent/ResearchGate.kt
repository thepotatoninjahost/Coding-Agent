package com.codingagent.agent

import com.codingagent.intake.TaskIntake
import com.codingagent.intake.TaskIntent

/**
 * ONE JOB: Decide whether this request needs a web research pass.
 */
object ResearchGate {
    /**
     * Explicit research/current-documentation requests must not be answered from a model's
     * unsupported prior knowledge when retrieval fails. Debug requests may still use local
     * evidence when optional web research is unavailable.
     */
    fun requiresUsableEvidence(focus: String, intake: TaskIntake): Boolean {
        val lower = focus.lowercase()
        if (Regex("""\b(research|look up|search the web|google|web search|docs online)\b""")
                .containsMatchIn(lower)
        ) return true

        if (intake.intent == TaskIntent.EXPLAIN &&
            Regex("""\b(look into|investigate|documentation)\b""").containsMatchIn(lower)
        ) return true

        return Regex("""\b(android|kotlin|gradle|compose|jetpack|retrofit|okhttp|room|hilt|coroutine|material)\b""")
            .containsMatchIn(lower) &&
            Regex("""\b(latest|current|docs|documentation|api|migrate|deprecated)\b""")
                .containsMatchIn(lower)
    }

    fun shouldAutoResearch(focus: String, intake: TaskIntake): Boolean {
        val lower = focus.lowercase()
        if (Regex("""\b(research|look up|search the web|google|web search|docs online)\b""")
                .containsMatchIn(lower)
        ) {
            return true
        }
        if (intake.intent == TaskIntent.EXPLAIN &&
            Regex("""\b(look into|investigate|documentation)\b""").containsMatchIn(lower)
        ) {
            return true
        }
        if (Regex("""\b(android|kotlin|gradle|compose|jetpack|retrofit|okhttp|room|hilt|coroutine|material)\b""")
                .containsMatchIn(lower) &&
            Regex("""\b(how|latest|current|docs|documentation|api|migrate|deprecated|error|exception|crash)\b""")
                .containsMatchIn(lower)
        ) {
            return true
        }
        if (intake.intent == TaskIntent.DEBUG &&
            Regex("""\b(error|exception|stack.?trace|crash|cannot resolve|unresolved|not found)\b""")
                .containsMatchIn(lower)
        ) {
            return true
        }
        return false
    }
}
