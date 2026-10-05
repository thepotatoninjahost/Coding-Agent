package com.codingagent.workspace

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe text persistence for app-private filesystem state.
 *
 * Existing files are moved to a same-directory backup before replacement.
 * New bytes are synced before the backup is discarded. A leftover backup is
 * always treated as an incomplete write and restored before reads.
 *
 * This follows Android AtomicFile's recovery model without depending on
 * android.jar, so JVM tests exercise the same persistence protocol.
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

        val backup = backup(file)
        val pending = pending(file)
        var backedUp = false

        try {
            if (file.exists()) {
                if (backup.exists() && !backup.delete()) {
                    throw IOException("Could not remove stale atomic backup: " + backup)
                }
                if (!file.renameTo(backup)) {
                    throw IOException("Could not create atomic backup: " + file + " -> " + backup)
                }
                backedUp = true
            }

            if (pending.exists() && !pending.delete()) {
                throw IOException("Could not remove stale atomic pending file: " + pending)
            }

            if (backedUp) {
                writeAndSync(file, content)
            } else {
                writeAndSync(pending, content)
                if (!pending.renameTo(file)) {
                    throw IOException("Could not commit atomic new file: " + pending + " -> " + file)
                }
            }

            val onDisk = file.readBytes()
            val expected = content.toByteArray(Charsets.UTF_8)
            require(onDisk.contentEquals(expected)) {
                "Atomic write verification failed for " + file.name
            }

            if (backup.exists() && !backup.delete()) {
                throw IOException("Could not remove committed atomic backup: " + backup)
            }
        } catch (error: Exception) {
            pending.delete()
            if (backedUp) {
                file.delete()
                if (!backup.renameTo(file)) {
                    throw IllegalStateException(
                        "Atomic write failed and previous file could not be restored: " + file,
                        error
                    )
                }
            }
            throw error
        } finally {
            pending.delete()
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
