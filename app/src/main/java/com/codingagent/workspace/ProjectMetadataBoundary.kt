package com.codingagent.workspace

import java.io.File
import java.nio.file.Files

/**
 * Resolves private agent metadata only when every path component is a real
 * in-root filesystem path, not a symbolic link into another location.
 */
internal object ProjectMetadataBoundary {
    fun resolve(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && !relativePath.contains('\\')) {
            "Unsafe private metadata path"
        }
        val parts = relativePath.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe private metadata path"
        }

        val canonicalRoot = root.canonicalFile
        val rootPath = canonicalRoot.toPath()
        var current = canonicalRoot
        val walked = mutableListOf<String>()
        for (part in parts) {
            walked += part
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) {
                "Private agent metadata may not use symbolic links: $relativePath"
            }
            val expected = rootPath.resolve(walked.joinToString("/")).normalize()
            val canonical = current.canonicalFile.toPath()
            require(canonical == expected && canonical.startsWith(rootPath)) {
                "Private agent metadata path escapes its project root: $relativePath"
            }
        }
        return current
    }
}
