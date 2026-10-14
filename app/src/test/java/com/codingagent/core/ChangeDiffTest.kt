package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.AgentConstitution
import com.codingagent.workspace.ChangeDiff
import com.codingagent.workspace.ChangeOperation
import com.codingagent.workspace.ChangeRecord
import com.codingagent.workspace.ChangeSet
import com.codingagent.workspace.DiffLineKind
import com.codingagent.workspace.PendingChangeProposal
import com.codingagent.workspace.VerificationReport
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation

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
    fun ownerReviewIncludesEntireLargeChangeAndLongRequestWithoutSilentTruncation() {
        val request = "request-" + "r".repeat(700)
        val content = "start\n" + "x".repeat(15_000) + "\nEND-OF-FILE-SENTINEL"
        val proposal = PendingChangeProposal(
            id = "prop-large-review",
            request = request,
            changeSet = ChangeSet(
                id = "cs-large",
                changes = listOf(
                    ChangeRecord(
                        path = "large.txt",
                        operation = ChangeOperation.CREATE,
                        before = null,
                        after = content,
                        reason = "reason-" + "r".repeat(500),
                        beforeChecksum = "",
                        afterChecksum = "after-checksum"
                    )
                ),
                createdAt = 1_000L,
                reason = "large review"
            ),
            verification = VerificationReport(true, emptyList()),
            createdAt = 1_000L,
            expiresAt = 1_000L + AgentConstitution.APPROVAL_EXPIRATION_MS
        )

        val review = ChangeDiff.ownerReviewText(proposal)

        assertTrue(review.length > 12_000)
        assertTrue(review.contains(request))
        assertTrue(review.contains(content))
        assertTrue(review.contains("END-OF-FILE-SENTINEL"))
        assertTrue(review.contains("reason-" + "r".repeat(500)))
        assertTrue(review.contains("THE AGENT'S PLAIN-ENGLISH EXPLANATION (complete):"))
        assertTrue(review.contains("WARNING: This proposal does not contain all six required explanation sections."))
        assertTrue(review.contains("Create file: large.txt"))
        assertTrue(review.contains("Before-file safety check"))
        assertTrue(review.trimEnd().endsWith("Files tab only lists files already on disk."))
    }

    @Test
    fun ownerReviewIncludesCompleteBeforeAndAfterContentForReplacement() {
        val before = "BEFORE-BEGIN\n" + "b".repeat(13_000) + "\nBEFORE-END"
        val after = "AFTER-BEGIN\n" + "a".repeat(13_000) + "\nAFTER-END"
        val proposal = PendingChangeProposal(
            id = "prop-replace-review",
            request = "replace full file",
            changeSet = ChangeSet(
                id = "cs-replace",
                changes = listOf(
                    ChangeRecord(
                        path = "replace.txt",
                        operation = ChangeOperation.REPLACE,
                        before = before,
                        after = after,
                        reason = "replace complete contents",
                        beforeChecksum = "before-checksum",
                        afterChecksum = "after-checksum"
                    )
                ),
                createdAt = 1_000L,
                reason = "replacement review"
            ),
            verification = VerificationReport(true, emptyList()),
            createdAt = 1_000L,
            expiresAt = 1_000L + AgentConstitution.APPROVAL_EXPIRATION_MS
        )

        val review = ChangeDiff.ownerReviewText(proposal)

        assertTrue(review.contains(before))
        assertTrue(review.contains(after))
        assertTrue(review.contains("BEFORE-END"))
        assertTrue(review.contains("AFTER-END"))
        assertTrue(review.contains("END OF COMPLETE PROPOSAL REVIEW."))
    }


    @Test
    fun plainEnglishProposalExplanationRequiresAllSixHeadingsInOrder() {
        val complete = """
            What I want to do:
            Change the main file so the app starts correctly.

            Why:
            The current version stops before showing the main screen.

            What will change:
            I will update Main.kt. The complete old and new contents are shown below.

            Possible effects:
            The app should reach the main screen after this change.

            Risks:
            The change could introduce a bug. The project checks have not yet been run.

            What your approval means:
            Nothing is written until you complete both owner approvals.
        """.trimIndent()

        assertTrue(ChangeDiff.hasCompletePlainEnglishExplanation(complete))
        assertTrue(!ChangeDiff.hasCompletePlainEnglishExplanation("Autonomous model proposal"))
        assertTrue(!ChangeDiff.hasCompletePlainEnglishExplanation(
            "Why: reason\nWhat I want to do: change\nWhat will change: file\nPossible effects: behavior\nRisks: bugs\nWhat your approval means: confirm twice"
        ))
    }

    @Test
    fun internalProposalWithoutModelExplanationGetsHonestPlainEnglishFallback() {
        val explanation = ChangeDiff.ensurePlainEnglishExplanation(
            request = "create a helper file",
            reason = "Editor save",
            operations = listOf(TaskOperation(OperationKind.CREATE_FILE, "src/Helper.kt", text = "class Helper"))
        )

        assertTrue(ChangeDiff.hasCompletePlainEnglishExplanation(explanation))
        assertTrue(explanation.contains("Create the file src/Helper.kt"))
        assertTrue(explanation.contains("No more specific risk assessment was provided"))
        assertTrue(explanation.contains("both required owner approvals"))
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
