package com.codingagent.core

import com.codingagent.workspace.ProjectWorkspace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProjectWorkspaceVerificationPolicyTest {
    @Test
    fun autonomousVerificationDoesNotExecuteImportedBuildScripts() {
        val root = Files.createTempDirectory("verification-script-policy").toFile()
        val marker = root.resolve("script-was-executed.txt")
        root.resolve("gradlew").apply {
            writeText("#!/system/bin/sh\nprintf executed > \"$marker\"\n")
            setExecutable(true)
        }

        val report = ProjectWorkspace(root).runChecks(
            listOf(listOf("sh", "-c", "./gradlew test"))
        )

        assertFalse(report.passed)
        assertTrue(report.commands.isEmpty())
        assertFalse(marker.exists())
        assertTrue(report.issues.any { it.message.contains("Not executed by autonomous verification") })
    }
}
