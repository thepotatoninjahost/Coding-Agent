package com.codingagent.core

import com.codingagent.ui.isReservedProjectImportEntry
import com.codingagent.ui.validateProjectImportEntryName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectImportPolicyTest {
    @Test
    fun reservesPrivateMetadataRepositoryStateAndGeneratedDirectoryTrees() {
        assertTrue(isReservedProjectImportEntry(".coding-agent"))
        assertTrue(isReservedProjectImportEntry(".coding-agent"))
        assertTrue(isReservedProjectImportEntry(".git", isDirectory = true))
        assertTrue(isReservedProjectImportEntry(".git", isDirectory = false))
        assertTrue(isReservedProjectImportEntry("build", isDirectory = true))
        assertTrue(isReservedProjectImportEntry("node_modules", isDirectory = true))
        assertTrue(isReservedProjectImportEntry(".gradle", isDirectory = true))
        assertFalse(isReservedProjectImportEntry("build", isDirectory = false))
        assertFalse(isReservedProjectImportEntry("src", isDirectory = true))
        assertFalse(isReservedProjectImportEntry("Main.kt", isDirectory = false))
    }

    @Test
    fun acceptsOrdinaryProjectEntryNames() {
        validateProjectImportEntryName("src")
        validateProjectImportEntryName("Main.kt")
        validateProjectImportEntryName("gradlew")
        validateProjectImportEntryName(".gitignore")
    }

    @Test
    fun rejectsNamesThatCanEscapeOrConfuseTheImportRoot() {
        val invalidNames = listOf("", ".", "..", "../outside", "src/Main.kt", "src\\Main.kt", "bad\u0000name")
        invalidNames.forEach { name ->
            try {
                validateProjectImportEntryName(name)
                throw AssertionError("Expected unsafe project entry name to be rejected: ${name.toCharArray().contentToString()}")
            } catch (expected: IllegalArgumentException) {
                // Expected: untrusted imported names must not become filesystem paths.
            }
        }
    }
}
