package com.codingagent.agent

/**
 * ONE JOB: Keep one task from using the owner's whole daily free allowance.
 */
object TaskBudget {
    const val PER_TASK = 8

    fun callsFor(stepLimit: Int, dailyRemaining: Int?): Int {
        val cap = when {
            dailyRemaining == null -> PER_TASK
            dailyRemaining <= 2 -> 1
            else -> minOf(PER_TASK, maxOf(2, dailyRemaining / 6))
        }
        return minOf(stepLimit.coerceAtLeast(1), cap)
    }

    fun warning(dailyRemaining: Int?, calls: Int): String? {
        if (dailyRemaining == null || dailyRemaining > 15) return null
        return "$dailyRemaining free requests left today. This task will use at most $calls."
    }
}
