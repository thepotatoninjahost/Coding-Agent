package com.codingagent.terminal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSessionTest {
    @Test
    fun executesShellCommandsAndKeepsHistory() {
        val session = TerminalSession(Files.createTempDirectory("terminal").toFile())

        val entry = session.execute("printf ready; printf failure >&2; exit 3")

        assertEquals(3, entry.result.exitCode)
        assertEquals("ready", entry.result.stdout)
        assertEquals("failure", entry.result.stderr)
        assertFalse(entry.result.timedOut)
        assertEquals(1, session.history().size)
    }

    @Test
    fun cancellationStopsLongRunningCommand() {
        val session = TerminalSession(Files.createTempDirectory("terminal").toFile())
        var result: TerminalEntry? = null
        val worker = Thread { result = session.execute("sleep 30", timeoutSeconds = 60) }
        worker.start()
        repeat(30) { if (!session.isBusy()) Thread.sleep(10) }
        session.cancel()
        worker.join(3_000)

        assertTrue(result != null)
        assertEquals(130, result?.result?.exitCode)
        assertFalse(session.isBusy())
    }
}
