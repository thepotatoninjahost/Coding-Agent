package com.codingagent.workspace

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

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


    /**
     * Atomically replace an ordinary project source file without interpreting
     * sibling .bak/.new files as persistence sidecars. Those names may be
     * legitimate files in an imported project. The temporary file lives in the
     * workspace's private transaction directory, not beside user source files.
     */
    @Synchronized
    fun writeProjectFile(file: File, transactionDirectory: File, content: String) {
        val parent = file.parentFile ?: throw IOException("Project file must have a parent directory")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Could not create project file directory: " + parent)
        }
        if (!transactionDirectory.isDirectory && !transactionDirectory.mkdirs() && !transactionDirectory.isDirectory) {
            throw IOException("Could not create private transaction directory: " + transactionDirectory)
        }
        rejectSymbolicLink(file)
        rejectSymbolicLink(transactionDirectory)
        val temporary = File(transactionDirectory, "project-write-${UUID.randomUUID()}.tmp")
        try {
            writeAndSync(temporary, content)
            val expected = content.toByteArray(Charsets.UTF_8)
            require(temporary.readBytes().contentEquals(expected)) {
                "Project file write verification failed for " + file.name
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            require(file.isFile && file.readBytes().contentEquals(expected)) {
                "Project file commit verification failed for " + file.name
            }
        } catch (error: Exception) {
            if (temporary.exists() && !temporary.delete()) {
                error.addSuppressed(IOException("Could not remove failed project temporary file: " + temporary.name))
            }
            throw error
        }
    }

    @Synchronized
    fun delete(file: File) {
        rejectSymbolicLink(file)
        rejectSymbolicLink(backup(file))
        rejectSymbolicLink(pending(file))
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
        rejectSymbolicLink(file)
        rejectSymbolicLink(backup)
        rejectSymbolicLink(pending)

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

    private fun rejectSymbolicLink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            throw IllegalStateException("Atomic persistence refuses symbolic-link paths: " + file.name)
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
