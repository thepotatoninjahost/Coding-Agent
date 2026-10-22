package com.codingagent.agent

/**
 * ONE JOB: Notice when the helper is repeating itself and should stop.
 */
object StuckDetector {
    data class Step(val action: String, val observation: String, val error: Boolean, val textOnly: Boolean)

    fun check(steps: List<Step>): String? {
        if (steps.size >= 3 && steps.takeLast(3).all { it.textOnly }) {
            return "The helper wrote three replies in a row without doing anything."
        }
        val recent = steps.takeLast(6)
        if (recent.size >= 6 && recent.map { it.action }.let { actions ->
                actions[0] == actions[2] && actions[2] == actions[4] &&
                    actions[1] == actions[3] && actions[3] == actions[5] &&
                    actions[0] != actions[1]
            }
        ) {
            return "The helper is switching between the same two steps."
        }
        val last = steps.lastOrNull() ?: return null
        if (!last.textOnly && last.error) {
            val sameError = steps.takeLast(3)
            if (sameError.size == 3 && sameError.all { it.action == last.action && it.error && it.observation == last.observation }) {
                return "The same step failed three times with the same error."
            }
        }
        if (!last.textOnly) {
            val same = steps.takeLast(4)
            if (same.size == 4 && same.all { it.action == last.action && it.observation == last.observation }) {
                return "The same step was repeated and the result did not change."
            }
        }
        return null
    }

    fun actionKey(name: String, arguments: String): String {
        val path = Regex(""""path"\s*:\s*"([^"]+)"""").find(arguments)?.groupValues?.getOrNull(1).orEmpty()
        val query = Regex(""""query"\s*:\s*"([^"]+)"""").find(arguments)?.groupValues?.getOrNull(1).orEmpty()
        val command = Regex(""""command"\s*:\s*"([^"]+)"""").find(arguments)?.groupValues?.getOrNull(1).orEmpty()
        return listOf(name, path, query, command).joinToString("|")
    }
}
