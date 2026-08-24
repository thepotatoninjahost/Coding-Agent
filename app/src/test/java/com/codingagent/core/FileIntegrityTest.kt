package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.codingagent.intake.OperationKind
import com.codingagent.intake.TaskOperation
import com.codingagent.workspace.FileIntegrity
import com.codingagent.workspace.ProjectWorkspace

class FileIntegrityTest {
    @Test
    fun completeKotlinPasses() {
        val src = """
            package demo
            class Report {
                fun ok(): Int = 1
            }
        """.trimIndent()
        val issues = FileIntegrity.inspect("Report.kt", src)
        assertTrue("complete file must pass: $issues", issues.isEmpty())
        assertTrue(FileIntegrity.matchesChecksum(src, FileIntegrity.sha256(src)))
    }

    @Test
    fun placeholderIsADefect() {
        val src = """
            package demo
            class Agent {
                fun run() {
                    // rest unchanged
                }
            }
        """.trimIndent()
        val issues = FileIntegrity.inspect("Agent.kt", src)
        assertTrue(issues.any { it.message.contains("truncation placeholder") })
    }

    @Test
    fun missingBraceIsADefect() {
        val src = """
            package demo
            class Broken {
                fun run() {
                    println("hi")
        """.trimIndent()
        val issues = FileIntegrity.inspect("Broken.kt", src)
        assertTrue("unclosed brace must fail: $issues", issues.any { it.message.contains("unclosed '{'") })
    }

    @Test
    fun bracesInsideStringsDoNotFail() {
        val src = """
            package demo
            fun msg() = "{ not a real brace"
            fun ok() = 1
        """.trimIndent()
        val issues = FileIntegrity.inspect("Copy.kt", src)
        assertTrue("string braces must not fail: $issues", issues.isEmpty())
    }

    @Test
    fun checksumMismatchIsADefect() {
        val src = "fun ok() = 1\n"
        val issues = FileIntegrity.inspect("Ok.kt", src, expectedChecksum = "deadbeef")
        assertTrue(issues.any { it.message.contains("SHA-256 mismatch") })
        assertFalse(FileIntegrity.matchesChecksum(src, "deadbeef"))
    }

    @Test
    fun midExpressionEndingIsADefect() {
        val src = "fun ok() =\n"
        val issues = FileIntegrity.inspect("Ok.kt", src)
        assertTrue(issues.any { it.message.contains("mid-expression") })
    }

    @Test
    fun verifyProposalRejectsPlaceholder() {
        val root = Files.createTempDirectory("integrity-proposal").toFile()
        root.resolve("Keep.kt").writeText("fun keep() = 1\n")
        val workspace = ProjectWorkspace(root)
        val preview = workspace.preview(
            listOf(
                TaskOperation(
                    OperationKind.CREATE_FILE,
                    "New.kt",
                    text = "class New {\n    // rest unchanged\n}\n"
                )
            ),
            "test"
        )
        val report = workspace.verifyProposal(preview)
        assertFalse(report.passed)
        assertTrue(report.issues.any { it.message.contains("truncation placeholder") })
    }

    @Test
    fun applyRejectsIfDiskChecksumWouldDiverge() {
        val root = Files.createTempDirectory("integrity-apply").toFile()
        root.resolve("Example.kt").writeText("fun one() = 1\n")
        val workspace = ProjectWorkspace(root)
        val changeSet = workspace.replace("Example.kt", "= 1", "= 2", "test")
        assertEquals("fun one() = 2\n", root.resolve("Example.kt").readText())
        assertTrue(
            FileIntegrity.matchesChecksum(
                root.resolve("Example.kt").readText(),
                changeSet.changes.single().afterChecksum
            )
        )
    }

    @Test
    fun verifyFlagsIncompleteSourceOnDisk() {
        val root = Files.createTempDirectory("integrity-disk").toFile()
        root.resolve("Broken.kt").writeText("class Broken {\n  fun x() {\n")
        val report = ProjectWorkspace(root).verify()
        assertFalse(report.passed)
        assertTrue(report.issues.any { it.message.startsWith("integrity:") })
    }
}
