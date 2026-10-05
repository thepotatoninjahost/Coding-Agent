package com.codingagent.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codingagent.agent.ExperienceRecorder

class ExperienceRecorderTest {
    @Test
    fun historyLoaderReturnsOnlyRecentBoundedEntries() {
        val root = Files.createTempDirectory("experience-tail").toFile()
        val recorder = ExperienceRecorder(root)
        repeat(250) { index ->
            recorder.record(
                task = "task-$index",
                operation = "completed",
                result = "result-$index",
                evidence = "evidence-$index",
                passed = true
            )
        }

        val lines = recorder.all()
        assertEquals(200, lines.size)
        assertTrue(lines.first().contains("task-50"))
        assertTrue(lines.last().contains("task-249"))
    }
}
