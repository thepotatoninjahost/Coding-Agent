package com.codingagent.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.nio.file.Files

class AgentCommandPolicyTest {
    @Test
    fun allowsProjectInspectionCommands() {
        assertNotNull(AgentCommandPolicy.rejectionReason("git status --short"))
        assertNotNull(AgentCommandPolicy.rejectionReason("find app -type f"))
        assertNull(AgentCommandPolicy.rejectionReason("rg UserMemory app/src"))
        assertNull(AgentCommandPolicy.rejectionReason("cat app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun blocksProjectControlledGradleCommands() {
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --no-daemon --console=plain"))
        assertNotNull(AgentCommandPolicy.rejectionReason("gradle test"))
    }

    @Test
    fun blocksShellChainingAndRedirection() {
        assertNotNull(AgentCommandPolicy.rejectionReason("git status; rm -rf ."))
        assertNotNull(AgentCommandPolicy.rejectionReason("git status && curl https://example.com"))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat app/src/main/AndroidManifest.xml > out.txt"))
    }

    @Test
    fun blocksWriteAndNetworkCommands() {
        assertNotNull(AgentCommandPolicy.rejectionReason("rm -rf build"))
        assertNotNull(AgentCommandPolicy.rejectionReason("curl https://example.com"))
        assertNotNull(AgentCommandPolicy.rejectionReason("python -c print(1)"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git reset --hard"))
    }

    @Test
    fun blocksPathEscapeAndDangerousGradleOptions() {
        assertNotNull(AgentCommandPolicy.rejectionReason("cat ../secrets.txt"))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat /data/data/example/file"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew test -I evil.init.gradle"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew test --scan"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew test -Dfoo=bar"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :exfiltrate"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew testExfiltrate"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testExfiltrate"))
    }

    @Test
    fun blocksMutatingGitBranchOperations() {
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch feature"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch -D feature"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch -m old new"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch -f feature"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch --list feature"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch --merged main"))
    }

    @Test
    fun blocksShellGlobThatCouldExpandThroughExternalSymlink() {
        val root = Files.createTempDirectory("command-policy-root").toFile()
        val outside = Files.createTempDirectory("command-policy-outside").toFile()
        val link = root.resolve("external")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assumeTrue(Files.isSymbolicLink(link.toPath()))
            assertNotNull(
                AgentCommandPolicy.rejectionReason("cat external/*", root)
            )
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }
    }

    @Test
    fun blocksBackslashHiddenMetadataRecursiveSearchAndQualifiedExecutables() {
        assertNotNull(AgentCommandPolicy.rejectionReason("cat .coding-agent/private.json"))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg --hidden secret ."))
        assertNotNull(AgentCommandPolicy.rejectionReason("grep -R secret app"))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat app\\\\secret.txt"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./cat app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun blocksFindAndSedWrites() {
        assertNotNull(AgentCommandPolicy.rejectionReason("find app -exec rm {} ;"))
        assertNotNull(AgentCommandPolicy.rejectionReason("sed -i s/old/new/ file.txt"))
        assertNotNull(AgentCommandPolicy.rejectionReason("sed s/old/new/ file.txt"))
    }
}
