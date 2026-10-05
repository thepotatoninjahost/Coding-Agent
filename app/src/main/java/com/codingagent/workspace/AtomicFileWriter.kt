package com.codingagent.workspace

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe text persistence for app-private filesystem state.
 *
 * New bytes are written and synced to a same-directory pending file before
 * that file is renamed into the committed path. A leftover pending file is
 * discarded during recovery. A leftover legacy backup is restored before reads.
 *
 * This follows Android AtomicFile's pending-file commit model without depending
 * on android.jar, so JVM tests exercise the same persistence protocol.
 */
internal object AtomicFileWriter {
    private fun backup(file: File): File = File(file.path + ".bak")
    private fun pending(file: File): File = File(file.path + ".new")

    @Synchronized
    fun write(file: File, content: String) {
        val parent = file.parentFile ?: throw IOException("Atomic file must have a parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Could not create atomic file directory: " + parent)
        }

        recover(file)

        val pending = pending(file)
        try {
            if (pending.exists() && !pending.delete()) {
                throw IOException("Could not remove stale atomic pending file: " + pending)
            }

            writeAndSync(pending, content)

            val onDisk = pending.readBytes()
            val expected = content.toByteArray(Charsets.UTF_8)
            require(onDisk.contentEquals(expected)) {
                "Atomic write verification failed for " + file.name
            }

            if (!pending.renameTo(file)) {
                throw IOException("Could not commit atomic file: " + pending + " -> " + file)
            }
        } catch (error: Exception) {
            if (pending.exists() && !pending.delete()) {
                error.addSuppressed(
                    IOException("Could not remove failed atomic pending file: " + pending)
                )
            }
            throw error
        }
    }

    @Synchronized
    fun delete(file: File) {
        if (!file.delete() && file.exists()) {
            throw IllegalStateException("Atomic delete failed: " + file)
        }
        val backup = backup(file)
        val pending = pending(file)
        if (!backup.delete() && backup.exists()) {
            throw IllegalStateException("Atomic delete failed for backup: " + backup)
        }
        if (!pending.delete() && pending.exists()) {
            throw IllegalStateException("Atomic delete failed for pending file: " + pending)
        }
    }

    @Synchronized
    fun readTextIfExists(file: File): String? {
        recover(file)
        return if (file.isFile) file.readText(Charsets.UTF_8) else null
    }

    @Synchronized
    fun recover(file: File) {
        val backup = backup(file)
        val pending = pending(file)

        if (backup.exists()) {
            file.delete()
            if (!backup.renameTo(file)) {
                throw IllegalStateException(
                    "Atomic recovery failed: could not restore " + backup + " to " + file
                )
            }
        }

        if (pending.exists()) {
            if (!file.exists() && !pending.delete()) {
                throw IllegalStateException("Atomic recovery failed: could not remove orphan " + pending)
            }
            if (file.exists() && !pending.delete()) {
                throw IllegalStateException("Atomic recovery failed: could not remove " + pending)
            }
        }
    }

    private fun writeAndSync(file: File, content: String) {
        FileOutputStream(file, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }
}
