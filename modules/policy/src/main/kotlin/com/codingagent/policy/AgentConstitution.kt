package com.codingagent.policy

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

enum class AgentActionCategory {
    CODE_CHANGE,
    MODEL_CHANGE,
    SETTINGS_CHANGE,
    DATA_EXPORT,
    DATA_SHARE,
    READ_ONLY
}

data class AgentAction(
    val description: String,
    val category: AgentActionCategory,
    val silent: Boolean = false,
    val ownerVerified: Boolean = false,
    val approvalCount: Int = 0,
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
    const val APPROVAL_EXPIRATION_MS = 2 * 60 * 1000L

    private val doubleConfirmationCategories = setOf(
        AgentActionCategory.CODE_CHANGE,
        AgentActionCategory.MODEL_CHANGE,
        AgentActionCategory.SETTINGS_CHANGE,
        AgentActionCategory.DATA_EXPORT,
        AgentActionCategory.DATA_SHARE
    )

    fun check(action: AgentAction, now: Long = System.currentTimeMillis(), approvalAt: Long? = null): List<ConstitutionViolation> {
        if (action.lockdown && action.category != AgentActionCategory.READ_ONLY) {
            return listOf(ConstitutionViolation(ConstitutionRule.IMMEDIATE_STOP, "Lockdown is active", true))
        }
        if (action.category == AgentActionCategory.READ_ONLY) return emptyList()
        val violations = mutableListOf<ConstitutionViolation>()
        if (!action.ownerVerified) violations += ConstitutionViolation(ConstitutionRule.OWNER_LOCK, "Owner verification is required", true)
        if (action.approvalCount == 0) violations += ConstitutionViolation(ConstitutionRule.DEFAULT_NO, "No explicit approval was recorded", true)
        if (!action.clearPermission) violations += ConstitutionViolation(ConstitutionRule.CLEAR_PERMISSION, "The requested permission is not explicit", true)
        if (action.category == AgentActionCategory.CODE_CHANGE && !action.sandboxPassed) {
            violations += ConstitutionViolation(ConstitutionRule.SANDBOX_FIRST, "Code changes require a passing evaluation", true)
        }
        if (action.category == AgentActionCategory.MODEL_CHANGE && !action.sandboxPassed) {
            violations += ConstitutionViolation(ConstitutionRule.SANDBOX_FIRST, "Model changes require a passing evaluation", true)
        }
        if (action.silent) violations += ConstitutionViolation(ConstitutionRule.NO_SILENT_BACKGROUND_POWER, "The action must be visible in the activity log", true)
        if (action.category == AgentActionCategory.DATA_SHARE && !action.explicitShareApproval) {
            violations += ConstitutionViolation(ConstitutionRule.DATA_LOYALTY, "Data sharing needs per-connection approval", true)
        }
        if (action.voiceInitiated && action.category in doubleConfirmationCategories) {
            violations += ConstitutionViolation(ConstitutionRule.ANTI_IMPERSONATION, "Voice-initiated critical changes need owner verification", true)
        }
        if (action.category in doubleConfirmationCategories && action.approvalCount < 2) {
            violations += ConstitutionViolation(ConstitutionRule.DOUBLE_CONFIRMATION, "This action requires two approvals", true)
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
    val confirmationNumber: Int
)

class ApprovalLedger {
    private val records = mutableMapOf<String, MutableList<ApprovalRecord>>()

    @Synchronized
    fun record(actionId: String, ownerLabel: String, at: Long = System.currentTimeMillis()): ApprovalRecord {
        val current = records.getOrPut(actionId) { mutableListOf() }
        val record = ApprovalRecord(actionId, at, ownerLabel, current.size + 1)
        current += record
        return record
    }

    @Synchronized
    fun count(actionId: String): Int = records[actionId]?.size ?: 0

    @Synchronized
    fun latestAt(actionId: String): Long? = records[actionId]?.lastOrNull()?.approvedAt
}
