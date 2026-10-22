package com.codingagent.workspace

import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation

/**
 * ONE JOB: Format pending transactional changes for Review UI and tests.
 */
enum class DiffLineKind { CONTEXT, ADD, REMOVE, HEADER }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val beforeLine: Int? = null,
    val afterLine: Int? = null
)

data class FileDiffSummary(
    val path: String,
    val operation: ChangeOperation,
    val beforeLines: Int,
    val afterLines: Int,
    val added: Int,
    val removed: Int,
    val reason: String,
    val beforeChecksum: String,
    val afterChecksum: String
) {
    val opLabel: String get() = operation.name
}

data class ProposalDiffView(
    val proposalId: String,
    val request: String,
    val fileCount: Int,
    val approvals: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val files: List<FileDiffSummary>,
    val verificationPassed: Boolean,
    val verificationIssues: List<String>
)

object ChangeDiff {

    fun summarize(proposal: PendingChangeProposal): ProposalDiffView =
        ProposalDiffView(
            proposalId = proposal.id,
            request = proposal.request,
            fileCount = proposal.changeSet.changes.size,
            approvals = proposal.approvalCount,
            createdAt = proposal.createdAt,
            expiresAt = proposal.expiresAt,
            files = proposal.changeSet.changes.map { summarizeFile(it) },
            verificationPassed = proposal.verification.passed,
            verificationIssues = proposal.verification.issues.map { "${it.path}:${it.line} ${it.message}" }
        )

    fun summarizeFile(record: ChangeRecord): FileDiffSummary {
        val before = record.before.orEmpty()
        val after = record.after.orEmpty()
        val beforeList = before.lines()
        val afterList = after.lines()
        val (added, removed) = countLineDelta(beforeList, afterList)
        return FileDiffSummary(
            path = record.path,
            operation = record.operation,
            beforeLines = if (record.before == null) 0 else beforeList.size,
            afterLines = if (record.after == null) 0 else afterList.size,
            added = added,
            removed = removed,
            reason = record.reason,
            beforeChecksum = record.beforeChecksum,
            afterChecksum = record.afterChecksum
        )
    }

    private val explanationHeadings = listOf(
        "What I want to do:",
        "Why:",
        "What will change:",
        "Possible effects:",
        "Risks:",
        "What your approval means:"
    )

    /**
     * True only when the agent supplied all six required sections in the required order.
     */
    fun hasCompletePlainEnglishExplanation(reason: String): Boolean {
        var previous = -1
        for (heading in explanationHeadings) {
            val found = reason.indexOf(heading, startIndex = previous + 1, ignoreCase = false)
            if (found <= previous) return false
            val bodyStart = found + heading.length
            val nextHeading = explanationHeadings
                .mapNotNull { next -> reason.indexOf(next, startIndex = bodyStart).takeIf { it >= 0 } }
                .minOrNull() ?: reason.length
            if (reason.substring(bodyStart, nextHeading).isBlank()) return false
            previous = found
        }
        return true
    }

    /**
     * Internal and offline changes may not come from the model. Give those proposals
     * a truthful, readable explanation instead of presenting a technical label as enough.
     */
    fun ensurePlainEnglishExplanation(
        request: String,
        reason: String,
        operations: List<TaskOperation>
    ): String {
        if (hasCompletePlainEnglishExplanation(reason)) return reason
        val changes = operations.joinToString("\n") { operation ->
            val path = operation.path ?: "(file path not supplied)"
            when (operation.kind) {
                OperationKind.CREATE_FILE -> "Create the file $path with the proposed contents shown below."
                OperationKind.REPLACE -> "Change $path by replacing the selected existing text with the proposed text shown below."
                OperationKind.APPEND -> "Add the proposed text to the end of $path."
                OperationKind.REMOVE -> "Remove the selected text from $path."
                OperationKind.NONE -> "No specific file operation was supplied."
            }
        }.ifBlank { "The exact file changes are listed below." }
        val why =
            "To carry out the request you gave the agent. This proposal did not include a separate, clear explanation of the reason."
        val requestedOutcome = request.trim().ifBlank { "the requested change" }
        return buildString {
            appendLine("What I want to do:")
            appendLine("Carry out this request: $requestedOutcome")
            appendLine()
            appendLine("Why:")
            appendLine(why)
            appendLine()
            appendLine("What will change:")
            appendLine(changes)
            appendLine()
            appendLine("Possible effects:")
            appendLine("The listed files may behave differently after the changes are applied.")
            appendLine()
            appendLine("Risks:")
            appendLine("The changes could introduce errors or break existing behavior. No more specific risk assessment was provided in plain English. Review all current and proposed file contents below.")
            appendLine()
            appendLine("What your approval means:")
            appendLine("Nothing is written until you complete both required owner approvals. After both approvals, these exact listed changes can be applied.")
        }
    }

