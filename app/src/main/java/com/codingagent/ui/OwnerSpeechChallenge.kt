package com.codingagent.ui

import java.security.SecureRandom

/**
 * ONE JOB: Generate one-time spoken challenge phrases for owner approval.
 */
internal object OwnerSpeechChallenge {
    private val random = SecureRandom()
    private val words = listOf(
        "maple", "harbor", "silver", "copper", "meadow", "velvet", "planet", "window",
        "garden", "rocket", "island", "pencil", "candle", "forest", "button", "bridge",
        "rabbit", "sunset", "pocket", "basket", "thunder", "marble", "orange", "coffee",
        "helmet", "tunnel", "winter", "market", "yellow", "camera", "pillow", "ocean"
    )

    fun next(previous: String? = null): String {
        var candidate: String
        do {
            val available = words.toMutableList()
            val selected = mutableListOf<String>()
            repeat(4) {
                selected += available.removeAt(random.nextInt(available.size))
            }
            candidate = selected.joinToString(" ")
        } while (candidate == previous)
        return candidate
    }
}
