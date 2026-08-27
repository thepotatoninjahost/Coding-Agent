package com.codingagent.agent

/**
 * ONE JOB: Enforce the twelve non-negotiable safety rules.
 * These rules are READ-ONLY. Do not weaken, skip, or rephrase them away.
 *
 * 1. OWNER_LOCK — owner verification required
 * 2. DEFAULT_NO — no action without explicit approval
 * 3. CLEAR_PERMISSION — permission must be explicit
 * 4. DOUBLE_CONFIRMATION — two distinct approval channels
 * 5. SANDBOX_FIRST — evaluate before apply; nothing leaves sandbox early
 * 6. TRANSPARENCY_LOG — nothing silent; everything visible to the owner
 * 7. IMMEDIATE_STOP — lockdown stops non-read work
 * 8. PERMISSION_EXPIRATION — approvals expire
 * 9. NO_SILENT_BACKGROUND_POWER — no silent background power
 * 10. DATA_LOYALTY — data share needs per-connection approval
 * 11. ANTI_IMPERSONATION — voice-critical needs owner verification
 * 12. SAFETY_BOUNDARY — reserved hard stop for unsafe actions
 *
 * Dual approval channels (code/model/settings/export/share):
 *   (1) BIOMETRIC — fingerprint (or device biometric)
 *   (2) SPOKEN_PASSWORD — speak the password
 * Same channel twice does NOT satisfy DOUBLE_CONFIRMATION.
 */
enum class ConstitutionRule {
    OWNER_LOCK,
    DEFAULT_NO,
    CLEAR_PERMISSION,
    DOUBLE_CONFIRMATION,
    SANDBOX_FIRST,
    TRANSPARENCY_LOG,
    IMMEDIATE_STOP,
    PERMISSION_EXPIRATION,
    NO_SILENT_BACKGROUND_POWER,
    DATA_LOYALTY,
    ANTI_IMPERSONATION,
    SAFETY_BOUNDARY
}

/** Two distinct channels required for dual confirmation. */
enum class ApprovalChannel {
    BIOMETRIC,
    SPOKEN_PASSWORD
}

enum class AgentActionCategory {
    CODE_CHANGE,
    MODEL_CHANGE,
    SETTINGS_CHANGE,
    DATA_EXPORT,
    DATA_SHARE,
    READ_ONLY,
    SHELL_COMMAND
}

data class AgentAction(
    val description: String,
    val category: AgentActionCategory,
    val silent: Boolean = false,
    val ownerVerified: Boolean = false,
    val approvalCount: Int = 0,
    val distinctChannels: Int = 0,
    val sandboxPassed: Boolean = false,
    val explicitShareApproval: Boolean = false,
    val voiceInitiated: Boolean = false,
    val clearPermission: Boolean = true,
    val lockdown: Boolean = false
)

data class ConstitutionViolation(
    val rule: ConstitutionRule,
    val message: String,
    val blocking: Boolean
)

object AgentConstitution {
    const val APPROVAL_EXPIRATION_MS = 30 * 60 * 1000L

    private val doubleConfirmationCategories = setOf(
        AgentActionCategory.CODE_CHANGE,
        AgentActionCategory.MODEL_CHANGE,
        AgentActionCategory.SETTINGS_CHANGE,
        AgentActionCategory.DATA_EXPORT,
        AgentActionCategory.DATA_SHARE,
        AgentActionCategory.SHELL_COMMAND
    )

