package com.codingagent.workspace

import java.io.File
import java.nio.file.Files

/**
 * Resolves private agent metadata only when every path component is a real
 * in-root filesystem path, not a symbolic link into another location.
 */
internal object ProjectMetadataBoundary {

    fun normalizePath(path: File): File {
        val absolute = path.absoluteFile
        var ancestor: File? = absolute
        while (ancestor != null) {
            if (ancestor.name.equals(".coding-agent", ignoreCase = true)) {
                val projectRoot = ancestor.parentFile
                    ?: throw IllegalArgumentException("Private metadata path has no project root")
                val relative = projectRoot.toPath().relativize(absolute.toPath())
                    .toString().replace('\\\\', '/')
                return resolve(projectRoot, relative)
            }
            ancestor = ancestor.parentFile
        }
        require(!Files.isSymbolicLink(absolute.toPath())) {
            "Private state directory may not be a symbolic link: $path"
        }
        return absolute.canonicalFile
    }

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
