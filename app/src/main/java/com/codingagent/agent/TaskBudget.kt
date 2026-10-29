package com.codingagent.agent

/**
 * ONE JOB: Keep one task from using the owner's whole daily free allowance.
 */
object TaskBudget {
    const val PER_TASK = 8
    const val BUILD_CAP = 24

    fun callsFor(stepLimit: Int, dailyRemaining: Int?, changeWork: Boolean = false): Int {
        val cap = when {
            dailyRemaining != null && dailyRemaining <= 2 -> 1
            changeWork && (dailyRemaining == null || dailyRemaining >= 100) ->
                if (dailyRemaining == null) 16 else BUILD_CAP
            changeWork && dailyRemaining != null -> minOf(BUILD_CAP, dailyRemaining, maxOf(6, dailyRemaining / 3))
            dailyRemaining == null -> PER_TASK
            else -> minOf(PER_TASK, maxOf(2, dailyRemaining / 6))
        }
        return minOf(stepLimit.coerceAtLeast(1), cap)
    }

    fun warning(dailyRemaining: Int?, calls: Int): String? {
        if (dailyRemaining == null || dailyRemaining > 15) return null
        return "$dailyRemaining free requests left today. This task will use at most $calls."
    }
}
