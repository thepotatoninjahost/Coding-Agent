package com.codingagent.agent

/**
 * ONE JOB: Separate model reasoning from the user-facing answer and flag
 * conclusions that contradict gathered evidence.
 */
data class LogicVerdict(
    val displayText: String,
    val reasoning: String,
    val issues: List<String>
) {
    val contradicted: Boolean get() = issues.isNotEmpty()
}

object LogicReasoning {
    private val THINK_BLOCK = Regex(
        "<think>[\\s\\S]*?</think>|<thinking>[\\s\\S]*?</thinking>|```(?:reasoning|thought|think)[\\s\\S]*?```",
        RegexOption.IGNORE_CASE
    )
    private val REASONING_HEADER = Regex(
        "(?im)^(?:reasoning|thoughts?|chain of thought|internal monologue)\\s*:\\s*"
    )
    private val FILE_CLAIM = Regex(
        "(?<![\\w./])((?:[A-Za-z0-9_./-]+/)*[A-Za-z0-9_.-]+\\.(?:kt|kts|java|xml|gradle|md|json|properties|txt))\\b"
    )
    private val APPLIED_CLAIM = Regex(
        "\\b(applied to disk|written to disk|already applied|change is live|committed the fix)\\b",
        RegexOption.IGNORE_CASE
    )
    private val VERIFY_PASS_CLAIM = Regex(
        "\\b(verify(?:ication)? (?:passed|ok|clean)|static verification passed)\\b",
        RegexOption.IGNORE_CASE
    )
    private val VERIFY_FAIL_EVIDENCE = Regex(
        "\\b(verification:\\s*failed|verify failed|FAILED\\s*\\()",
        RegexOption.IGNORE_CASE
    )

    fun inspect(answer: String, evidence: String = "", request: String = ""): LogicVerdict {
        val (body, reasoning) = split(answer)
        val cleaned = body.trim().ifBlank {
            if (reasoning.isNotBlank()) {
                "The model only produced internal reasoning, not an answer. Retry the request."
            } else {
                ""
            }
        }
        val issues = mutableListOf<String>()
        if (body.isBlank() && reasoning.isNotBlank()) {
            issues += "Answer was reasoning-only; no user-facing conclusion"
        }
        issues += inventedPaths(cleaned, evidence)
        if (APPLIED_CLAIM.containsMatchIn(cleaned) && !evidence.contains("APPLIED", ignoreCase = true)) {
            issues += "Claimed a write was applied, but evidence has no APPLIED result"
        }
        if (VERIFY_PASS_CLAIM.containsMatchIn(cleaned) && VERIFY_FAIL_EVIDENCE.containsMatchIn(evidence)) {
            issues += "Claimed verification passed, but evidence records a failure"
        }
        if (request.isNotBlank() && looksLikeChangeRequest(request) && looksLikeReviewOnly(cleaned) &&
            !evidence.contains("needs-approval", ignoreCase = true) &&
            !evidence.contains("APPLIED", ignoreCase = true)
        ) {
            issues += "Change request answered with a review instead of a staged edit"
        }
        val display = if (issues.isEmpty()) {
            cleaned
        } else {
            buildString {
                append(cleaned)
                if (cleaned.isNotBlank()) append("\n\n")
                append("Logic check:\n")
                issues.forEach { append("- ").append(it).append('\n') }
            }.trim()
        }
        return LogicVerdict(display, reasoning, issues)
    }

    fun split(answer: String): Pair<String, String> {
        if (answer.isBlank()) return "" to ""
        val blocks = mutableListOf<String>()
        var rest = THINK_BLOCK.replace(answer) { match ->
            blocks += match.value
            ""
        }
        val header = REASONING_HEADER.find(rest)
        if (header != null && header.range.first < rest.length / 2) {
            val after = rest.substring(header.range.last + 1)
            val splitAt = after.indexOf("\n\n")
            if (splitAt >= 0) {
                blocks += after.take(splitAt)
                rest = after.substring(splitAt + 2)
            }
        }
        return rest.trim() to blocks.joinToString("\n").trim()
    }

    private fun inventedPaths(answer: String, evidence: String): List<String> {
        if (evidence.isBlank()) return emptyList()
        val issues = ArrayList<String>()
        val seen = HashSet<String>()
        for (match in FILE_CLAIM.findAll(answer)) {
            val path = match.groupValues[1]
            if (!seen.add(path)) continue
            val name = path.substringAfterLast('/')
            val known = evidence.contains(path, ignoreCase = true) ||
                evidence.contains(name, ignoreCase = true)
            if (!known) {
                issues.add("Cited `$path` which does not appear in gathered evidence")
            }
        }
        return issues
    }

    private fun looksLikeChangeRequest(request: String): Boolean {
        val t = request.lowercase()
        return Regex("\\b(fix|change|edit|replace|implement|add|remove|refactor|modify)\\b").containsMatchIn(t)
    }

    private fun looksLikeReviewOnly(answer: String): Boolean {
        val t = answer.lowercase()
        if (t.contains("needs-approval") || t.contains("staged") || t.contains("replace_text") || t.contains("create_file")) {
            return false
        }
        return Regex("\\b(review|looks fine|here is an analysis|i would suggest)\\b").containsMatchIn(t)
    }
}
