package com.codingagent.core

import java.io.File

/**
 * ONE JOB: Stable project-relative paths on Android (SAF / /data mounts).
 *
 * File.relativeTo() can emit ../../../../data/... when canonical roots differ.
 * Prefer Path.relativize, then string prefix strip, never leak absolute device paths.
 */
object ProjectPaths {
    fun relative(root: File, file: File): String {
        val rootCanon = root.canonicalFile
        val fileCanon = file.canonicalFile
        try {
            val rootPath = rootCanon.toPath()
            val filePath = fileCanon.toPath()
            if (filePath.startsWith(rootPath)) {
                val rel = rootPath.relativize(filePath).toString().replace('\\', '/')
                if (rel.isNotBlank() && !rel.startsWith("..")) return rel
            }
        } catch (_: Exception) {
            // fall through
        }
        val rootStr = rootCanon.invariantSeparatorsPath.trimEnd('/') + "/"
        val fileStr = fileCanon.invariantSeparatorsPath
        if (fileStr.startsWith(rootStr)) {
            return fileStr.removePrefix(rootStr)
        }
        // Last resort: name only (still usable, never absolute device path)
        return fileCanon.name
    }
}