    /**
     * Human-readable review body. Proposed files are not on disk until dual approval,
     * so this text is the only way the owner can see what they are confirming.
     */
    fun ownerReviewText(proposal: PendingChangeProposal): String = buildString {
        appendLine("PROPOSED CHANGES — nothing is written until you confirm twice.")
        appendLine("Read the plain-English explanation before deciding. If it does not clearly explain the change or its risks, reject it and ask the agent to explain.")
        appendLine("Proposal ${shortId(proposal.id)} · ${proposal.changeSet.changes.size} file(s) · ${expiryLabel(proposal.expiresAt)}")
        appendLine()
        appendLine("WHAT YOU ASKED FOR (complete):")
        appendCompleteContent(proposal.request)
        appendLine()
        appendLine("IN EVERYDAY WORDS:")
        appendLine(everydaySummary(proposal))
        appendLine()
        appendLine("THE AGENT'S PLAIN-ENGLISH EXPLANATION (complete):")
        appendCompleteContent(ownerFacingReason(proposal.changeSet.reason))
        if (!hasCompletePlainEnglishExplanation(proposal.changeSet.reason)) {
            appendLine("WARNING: This proposal does not contain all six required explanation sections. Do not approve it until the agent provides a complete explanation.")
        }
        appendLine()
        appendLine("EXACT FILE CHANGES (complete contents follow):")
        if (proposal.changeSet.changes.isEmpty()) {
            appendLine("No file records in this proposal.")
        }
        proposal.changeSet.changes.forEach { record ->
            appendLine()
            val operationLabel = when (record.operation) {
                ChangeOperation.CREATE -> "Create file"
                ChangeOperation.REPLACE -> "Change file"
                ChangeOperation.APPEND -> "Add text to file"
                ChangeOperation.REMOVE -> "Remove text from file"
            }
            appendLine("=== $operationLabel: ${record.path} ===")
            if (record.reason != proposal.changeSet.reason) {
                appendLine("Additional file-specific reason (complete):")
                appendCompleteContent(ownerFacingReason(record.reason))
            } else {
                appendLine("Reason: see the complete plain-English explanation above.")
            }
            appendLine("Before-file safety check (helps detect unexpected edits): ${record.beforeChecksum.ifBlank { "<none>" }}")
            appendLine("Proposed-file safety check (helps detect unexpected edits): ${record.afterChecksum.ifBlank { "<none>" }}")
            record.before?.let { before ->
                appendLine("--- BEFORE CONTENT (complete; ${before.length} characters) ---")
                appendCompleteContent(before)
            }
            record.after?.let { after ->
                appendLine("--- AFTER CONTENT (complete; ${after.length} characters) ---")
                appendCompleteContent(after)
            }
        }
        appendLine()
        appendLine("END OF COMPLETE PROPOSAL REVIEW.")
        appendLine("Open the Review tab to confirm or reject. Files tab only lists files already on disk.")
    }

    /**
     * The approval card is what the owner reads. Specialist words are rewritten
     * into everyday language before that card is shown.
     */
    fun ownerFacingReason(reason: String): String {
        var text = reason
        val replacements = listOf(
            Regex("(?i)recursive[- ]descent parsers?") to "a reader that walks through the text from the top, one piece at a time",
            Regex("(?i)abstract syntax trees?") to "an outline of the program",
            Regex("(?i)\\bASTs?\\b") to "an outline of the program",
            Regex("(?i)\\btokenizers?\\b") to "a splitter that breaks the text into small pieces",
            Regex("(?i)\\blexers?\\b") to "a splitter that breaks the text into small pieces",
            Regex("(?i)\\bparsers?\\b") to "a reader that understands how the text is structured",
            Regex("(?i)\\bbytecodes?\\b") to "instructions the program can run",
            Regex("(?i)\\binterpreters?\\b") to "a program that runs the text directly",
            Regex("(?i)\\bcompilers?\\b") to "a program that turns written instructions into something that can run",
            Regex("(?i)\\btokens?\\b") to "small pieces of the text",
            Regex("(?i)\\bgrammars?\\b") to "the rules for how the text may be written",
            Regex("(?i)semantic analysis") to "a check that the text makes sense",
            Regex("(?i)code generation") to "writing the finished program"
        )
        replacements.forEach { (pattern, replacement) ->
            text = text.replace(pattern, replacement)
        }
        return text
    }

