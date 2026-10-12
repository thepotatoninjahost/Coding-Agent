package com.codingagent.workspace

import java.io.File

/**
 * ONE JOB: Stable project-relative paths on Android (SAF / /data mounts).
 *
 * File.relativeTo() can emit ../../../../data/... when canonical roots differ.
 * Prefer Path.relativize, then string prefix strip, never leak absolute device paths.
 */
object ProjectPaths {
    private val secretFileNames = setOf(
        ".netrc", ".npmrc", ".pypirc", "credentials.json", "secrets.json",
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "authorized_keys"
    )
    private val secretExtensions = setOf("pem", "key", "p12", "pfx", "keystore", "jks")

    /** Shared exclusion for credential stores and internal metadata. */
    fun isSensitivePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        return parts.any { part ->
            part.equals(".coding-agent", ignoreCase = true) ||
                part.equals(".git", ignoreCase = true) ||
                part.equals(".ssh", ignoreCase = true) ||
                part.equals(".aws", ignoreCase = true) ||
                part.startsWith(".env", ignoreCase = true) ||
                part.lowercase() in secretFileNames ||
                part.substringAfterLast('.', "").lowercase() in secretExtensions
        }
    }

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
