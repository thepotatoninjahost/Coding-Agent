package com.codingagent.core

import com.codingagent.agent.ApprovalModality
import com.codingagent.agent.RiskyChange
import com.codingagent.workspace.ChangeOperation
import com.codingagent.workspace.ChangeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskyChangeTest {
    @Test
    fun deleteAndSelfChangeAskForThePhoneLock() {
        val delete = change("notes/todo.txt", ChangeOperation.REMOVE)
        val self = change("app/src/main/java/com/codingagent/agent/AgentPrompt.kt", ChangeOperation.REPLACE)
        val ordinary = change("src/Hello.kt", ChangeOperation.CREATE)

        assertTrue(RiskyChange.Kind.DELETE in RiskyChange.kinds(listOf(delete)))
        assertTrue(RiskyChange.Kind.SELF_MODIFY in RiskyChange.kinds(listOf(self)))
        assertTrue(RiskyChange.kinds(listOf(ordinary), "Please change your own prompt").contains(RiskyChange.Kind.SELF_MODIFY))
        assertTrue(RiskyChange.kinds(listOf(ordinary)).isEmpty())

        val refusal = RiskyChange.deviceLockRefusal(
            isTestCapability = false,
            changes = listOf(delete),
            request = "delete the note",
            modalities = setOf(ApprovalModality.TYPED, ApprovalModality.SPOKEN)
        )
        assertTrue(refusal!!.contains("phone lock"))
        assertTrue(refusal.contains("deletes a file"))
        assertNull(
            RiskyChange.deviceLockRefusal(
                isTestCapability = false,
                changes = listOf(delete),
                request = "delete the note",
                modalities = setOf(ApprovalModality.DEVICE_AUTH)
            )
        )
        assertNull(
            RiskyChange.deviceLockRefusal(
                isTestCapability = true,
                changes = listOf(delete),
                request = "delete the note",
                modalities = emptySet()
            )
        )
    }

    @Test
    fun sendingWorkOffThePhoneIsExplainedInPlainWords() {
        val summary = RiskyChange.plainSummary(setOf(RiskyChange.Kind.LEAVES_PHONE))
        assertTrue(summary.contains("off this phone"))
        assertEquals("", RiskyChange.plainSummary(emptySet()))
    }

    private fun change(path: String, operation: ChangeOperation) = ChangeRecord(
        path = path,
        operation = operation,
        before = null,
        after = "text",
        reason = "test",
        beforeChecksum = "",
        afterChecksum = "abc"
    )
}
