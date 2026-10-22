package com.codingagent.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Seals one line of chat or task notes.
 *
 * Unit tests run on a plain JVM and do not install a cipher, so lines stay as written.
 * On a phone, [LocalStore] installs the Android Keystore cipher. Existing plain lines
 * are rewritten the next time the file is read or appended.
 */
object NotesAtRest {
    const val PREFIX = "enc1:"

    interface Cipher {
        fun seal(plain: String): String
        fun open(sealed: String): String
    }

    private val lock = Any()
    @Volatile private var cipher: Cipher? = null
    private val fileLocks = ConcurrentHashMap<String, Any>()

    fun install(cipher: Cipher) {
        synchronized(lock) { this.cipher = cipher }
    }

    fun clear() {
        synchronized(lock) { cipher = null }
    }

    fun isInstalled(): Boolean = cipher != null

    fun isSealed(line: String): Boolean = line.startsWith(PREFIX)

    fun sealLine(plain: String): String {
        val current = cipher ?: return plain
        val sealed = current.seal(plain).replace("\n", "").replace("\r", "")
        return PREFIX + sealed
    }

    fun openLine(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        val current = cipher ?: return stored
        return current.open(stored.removePrefix(PREFIX))
    }

    fun appendLine(file: File, plain: String) {
        synchronized(lockFor(file)) {
            file.parentFile?.mkdirs()
            migrateLocked(file)
            file.appendText(sealLine(plain) + "\n")
        }
    }

    fun migrate(file: File) {
        synchronized(lockFor(file)) { migrateLocked(file) }
    }

    private fun migrateLocked(file: File) {
        if (cipher == null || !file.isFile) return
        val lines = file.readLines()
        if (lines.none { it.isNotBlank() && !isSealed(it) }) return
        val sealed = lines.map { line ->
            if (line.isBlank()) line else sealLine(openLine(line))
        }
        val tmp = File(file.parentFile, file.name + ".migrate")
        tmp.writeText(sealed.joinToString("\n") + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun lockFor(file: File): Any =
        fileLocks.computeIfAbsent(file.absolutePath) { Any() }
}
