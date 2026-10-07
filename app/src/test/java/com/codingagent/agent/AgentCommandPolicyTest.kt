package com.codingagent.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentCommandPolicyTest {
    @Test
    fun allowsProjectInspectionCommands() {
        assertNull(AgentCommandPolicy.rejectionReason("git status --short"))
        assertNull(AgentCommandPolicy.rejectionReason("find app -type f"))
        assertNull(AgentCommandPolicy.rejectionReason("rg UserMemory app/src"))
        assertNull(AgentCommandPolicy.rejectionReason("cat app/src/main/AndroidManifest.xml"))
    }

    @Test
    fun allowsStandardVerificationGradleCommands() {
        assertNull(
            AgentCommandPolicy.rejectionReason(
                "./gradlew :app:testDebugUnitTest --no-daemon --console=plain"
            )
        )
        assertNull(
            AgentCommandPolicy.rejectionReason(
                "./gradlew :app:lintDebug :app:assembleDebug --no-daemon --console=plain"
            )
        )
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
