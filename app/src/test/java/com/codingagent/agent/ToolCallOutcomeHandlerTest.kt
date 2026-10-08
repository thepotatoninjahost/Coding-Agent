package com.codingagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallOutcomeHandlerTest {
    @Test
    fun runCommandRequiresZeroExitAndNoTimeout() {
        assertTrue(toolExecutionSucceeded("run_command", "exit=0 timeout=false\noutput"))
        assertFalse(toolExecutionSucceeded("run_command", "exit=3 timeout=false\nerror"))
        assertFalse(toolExecutionSucceeded("run_command", "exit=0 timeout=true\noutput"))
        assertFalse(toolExecutionSucceeded("run_command", "ERROR: command failed"))
    }

    @Test
    fun verifyRequiresExplicitPassedTrue() {
        assertTrue(toolExecutionSucceeded("verify", "passed=true\n"))
        assertTrue(toolExecutionSucceeded("verify", "passed=true\nissues=0"))
        assertFalse(toolExecutionSucceeded("verify", "passed=false\nissue found"))
        assertFalse(toolExecutionSucceeded("verify", "passed=false"))
        assertFalse(toolExecutionSucceeded("verify", "ERROR: verification failed"))
    }

    @Test
    fun otherToolsRetainErrorPrefixSemantics() {
        assertTrue(toolExecutionSucceeded("read_file", "file contents"))
        assertFalse(toolExecutionSucceeded("read_file", "ERROR: file not found"))
    }
}
