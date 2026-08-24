package com.codingagent.workspace

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
    private const val MAX_UNIFIED_LINES = 400
    private const val MAX_LINE_CHARS = 240

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

    fun unified(record: ChangeRecord, maxLines: Int = MAX_UNIFIED_LINES): List<DiffLine> {
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
                before.takeLast(3).forEachIndexed { i, line ->
                    val n = before.size - 3 + i + 1
                    out += DiffLine(DiffLineKind.CONTEXT, " ${clip(line)}", beforeLine = n.coerceAtLeast(1), afterLine = n.coerceAtLeast(1))
                }
                after.drop(before.size).forEachIndexed { i, line ->
                    out += DiffLine(DiffLineKind.ADD, "+${clip(line)}", afterLine = before.size + i + 1)
                }
            }
            ChangeOperation.REPLACE -> {
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
                    if (out.size >= maxLines) {
                        out += DiffLine(DiffLineKind.HEADER, "… truncated (${before.size}+${after.size} lines total)")
                        break
                    }
                }
            }
        }
        if (out.size > maxLines) {
            return out.take(maxLines) + DiffLine(DiffLineKind.HEADER, "… truncated")
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

    private fun clip(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS - 1) + "…"

    private fun countLineDelta(before: List<String>, after: List<String>): Pair<Int, Int> {
        val lcs = longestCommonSubsequence(before, after)
        return (after.size - lcs.size) to (before.size - lcs.size)
    }

    internal fun longestCommonSubsequence(a: List<String>, b: List<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val left = a.take(2_000)
        val right = b.take(2_000)
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
