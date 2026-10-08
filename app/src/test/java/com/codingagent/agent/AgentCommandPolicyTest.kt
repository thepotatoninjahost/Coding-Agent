package com.codingagent.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.nio.file.Files

class AgentCommandPolicyTest {
    @Test
    fun allowsProjectInspectionCommands() {
        assertNull(AgentCommandPolicy.rejectionReason("git status --short"))
        assertNull(AgentCommandPolicy.rejectionReason("find app -type f"))
        assertNull(AgentCommandPolicy.rejectionReason("rg UserMemory app/src"))
        assertNull(AgentCommandPolicy.rejectionReason("cat app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun blocksProjectControlledBuildScriptsFromAutonomousCommands() {
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --no-daemon"))
        assertNotNull(AgentCommandPolicy.rejectionReason("gradle test"))
        assertNotNull(AgentCommandPolicy.rejectionReason("gradlew.bat test"))
    }

    @Test
    fun blocksShellGlobsThatCanExpandToSymlinkedPaths() {
        assertNotNull(AgentCommandPolicy.rejectionReason("cat *"))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg needle src/*"))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat src/?.txt"))
        assertNotNull(AgentCommandPolicy.rejectionReason("ls src/[ab].kt"))
        // Android's /system/bin/sh is mksh; brace and extended-glob expansion can
        // introduce absolute/out-of-root operands after the policy's path checks.
        assertNotNull(AgentCommandPolicy.rejectionReason("cat {README.md,/etc/passwd}"))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat @(/etc/passwd)"))
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
    fun blocksProjectControlledBuildScriptVariantsAndPathEscape() {
        assertNotNull(AgentCommandPolicy.rejectionReason("cat ../secrets.txt"))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat /data/data/example/file"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew test -I evil.init.gradle"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew test --scan"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew test -Dfoo=bar"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :exfiltrate"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew testExfiltrate"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testExfiltrate"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --project-cache-dir=/tmp/external-cache"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --project-cache-dir=../external-cache"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --write-verification-metadata sha256"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --write-locks"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --update-locks=org.example:library"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --export-keys"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --refresh-keys"))
        assertNotNull(AgentCommandPolicy.rejectionReason("./gradlew :app:testDebugUnitTest --dependency-verification=off"))
    }

    @Test
    fun blocksSymlinkedParentEvenWhenFinalTargetDoesNotExist() {
        val root = Files.createTempDirectory("command-policy-root").toFile()
        val outside = Files.createTempDirectory("command-policy-outside").toFile()
        val link = root.toPath().resolve("outside")
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Symbolic links are required for this regression test", false)
        }

        assertNotNull(
            AgentCommandPolicy.rejectionReason("cat outside/missing.txt", root)
        )
        assertNotNull(
            AgentCommandPolicy.rejectionReason("rg -n needle outside/missing.txt", root)
        )
    }

    @Test
    fun blocksDirectAccessToPrivateAgentMetadata() {
        val root = Files.createTempDirectory("command-policy-metadata").toFile()
        root.resolve(".coding-agent").mkdirs()
        root.resolve(".coding-agent/private-state.json").writeText("{\"secret\":true}")

        root.resolve(".git").mkdirs()
        root.resolve(".git/config").writeText("[core]\\n repositoryformatversion = 0\\n")
        assertNotNull(AgentCommandPolicy.rejectionReason("cat .coding-agent/private-state.json", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg secret .coding-agent/private-state.json", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("cat .git/config", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("find .coding-agent -type f", root))
        assertNull(AgentCommandPolicy.rejectionReason("cat README.md", root))
    }

    @Test
    fun blocksProjectLocalExecutablesThatMasqueradeAsAllowlistedTools() {
        val root = Files.createTempDirectory("command-policy-local-executable").toFile()
        root.resolve("rg").writeText("#!/system/bin/sh\\nprintf compromised\\n")
        root.resolve("cat").writeText("#!/system/bin/sh\\nprintf compromised\\n")

        assertNotNull(AgentCommandPolicy.rejectionReason("./rg secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("./cat README.md", root))
    }

    @Test
    fun blocksGitCommandsWhenRepositoryConfigEnablesExecutableHelpers() {
        val root = Files.createTempDirectory("command-policy-git-config").toFile()
        root.resolve(".git").mkdirs()
        root.resolve(".git/config").writeText("[core]\\n fsmonitor = ./untrusted-helper.sh\\n")

        assertNotNull(AgentCommandPolicy.rejectionReason("git status", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("git diff", root))

        root.resolve(".git/config").writeText(
            "[core]\\n repositoryformatversion = 0\\n filemode = true\\n bare = false\\n logallrefupdates = true\\n"
        )
        assertNull(AgentCommandPolicy.rejectionReason("git status", root))
    }

    @Test
    fun blocksRecursiveSearchesThatCouldReadPrivateMetadataOrFollowSymlinks() {
        val root = Files.createTempDirectory("command-policy-recursive-search").toFile()
        root.resolve(".coding-agent").mkdirs()
        root.resolve(".coding-agent/private-state.json").writeText("{\"secret\":true}")

        assertNotNull(AgentCommandPolicy.rejectionReason("grep -R secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("grep -r secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("grep -nR secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg --hidden secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg --no-ignore secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg --follow secret .", root))
        assertNotNull(AgentCommandPolicy.rejectionReason("rg -uu secret .", root))
    }

    @Test
    fun blocksCommandsThatCanExecuteExternalSearchPreprocessors() {
        val root = Files.createTempDirectory("command-policy-pre").toFile()
        assertNotNull(
            AgentCommandPolicy.rejectionReason("rg --pre sh needle .", root)
        )
        assertNotNull(
            AgentCommandPolicy.rejectionReason("grep -R needle outside", root)
        )
    }

    @Test
    fun blocksGitOptionsThatCanExecuteExternalToolsOrReadOutsideTheRepository() {
        assertNotNull(AgentCommandPolicy.rejectionReason("git diff --ext-diff"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git show --textconv HEAD"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git grep --open-files-in-pager=sh needle"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git diff --no-index /etc/passwd app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun blocksMutatingGitBranchOperations() {
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch feature"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch -D feature"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch -m old new"))
        assertNotNull(AgentCommandPolicy.rejectionReason("git branch -f feature"))
        assertNull(AgentCommandPolicy.rejectionReason("git branch"))
        assertNull(AgentCommandPolicy.rejectionReason("git branch --list feature"))
        assertNull(AgentCommandPolicy.rejectionReason("git branch --merged main"))
    }

    @Test
    fun blocksFindAndSedWrites() {
        assertNotNull(AgentCommandPolicy.rejectionReason("find app -exec rm {} ;"))
        assertNotNull(AgentCommandPolicy.rejectionReason("sed -i s/old/new/ file.txt"))
        assertNotNull(AgentCommandPolicy.rejectionReason("sed s/old/new/ file.txt"))
    }
}
