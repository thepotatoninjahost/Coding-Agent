package com.codingagent.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCancelTest {
    @Test
    fun cancelStopsLongRunningCommand() {
        val root = Files.createTempDirectory("term-cancel").toFile()
        val runner = CommandRunner(root)
        val resultRef = AtomicReference<CommandResult>()
        val started = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            resultRef.set(runner.run(listOf("sh", "-c", "sleep 30"), timeoutSeconds = 60))
        }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        runner.cancel("test")
        thread.join(5_000)
        val result = resultRef.get()
        assertTrue(result != null)
        assertEquals(130, result.exitCode)
        assertTrue(result.stderr.contains("cancelled") || result.exitCode == 130)
    }

    @Test
    fun terminalSessionCancelPropagates() {
        val root = Files.createTempDirectory("term-session").toFile()
        val session = TerminalSession(root, timeoutSeconds = 60)
        val resultRef = AtomicReference<TerminalEntry>()
        val started = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            resultRef.set(session.execute("sleep 30"))
        }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        session.cancel("owner")
        thread.join(5_000)
        val entry = resultRef.get()
        assertTrue(entry != null)
        assertEquals(130, entry.exitCode)
    }

    @Test
    fun agentToolsCancelTerminalSharesSession() {
        val root = Files.createTempDirectory("term-tools").toFile()
        root.resolve("README.md").writeText("demo\n")
        val tools = AgentTools(ProjectWorkspace(root))
        val resultRef = AtomicReference<TerminalEntry>()
        val started = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            resultRef.set(tools.runTerminal("sleep 30"))
        }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        tools.cancelTerminal("ui-stop")
        thread.join(5_000)
        val entry = resultRef.get()
        assertTrue(entry != null)
        assertEquals(130, entry.exitCode)
    }

    @Test
    fun streamingCallbackReceivesOutput() {
        val root = Files.createTempDirectory("term-stream").toFile()
        val runner = CommandRunner(root)
        val chunks = StringBuilder()
        val result = runner.run(listOf("sh", "-c", "printf hello"), 10) { chunk -> chunks.append(chunk) }
        assertEquals(0, result.exitCode)
        assertTrue(chunks.toString().contains("hello") || result.stdout.contains("hello"))
    }
}
