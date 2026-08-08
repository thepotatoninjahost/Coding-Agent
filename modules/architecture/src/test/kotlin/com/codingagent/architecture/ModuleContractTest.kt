package com.codingagent.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleContractTest {
    @Test
    fun architectureHasNoContractViolations() {
        assertTrue(CodingAgentArchitecture.validate().isEmpty(), CodingAgentArchitecture.validate().joinToString("; "))
    }

    @Test
    fun moduleNamesAreUnique() {
        val names = CodingAgentArchitecture.modules.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun everyCapabilityHasOneOwner() {
        val responsibilities = CodingAgentArchitecture.modules.map { it.responsibility }
        assertEquals(1, responsibilities.count { it.contains("Project files", ignoreCase = true) })
        assertEquals(1, responsibilities.count { it.contains("Internet search", ignoreCase = true) })
        assertEquals(1, responsibilities.count { it.contains("Model request", ignoreCase = true) })
        assertEquals(1, responsibilities.count { it.contains("Command execution", ignoreCase = true) })
        assertEquals(1, responsibilities.count { it.contains("Durable event", ignoreCase = true) })
        assertEquals(1, responsibilities.count { it.contains("workflow coordinating", ignoreCase = true) })
        assertEquals(1, responsibilities.count { it.contains("screens", ignoreCase = true) })
    }

    @Test
    fun presentationIsOwnedOnlyByUi() {
        val nonUi = CodingAgentArchitecture.modules.filterNot { it.name == "ui" }
        assertTrue(nonUi.none { it.responsibility.contains("screens", ignoreCase = true) })
    }
}