    fun everydaySummary(proposal: PendingChangeProposal): String {
        val files = proposal.changeSet.changes.map { it.path }.distinct()
        val fileWords = if (files.isEmpty()) "the files listed below" else files.joinToString(", ")
        val action = when {
            proposal.changeSet.changes.isEmpty() -> "I prepared a change"
            proposal.changeSet.changes.all { it.operation == ChangeOperation.CREATE } -> "I prepared new files"
            proposal.changeSet.changes.any { it.operation == ChangeOperation.REMOVE } -> "I prepared a change that removes something"
            else -> "I prepared a change"
        }
        return "$action: $fileWords. This is what you asked for. Nothing is saved until you say yes on this phone."
    }

    private fun StringBuilder.appendCompleteContent(content: String) {
        append(content)
        if (!content.endsWith("\n")) appendLine()
    }

    fun unified(record: ChangeRecord, maxLines: Int = Int.MAX_VALUE): List<DiffLine> {
        val before = record.before?.lines().orEmpty()
        val after = record.after?.lines().orEmpty()
        val out = mutableListOf<DiffLine>()
        out += DiffLine(
            DiffLineKind.HEADER,
            "--- ${record.path} (${record.operation.name.lowercase()})"
        )
        when (record.operation) {
            ChangeOperation.CREATE -> after.forEachIndexed { i, line ->
                out += DiffLine(DiffLineKind.ADD, "+${clip(line)}", afterLine = i + 1)
            }
            ChangeOperation.REMOVE -> before.forEachIndexed { i, line ->
                out += DiffLine(DiffLineKind.REMOVE, "-${clip(line)}", beforeLine = i + 1)
            }
            ChangeOperation.APPEND -> {
                before.forEachIndexed { i, line ->
                    val n = i + 1
                    out += DiffLine(DiffLineKind.CONTEXT, " ${clip(line)}", beforeLine = n.coerceAtLeast(1), afterLine = n.coerceAtLeast(1))
                }
                after.drop(before.size).forEachIndexed { i, line ->
                    out += DiffLine(DiffLineKind.ADD, "+${clip(line)}", afterLine = before.size + i + 1)
                }
            }
            ChangeOperation.REPLACE -> {
                if (before.size > 2_000 || after.size > 2_000) {
                    before.forEachIndexed { i, line -> out += DiffLine(DiffLineKind.REMOVE, "-$line", beforeLine = i + 1) }
                    after.forEachIndexed { i, line -> out += DiffLine(DiffLineKind.ADD, "+$line", afterLine = i + 1) }
                } else {
                val lcs = longestCommonSubsequence(before, after)
                var bi = 0
                var ai = 0
                var li = 0
                while (bi < before.size || ai < after.size) {
                    if (li < lcs.size && bi < before.size && before[bi] == lcs[li] &&
                        ai < after.size && after[ai] == lcs[li]
                    ) {
                        out += DiffLine(DiffLineKind.CONTEXT, " ${clip(before[bi])}", beforeLine = bi + 1, afterLine = ai + 1)
                        bi++; ai++; li++
                    } else {
                        if (bi < before.size && (li >= lcs.size || before[bi] != lcs[li])) {
                            out += DiffLine(DiffLineKind.REMOVE, "-${clip(before[bi])}", beforeLine = bi + 1)
                            bi++
                        }
                        if (ai < after.size && (li >= lcs.size || after[ai] != lcs[li])) {
                            out += DiffLine(DiffLineKind.ADD, "+${clip(after[ai])}", afterLine = ai + 1)
                            ai++
                        }
                    }
                }
                }
            }
        }
        return out
    }

    fun expiryLabel(expiresAt: Long, now: Long = System.currentTimeMillis()): String {
        val remaining = expiresAt - now
        return when {
            remaining <= 0 -> "Expired"
            remaining < 60_000 -> "${remaining / 1000}s left"
            remaining < 3_600_000 -> "${remaining / 60_000}m left"
            else -> "${remaining / 3_600_000}h left"
        }
    }

    fun shortId(id: String): String = id.take(8)

    private fun clip(line: String): String = line

    private fun countLineDelta(before: List<String>, after: List<String>): Pair<Int, Int> {
        val lcs = longestCommonSubsequence(before, after)
        return (after.size - lcs.size) to (before.size - lcs.size)
    }

    internal fun longestCommonSubsequence(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        if (a.size > 2_000 || b.size > 2_000) return emptyList()
        val left = a
        val right = b
        val n = left.size
        val m = right.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (left[i - 1] == right[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        val result = ArrayList<String>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when {
                left[i - 1] == right[j - 1] -> {
                    result.add(left[i - 1])
                    i--; j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        return result.asReversed()
    }
}
