package com.codingagent.policy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConstitutionTest {
    @Test fun codeChangesRequireTwoApprovalsAndEvaluation() {
        val violations = AgentConstitution.check(AgentAction("edit", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 1))
        assertTrue(violations.any { it.rule == ConstitutionRule.SANDBOX_FIRST })
        assertTrue(violations.any { it.rule == ConstitutionRule.DOUBLE_CONFIRMATION })
        assertFalse(AgentConstitution.isAllowed(AgentAction("edit", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)))
    }

    @Test fun readOnlyActionsAreAllowed() {
        assertTrue(AgentConstitution.isAllowed(AgentAction("inspect", AgentActionCategory.READ_ONLY)))
    }
}
