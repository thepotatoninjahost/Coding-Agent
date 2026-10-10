package com.codingagent.core

import com.codingagent.agent.ToolPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPurposeTest {
    @Test
    fun ownerFacingToolMessagesUsePlainLanguage() {
        val messages = listOf(
            ToolPurpose.of("list_files", """{}"""),
            ToolPurpose.of("read_file", """{"path":"src/Main.kt"}"""),
            ToolPurpose.of("search_project", """{"query":"compiler"}"""),
            ToolPurpose.of("search_knowledge", """{"query":"Kotlin"}"""),
            ToolPurpose.of("research_web", """{"query":"Kotlin compiler API"}"""),
            ToolPurpose.of("replace_text", """{"path":"src/Main.kt"}"""),
            ToolPurpose.of("create_file", """{"path":"src/Main.kt"}"""),
            ToolPurpose.of("run_command", """{"command":"./gradlew test"}"""),
            ToolPurpose.of("verify", """{}""")
        ).joinToString("\n")

        assertTrue(messages.contains("Checking"))
        assertTrue(messages.contains("Reading"))
        assertTrue(messages.contains("Searching"))
        assertTrue(messages.contains("Preparing"))
        assertTrue(messages.contains("Running"))
        assertTrue(messages.contains("Checking the current changes"))
        assertFalse(messages.contains("Purpose:"))
        assertFalse(messages.contains("static verification"))
    }

    @Test
    fun explicitPurposeIsUsedForOwnerFacingLog() {
        val purpose = ToolPurpose.of(
            "read_file",
            """{"path":"src/Main.kt","purpose":"I need the implementation before designing a safe fix."}"""
        )

        assertEquals("I need the implementation before designing a safe fix.", purpose)
    }
}
