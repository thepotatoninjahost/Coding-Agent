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
    fun moduleParserAcceptsEscapedJsonStringsWithoutRegexCorruption() {
        val store = LiveModuleStore(java.io.File.createTempFile("module-test", "").parentFile)
        val parsed = store.parse(
            """{"kind":"coding","version":1,"steps":[{"op":"emit","value":"quoted \"value\" and {braces}","argument":"a:b"}]}"""
        )
        assertEquals("coding", parsed.kind)
        assertEquals(1, parsed.version)
        assertEquals("emit", parsed.steps.single().operation)
        assertEquals("quoted \"value\" and {braces}", parsed.steps.single().value)
        assertEquals("a:b", parsed.steps.single().argument)
    }

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





    @Test
    fun liveModuleRunStepCannotBypassAutonomousCommandPolicy() {
        val root = Files.createTempDirectory("coding-agent-module-command-policy").toFile()
        val victim = root.resolve("victim.txt").apply { writeText("must remain") }
        val workspace = ProjectWorkspace(root)
        val store = LiveModuleStore(root)
        val action = AgentAction("unsafe-module-command", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        val module = """{"kind":"coding","version":1,"steps":[{"op":"run","value":"rm victim.txt"}]}"""
        val installed = store.install(module, "coding", 1, action, VerificationReport(true, emptyList()))
        assertTrue(installed is ModuleInstallResult.Installed)

        val runtime = LiveModuleRuntime(
            workspace,
            object : KnowledgeProvider {
                override fun search(query: String, limit: Int): List<KnowledgeHit> = emptyList()
            },
            store
        )
        try {
            runtime.execute("")
            throw AssertionError("Live module must not execute a blocked command")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Live-module command blocked"))
        }
        assertTrue(victim.exists())
        assertEquals("must remain", victim.readText())
    }

    @Test
    fun modelPackageInspectionRejectsSymlinkPayloads() {
        val root = Files.createTempDirectory("coding-agent-package-link").toFile()
        val packageDir = root.resolve("package").apply { mkdirs() }
        val outside = root.resolve("outside-weights.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        packageDir.resolve("model.manifest").writeText("""{"name":"demo","files":["weights.bin"]}""")
        try {
            Files.createSymbolicLink(packageDir.resolve("weights.bin").toPath(), outside.toPath())
        } catch (_: Exception) {
            org.junit.Assume.assumeTrue("Symbolic links are required for this regression test", false)
        }

        val inspected = LiveModelStore(root).inspectPackage(packageDir)
        assertTrue(!inspected.complete)
        assertTrue(inspected.invalid.any { it.contains("symbolic links") })
        assertTrue(inspected.files.none { it.name == "weights.bin" })
    }

    @Test
    fun modelBytesRejectsPathsOutsidePrivateModelStorage() {
        val root = Files.createTempDirectory("coding-agent-model-bytes-boundary").toFile()
        val outside = root.resolve("outside.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = LiveModelStore(root)
        val untrusted = com.codingagent.model.LiveModel(
            id = "external",
            name = "external",
            format = "raw",
            sourcePath = outside.absolutePath,
            checksum = "",
            sizeBytes = outside.length(),
            createdAt = 0L
        )

        try {
            store.modelBytes(untrusted)
            throw AssertionError("Model bytes must not read outside private model storage")
        } catch (_: IllegalArgumentException) {
            // Expected: source paths are confined to the private model store.
        }
    }

    @Test
    fun activeModuleAndRollbackRejectSymlinkedModuleDirectories() {
        val root = Files.createTempDirectory("coding-agent-module-pointer").toFile()
        val store = LiveModuleStore(root)
        val moduleRoot = root.resolve(".coding-agent/live-modules")
        val outside = Files.createTempDirectory("coding-agent-module-outside").toFile()
        outside.resolve("module.json").writeText(
            """{"kind":"coding","version":1,"steps":[{"op":"emit","value":"outside"}]}"""
        )
        try {
            Files.createSymbolicLink(moduleRoot.resolve("linked-module").toPath(), outside.toPath())
        } catch (_: Exception) {
            org.junit.Assume.assumeTrue("Symbolic links are required for this regression test", false)
        }

        moduleRoot.resolve("active-module").writeText("linked-module")
        assertTrue(store.active() == null)
        assertEquals(false, store.rollback("linked-module"))
    }

    @Test
    fun activeModelPointerRejectsTraversalAndSymlinkedModelDirectories() {
        val root = Files.createTempDirectory("coding-agent-model-pointer").toFile()
        val store = LiveModelStore(root)
        val modelRoot = root.resolve(".coding-agent/models")
        val activeFile = modelRoot.resolve("active-model")

        activeFile.writeText("../../outside")
        assertEquals(null, store.active())

        val outside = Files.createTempDirectory("coding-agent-model-outside").toFile()
        outside.resolve("payload.bin").writeBytes(byteArrayOf(7, 8, 9))
        try {
            Files.createSymbolicLink(modelRoot.resolve("linked-model").toPath(), outside.toPath())
        } catch (_: Exception) {
            org.junit.Assume.assumeTrue("Symbolic links are required for this regression test", false)
        }
        activeFile.writeText("linked-model")
        assertEquals(null, store.active())
    }

    @Test
    fun moduleKindCannotEscapeLiveModuleDirectory() {
        val root = Files.createTempDirectory("coding-agent-live-path").toFile()
        val store = LiveModuleStore(root)
        val action = AgentAction("unsafe-module", AgentActionCategory.CODE_CHANGE, ownerVerified = true, approvalCount = 2)
        val result = store.install(
            """{"kind":"../../escape","version":1,"steps":[{"op":"emit","value":"bad"}]}""",
            "../../escape",
            1,
            action,
            VerificationReport(true, emptyList())
        )
        assertTrue(result is ModuleInstallResult.Rejected)
        assertTrue(root.resolve("escape").exists().not())
        assertTrue(store.history().isEmpty())
    }

}
