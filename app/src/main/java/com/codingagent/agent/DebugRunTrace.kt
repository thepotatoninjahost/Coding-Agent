package com.codingagent.agent

import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * ONE JOB: On a debug build, write a plain-text trace the owner can pull with adb.
 * Release builds write nothing. Secret passwords are not written.
 */
object DebugRunTrace {
    private val current = AtomicReference<File?>(null)

    fun start(root: File, title: String) {
        if (!com.codingagent.BuildConfig.DEBUG) return
        val file = File(root, ".coding-agent/run-trace.txt")
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("\n=== ${Instant.now()} ===\nrequest: ${redact(title)}\n")
            current.set(file)
        }
    }

    fun note(line: String) {
        val file = current.get() ?: return
        runCatching { file.appendText(redact(line).replace('\n', ' ') + "\n") }
    }

    fun finish(reason: String) {
        note("end: $reason")
        current.set(null)
    }

    fun redact(text: String): String = text
        .replace(Regex("""(?i)bearer\s+\S+"""), "Bearer [redacted]")
        .replace(Regex("""\b(?:sk|gsk|or|rsk|ghp)_[A-Za-z0-9_\-]{6,}"""), "[redacted]")
        .replace(Regex("""(?i)(api[_-]?key|authorization|token)\s*[:=]\s*\S+"""), "$1=[redacted]")
}
