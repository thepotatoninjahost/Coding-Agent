package com.codingagent.core
import com.codingagent.ui.review.ChangeDiff
import com.codingagent.ui.review.DiffLineKind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeDiffTest {
    @Test
    fun summarizeCreateAndReplace() {
        val create = ChangeRecord(
            path = "src/New.kt",
            operation = ChangeOperation.CREATE,
            before = null,
            after = "class New\n",
            reason = "create",
            beforeChecksum = "",
            afterChecksum = "abc"
        )
        val createSummary = ChangeDiff.summarizeFile(create)
        assertEquals(ChangeOperation.CREATE, createSummary.operation)
        assertEquals(0, createSummary.beforeLines)
        assertTrue(createSummary.added >= 1)

        val replace = ChangeRecord(
            path = "src/Main.kt",
            operation = ChangeOperation.REPLACE,
            before = "fun main() = 1\n",
            after = "fun main() = 2\n",
            reason = "bump",
            beforeChecksum = "a",
            afterChecksum = "b"
        )
        val lines = ChangeDiff.unified(replace)
        assertTrue(lines.any { it.kind == DiffLineKind.REMOVE && it.text.contains("= 1") })
        assertTrue(lines.any { it.kind == DiffLineKind.ADD && it.text.contains("= 2") })
    }

    @Test
    fun proposalViewListsEveryPath() {
        val changeSet = ChangeSet(
            id = "cs-1",
            changes = listOf(
                ChangeRecord("a.kt", ChangeOperation.REPLACE, "a", "b", "r", "1", "2"),
                ChangeRecord("b.kt", ChangeOperation.CREATE, null, "new", "r", "", "3")
            ),
            createdAt = 1_000L,
            reason = "multi"
        )
        val proposal = PendingChangeProposal(
            id = "prop-12345678",
            request = "update two files",
            changeSet = changeSet,
            verification = VerificationReport(true, emptyList()),
            createdAt = 1_000L,
            expiresAt = 1_000L + AgentConstitution.APPROVAL_EXPIRATION_MS
        )
        val view = ChangeDiff.summarize(proposal)
        assertEquals(2, view.fileCount)
        assertEquals(listOf("a.kt", "b.kt"), view.files.map { it.path })
        assertTrue(view.verificationPassed)
        assertEquals("prop-123", ChangeDiff.shortId(proposal.id))
    }

    @Test
    fun expiryLabelReflectsRemainingWindow() {
        val expires = 100_000L
        assertEquals("Expired", ChangeDiff.expiryLabel(expires, now = 100_001L))
        assertTrue(ChangeDiff.expiryLabel(expires, now = 40_000L).contains("m left") ||
            ChangeDiff.expiryLabel(expires, now = 40_000L).contains("s left"))
    }

    @Test
    fun lcsKeepsSharedLines() {
        val lcs = ChangeDiff.longestCommonSubsequence(
            listOf("a", "b", "c"),
            listOf("a", "x", "c")
        )
        assertEquals(listOf("a", "c"), lcs)
    }
}
