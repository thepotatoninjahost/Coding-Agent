package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.codingagent.agent.AgentAction
import com.codingagent.agent.AgentActionCategory
import com.codingagent.workspace.KnowledgeHit
import com.codingagent.knowledge.KnowledgeProvider
import com.codingagent.model.LiveModelRouter
import com.codingagent.model.LiveModelStore
import com.codingagent.model.ModelInstallResult
import com.codingagent.workspace.ProjectWorkspace
import com.codingagent.workspace.VerificationReport

class LiveUpdateTest {
    @Test
    fun `module source changes are installed and loaded without process restart`() {
        val root = Files.createTempDirectory("coding-agent-live").toFile()
        val workspace = ProjectWorkspace(root)
        val store = LiveModuleStore(root)
        val knowledge = object : KnowledgeProvider {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val runtime = LiveModuleRuntime(workspace, knowledge, store)
        val first = """{"kind":"coding","version":1,"steps":[{"op":"emit","value":"first"}]}"""
        val action = AgentAction("replace-module", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        val firstInstall = store.install(first, "coding", 1, action, VerificationReport(true, emptyList()))
        assertTrue(firstInstall.toString(), firstInstall is ModuleInstallResult.Installed)
        assertEquals("first", runtime.execute("ignored").output.single())
        val second = """{"kind":"coding","version":1,"steps":[{"op":"emit","value":"second"}]}"""
        assertTrue(store.install(second, "coding", 1, action, VerificationReport(true, emptyList())) is ModuleInstallResult.Installed)
        assertEquals("second", runtime.execute("ignored").output.single())
    }

    @Test
    fun `model package validator accepts manifest and payload files`() {
        val root = Files.createTempDirectory("coding-agent-model-pack").toFile()
        val packageDir = root.resolve("package").apply { mkdirs() }
        packageDir.resolve("model.manifest").writeText(
            """{"name":"demo-model","files":[{"name":"weights.bin"},{"name":"config.json"}]}"""
        )
        packageDir.resolve("weights.bin").writeBytes(byteArrayOf(1))
        packageDir.resolve("config.json").writeBytes(byteArrayOf(2))
        val store = LiveModelStore(root)
        val action = AgentAction("install-package", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        val result = store.installPackage(packageDir, "demo-model", "generic", action, VerificationReport(true, emptyList()))
        assertTrue(result is ModelInstallResult.Installed)
        assertEquals(2L, store.active()?.sizeBytes)
    }

    @Test
    fun `model router replaces loaded model bytes`() {
        val root = Files.createTempDirectory("coding-agent-model").toFile()
        val source = File(root, "model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = LiveModelStore(root)
        val action = AgentAction("install-model", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        assertTrue(store.install(source, "test", "raw", action, VerificationReport(true, emptyList())) is ModelInstallResult.Installed)
        val router = LiveModelRouter(store)
        assertEquals(3, router.loadedBytes())
        source.writeBytes(byteArrayOf(9, 8, 7, 6))
        assertEquals(3, router.loadedBytes())
        store.active()?.let { store.install(source, "test-next", "raw", action, VerificationReport(true, emptyList())) }
        router.reload()
        assertEquals(4, router.loadedBytes())
    }

    @Test
    fun `module patch switches running runtime and preserves rollback`() {
        val root = Files.createTempDirectory("coding-agent-patch").toFile()
        val workspace = ProjectWorkspace(root)
        val store = LiveModuleStore(root)
        val knowledge = object : KnowledgeProvider {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val runtime = LiveModuleRuntime(workspace, knowledge, store)
        val action = AgentAction("patch-module", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        store.install("""{"kind":"coding","version":1,"steps":[{"op":"emit","value":"old"}]}""", "coding", 1, action, VerificationReport(true, emptyList()))
        assertEquals("old", runtime.execute("").output.single())
        val patched = runtime.applyPatch({ it.replace("old", "new") }, action)
        assertTrue(patched is ModulePatchResult.Switched)
        assertEquals("new", runtime.execute("").output.single())
        val history = store.history()
        assertTrue(history.size >= 2)
        assertTrue(store.rollback(history.first().id))
        runtime.reload()
        assertEquals("old", runtime.execute("").output.single())
    }

    @Test
    fun `failed patch restores previous active module`() {
        val root = Files.createTempDirectory("coding-agent-patch-fail").toFile()
        val workspace = ProjectWorkspace(root)
        val store = LiveModuleStore(root)
        val knowledge = object : KnowledgeProvider {
            override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
        }
        val runtime = LiveModuleRuntime(workspace, knowledge, store)
        val action = AgentAction("patch-module-fail", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        store.install("""{"kind":"coding","version":1,"steps":[{"op":"emit","value":"stable"}]}""", "coding", 1, action, VerificationReport(true, emptyList()))
        assertEquals("stable", runtime.execute("").output.single())
        val rejected = runtime.applyPatch({ """{"kind":"coding","version":1,"steps":[{"op":"unknown"}]}""" }, action)
        assertTrue(rejected is ModulePatchResult.Rejected)
        assertEquals("stable", runtime.execute("").output.single())
    }
}