    fun check(
        action: AgentAction,
        now: Long = System.currentTimeMillis(),
        approvalAt: Long? = null
    ): List<ConstitutionViolation> {
        if (action.lockdown && action.category != AgentActionCategory.READ_ONLY) {
            return listOf(ConstitutionViolation(ConstitutionRule.IMMEDIATE_STOP, "Lockdown is active", true))
        }
        if (action.category == AgentActionCategory.READ_ONLY) return emptyList()

        val violations = mutableListOf<ConstitutionViolation>()

        if (!action.ownerVerified) {
            violations += ConstitutionViolation(ConstitutionRule.OWNER_LOCK, "Owner verification is required", true)
        }
        if (action.approvalCount == 0) {
            violations += ConstitutionViolation(ConstitutionRule.DEFAULT_NO, "No explicit approval was recorded", true)
        }
        if (!action.clearPermission) {
            violations += ConstitutionViolation(ConstitutionRule.CLEAR_PERMISSION, "The requested permission is not explicit", true)
        }
        if (action.category == AgentActionCategory.CODE_CHANGE && !action.sandboxPassed) {
            violations += ConstitutionViolation(ConstitutionRule.SANDBOX_FIRST, "Code changes require a passing evaluation", true)
        }
        if (action.category == AgentActionCategory.MODEL_CHANGE && !action.sandboxPassed) {
            violations += ConstitutionViolation(ConstitutionRule.SANDBOX_FIRST, "Model changes require a passing evaluation", true)
        }
        if (action.category == AgentActionCategory.SHELL_COMMAND && !action.sandboxPassed) {
            violations += ConstitutionViolation(ConstitutionRule.SANDBOX_FIRST, "Shell commands require sandbox evaluation", true)
        }
        if (action.silent) {
            violations += ConstitutionViolation(
                ConstitutionRule.NO_SILENT_BACKGROUND_POWER,
                "The action must be visible in the activity log — nothing is silent",
                true
            )
        }
        if (action.category == AgentActionCategory.DATA_SHARE && !action.explicitShareApproval) {
            violations += ConstitutionViolation(ConstitutionRule.DATA_LOYALTY, "Data sharing needs per-connection approval", true)
        }
        if (action.voiceInitiated && action.category in doubleConfirmationCategories) {
            violations += ConstitutionViolation(
                ConstitutionRule.ANTI_IMPERSONATION,
                "Voice-initiated critical changes need owner verification",
                true
            )
        }
        if (action.category in doubleConfirmationCategories) {
            if (action.approvalCount < 2) {
                violations += ConstitutionViolation(
                    ConstitutionRule.DOUBLE_CONFIRMATION,
                    "This action requires two approvals: fingerprint channel and spoken-password channel",
                    true
                )
            }
            if (action.distinctChannels < 2) {
                violations += ConstitutionViolation(
                    ConstitutionRule.DOUBLE_CONFIRMATION,
                    "Two approvals must use different channels (biometric + spoken password)",
                    true
                )
            }
        }
        if (approvalAt != null && now - approvalAt > APPROVAL_EXPIRATION_MS) {
            violations += ConstitutionViolation(ConstitutionRule.PERMISSION_EXPIRATION, "The approval has expired", true)
        }
        return violations.distinctBy { it.rule }
    }

    fun isAllowed(action: AgentAction, now: Long = System.currentTimeMillis(), approvalAt: Long? = null): Boolean =
        check(action, now, approvalAt).none { it.blocking }
}

data class ApprovalRecord(
    val actionId: String,
    val approvedAt: Long,
    val ownerLabel: String,
    val confirmationNumber: Int,
    val channel: ApprovalChannel
)

class ApprovalLedger {
    private val records = mutableMapOf<String, MutableList<ApprovalRecord>>()

    @Synchronized
    fun record(
        actionId: String,
        ownerLabel: String,
        channel: ApprovalChannel,
        at: Long = System.currentTimeMillis()
    ): ApprovalRecord {
        val current = records.getOrPut(actionId) { mutableListOf() }
        val record = ApprovalRecord(actionId, at, ownerLabel, current.size + 1, channel)
        current += record
        return record
    }

    @Synchronized
    fun count(actionId: String): Int = records[actionId]?.size ?: 0

    @Synchronized
    fun channels(actionId: String): Set<ApprovalChannel> =
        records[actionId]?.map { it.channel }?.toSet() ?: emptySet()

    @Synchronized
    fun latestAt(actionId: String): Long? = records[actionId]?.lastOrNull()?.approvedAt
}
