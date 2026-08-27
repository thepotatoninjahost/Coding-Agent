package com.codingagent.agent

import com.codingagent.workspace.VerificationReport

/**
 * ONE JOB: Build final answer text from tool evidence (not from inventing content).
 */
object AnswerFromEvidence {
    fun formatListing(listing: String, report: VerificationReport, namesOnly: Boolean = true): String = buildString {
        if (namesOnly) {
            append("Indexed source files (extension whitelist — not a full disk listing):\n")
        } else {
            append("Directory listing:\n")
        }
        append(listing.trim().ifBlank { "(none)" })
        append("\n\nVerification: ")
        if (report.passed) {
            append("passed (static unfinished-work marker scan)")
        } else {
            append("FAILED; ")
            append(report.issues.size)
            append(" issue(s)")
            report.issues.take(20).forEach { issue ->
                append("\n- ")
                append(issue.path)
                append(":")
                append(issue.line)
                append(" — ")
                append(issue.message)
            }
        }
    }

    fun sanitizeModelText(text: String, report: VerificationReport): String {
        if (!DegenerateOutput.isDegenerate(text)) {
            return text.take(4_000)
        }
        return buildString {
            append("The model produced repetitive garbage instead of a coherent report. ")
            append("Static verification found ")
            append(report.issues.size)
            append(" issue(s)")
            if (report.issues.isEmpty()) {
                append(" (none).")
            } else {
                append(":")
                report.issues.take(20).forEach { issue ->
                    append("\n- ")
                    append(issue.path)
                    append(":")
                    append(issue.line)
                    append(" — ")
                    append(issue.message)
                }
            }
        }
    }

    fun synthesize(request: String, evidence: String, report: VerificationReport, maxChars: Int): String = buildString {
        append("Review from gathered evidence (model did not write a final after tools were closed).\n\n")
        append("Request: ").append(request.trim()).append("\n\n")
        append(evidence.take(maxChars))
        append("\n\nVerification: ")
        if (report.passed) {
            append("passed (static unfinished-work marker scan)")
        } else {
            append("FAILED (").append(report.issues.size).append(" issue(s))")
            report.issues.take(20).forEach { issue ->
                append("\n- ").append(issue.path).append(":").append(issue.line).append(" — ").append(issue.message)
            }
        }
        append("\n\nIf this is thinner than you wanted, retry once. The next run starts with this evidence already in context.")
    }
}
