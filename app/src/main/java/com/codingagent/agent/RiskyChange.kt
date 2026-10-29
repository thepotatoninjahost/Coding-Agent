package com.codingagent.agent

import com.codingagent.workspace.ChangeOperation
import com.codingagent.workspace.ChangeRecord

/**
 * Risky work is a delete, a change to this app, or anything that leaves the phone.
 * Spoken words are not enough for those. The phone lock has to approve them.
 */
object RiskyChange {
    enum class Kind { DELETE, SELF_MODIFY, LEAVES_PHONE }

    fun kinds(changes: List<ChangeRecord>, request: String = ""): Set<Kind> {
        val found = linkedSetOf<Kind>()
        if (changes.any { it.operation == ChangeOperation.REMOVE }) found += Kind.DELETE
        if (changes.any { isSelfPath(it.path) } || mentionsSelf(request)) found += Kind.SELF_MODIFY
        return found
    }

    fun plainSummary(kinds: Set<Kind>): String {
        if (kinds.isEmpty()) return ""
        return buildString {
            append("Spoken words are not enough for this. ")
            if (Kind.DELETE in kinds) append("This deletes a file. ")
            if (Kind.SELF_MODIFY in kinds) append("This changes the app itself. ")
            if (Kind.LEAVES_PHONE in kinds) append("This sends your work off this phone. ")
            append("Confirm with the phone lock so only you can approve it.")
        }.trim()
    }

    /**
     * Production approvals of risky work must include the phone lock.
     * JVM test tokens skip this so existing coordinator tests stay focused on their own checks.
     */
    fun deviceLockRefusal(
        isTestCapability: Boolean,
        changes: List<ChangeRecord>,
        request: String,
        modalities: Set<ApprovalModality>
    ): String? {
        if (isTestCapability) return null
        val summary = plainSummary(kinds(changes, request))
        if (summary.isEmpty()) return null
        if (ApprovalModality.DEVICE_AUTH in modalities) return null
        return summary
    }

    private fun isSelfPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.contains("com/codingagent/") || normalized.contains(".coding-agent/evolution")
    }

    private fun mentionsSelf(request: String): Boolean {
        val lower = request.lowercase()
        return listOf(
            "change yourself",
            "change your prompt",
            "change your own",
            "self-modify",
            "self modify",
            "your own code",
            "your own prompt"
        ).any { it in lower }
    }
}
