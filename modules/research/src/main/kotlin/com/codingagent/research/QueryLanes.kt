package com.codingagent.research

object QueryLanes {
    private val howTo = Regex(
        "\\b(how|create|creating|build|building|implement|custom|ui|interface|layout|compose|widget|view)\\b",
        RegexOption.IGNORE_CASE
    )

    fun expand(query: String, mode: ResearchMode = ResearchMode.BROAD, salt: String = ""): List<ResearchLane> {
        val base = query.trim()
        val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
        val isShort = words.size <= 4 || base.length < 36
        val isHowTo = howTo.containsMatchIn(base)

        val core = mutableListOf(
            ResearchLane("primary documentation", "$base documentation OR guide OR tutorial OR official docs"),
            ResearchLane("implementation examples", "$base code example OR snippet OR implementation OR sample"),
            ResearchLane("community solutions", "$base site:stackoverflow.com OR site:github.com")
        )

        if (isHowTo) {
            core.add(0, ResearchLane("howto coding", "$base android OR ios OR jetpack compose OR swiftui OR react native"))
            core.add(1, ResearchLane("ui implementation", "$base custom view OR custom component OR UI toolkit"))
            val rotated = if (salt.isBlank()) core else {
                val shift = (salt.hashCode().and(0x7fffffff)) % core.size
                core.drop(shift) + core.take(shift)
            }
            return rotated
        }

        when (mode) {
            ResearchMode.EXPERIMENTAL -> {
                core.add(0, ResearchLane("experimental focus", "$base experimental OR prototype OR novel OR research code"))
                core.add(1, ResearchLane("github experimental", "$base experimental OR prototype site:github.com"))
            }
            ResearchMode.THEORETICAL -> {
                core.add(0, ResearchLane("theory focus", "$base theory OR formal OR model OR foundations"))
            }
            ResearchMode.EMPIRICAL -> {
                core.add(0, ResearchLane("empirical focus", "$base benchmark OR performance OR evaluation OR measure"))
            }
            ResearchMode.BROAD -> {}
        }

        if (!isShort) {
            core += ResearchLane("theoretical foundations", "$base theory OR formal model OR foundations OR hypothesis")
            core += ResearchLane("experimental research", "$base experimental OR prototype OR novel OR unconventional")
            core += ResearchLane("empirical evidence", "$base benchmark OR evaluation OR performance OR comparison OR measure")
            core += ResearchLane("standards and papers", "$base RFC OR specification OR paper OR standard")
            core += ResearchLane("failure modes", "$base pitfalls OR bugs OR failure OR limitations OR common mistakes")
            core += ResearchLane("alternatives and criticism", "$base alternatives OR criticism OR tradeoffs OR vs OR compared")
        } else {
            core += ResearchLane("code search", "$base programming OR library OR framework OR api")
            core += ResearchLane("github code", "$base site:github.com")
            if (mode == ResearchMode.EXPERIMENTAL) {
                core += ResearchLane("android kotlin experimental", "$base android kotlin experimental site:github.com")
            }
        }

        val rotated = if (salt.isBlank()) core else {
            val shift = (salt.hashCode().and(0x7fffffff)) % core.size
            core.drop(shift) + core.take(shift)
        }
        return rotated
    }

    fun focusedFallbacks(query: String): List<String> = listOf(
        "$query site:github.com",
        "$query site:stackoverflow.com",
        "$query site:developer.android.com",
        "$query site:developer.apple.com",
        "$query jetpack compose OR swiftui OR react native UI",
        "$query custom view OR custom component code example"
    )
}

data class ResearchLane(val name: String, val query: String)
