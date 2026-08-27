package com.codingagent.agent

/**
 * ONE JOB: Classify the current request line (list / greet / status / review / meta / paths).
 */
object AgentRequestKind {
    fun isListing(request: String): Boolean {
        val t = request.lowercase().trim()
        if (Regex("\\b(review|analy[sz]e|summarize|explain|inspect|describe|audit|critique|compare)\\b")
                .containsMatchIn(t)
        ) {
            return false
        }
        val listingHints = listOf(
            "list file", "list files", "list the file", "list project", "list the project",
            "show files", "show the files", "what files", "which files",
            "file list", "directory listing", "list source", "ls files"
        )
        if (listingHints.any { hint -> t.contains(hint) }) return true
        return t == "list" || t == "ls" || t == "files"
    }

    fun isSourceFileList(request: String): Boolean {
        val t = request.lowercase()
        if (t.contains("source file") || t.contains("source files") || t.contains("project source")) return true
        if (Regex("""\\b(in|under|path|directory|folder)\\b""").containsMatchIn(t)) return false
        if (t.contains("/") || t.contains("app/") || t.contains("src/")) return false
        return true
    }

    fun isGreeting(t: String): Boolean {
        if (t.length > 40) return false
        val greetings = listOf(
            "hi", "hello", "hey", "yo", "sup", "hi there", "hello there",
            "good morning", "good afternoon", "good evening", "ping", "you there",
            "are you there", "are you working", "can you hear me"
        )
        return greetings.any { t == it || t.startsWith("$it ") || t.startsWith("$it?") || t.startsWith("$it,") }
    }

    fun isStatus(t: String): Boolean {
        val exact = setOf("status", "help", "capabilities")
        if (t in exact) return true
        val phrases = listOf(
            "status report", "give me a status", "system status",
            "are you ready", "what can you do", "project status",
            "how many files", "summary of the project"
        )
        if (phrases.any { t == it || t.contains(it) }) return true
        if (Regex("""\\bstatus\\b""").containsMatchIn(t) && t.length <= 48) return true
        if (Regex("""\\bhelp\\b""").containsMatchIn(t) && !Regex("""\\b(helper|helpers)\\b""").containsMatchIn(t) && t.length <= 32) {
            return true
        }
        return false
    }

    fun isWholeProjectReview(request: String): Boolean {
        val t = request.lowercase()
        if (explicitReadPath(request) != null) return false
        if (inspectTarget(request) != null) return false
        val review = Regex("""\\b(review|analy[sz]e|audit|critique|improv)""")
        val scope = Regex("""\\b(project|codebase|repo|repository|app)\\b""")
        return review.containsMatchIn(t) && (scope.containsMatchIn(t) || t.length <= 90)
    }

    fun isAgentMeta(t: String): Boolean {
        if (t.length > 120) return false
        val aboutAgent = Regex("""\\b(why|what)\\b.*\\b(abort|aborted|stop|stopped|fail|failed|repeat|repeated|loop|tool)\\b""")
        val shortWhy = Regex("""^why\\s+(would|did|does|is|was)\\b""")
        return aboutAgent.containsMatchIn(t) || (shortWhy.containsMatchIn(t) && t.length < 60)
    }

    fun metaAnswer(t: String): String {
        val aboutRepeat = Regex("""\\b(repeat|repeated|identically|loop|same\\s+tool|read_file)\\b""").containsMatchIn(t)
        return if (aboutRepeat || t.contains("abort") || t.contains("stop")) {
            "The agent stopped a tool loop: the model called the same tool with the same arguments " +
                "several times in a row. That used to hard-abort the task. It now forces a final answer " +
                "from evidence already gathered (or nudges a different tool) instead of aborting. " +
                "Retry the original request if you still need the review."
        } else {
            "That question is about the agent runtime, not your project source. " +
                "Say what you wanted done (review, list files, read a path, fix a bug) and I will run that."
        }
    }

    fun explicitReadPath(request: String): String? = null
    fun inspectTarget(request: String): String? = null
    fun isMarkerOnly(request: String): Boolean {
        val lower = request.lowercase()
        val markerWords = listOf("todo", "fixme", "stub", "placeholder", "unfinished", "marker")
        val errorWords = listOf("error", "bug", "crash", "exception", "compile", "analyze", "logic")
        return markerWords.any { it in lower } && errorWords.none { it in lower }
    }
}
