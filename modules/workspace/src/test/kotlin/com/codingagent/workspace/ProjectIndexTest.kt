package com.codingagent.workspace

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectIndexTest {
    @Test fun indexesAndSearchesProjectFiles() {
        val root = Files.createTempDirectory("index").toFile(); root.resolve("src").mkdirs(); root.resolve("src/Main.kt").writeText("class Main\nfun run() = 1\n")
        val index = ProjectIndex()
        assertEquals("kotlin", index.index(root).single().language)
        assertTrue(index.search(root, "run").single().text.contains("run"))
    }
}
