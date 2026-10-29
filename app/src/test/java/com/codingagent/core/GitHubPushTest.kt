package com.codingagent.core

import com.codingagent.github.GitHubFile
import com.codingagent.github.GitHubPushClient
import com.codingagent.github.GitHubPushRequest
import com.codingagent.github.GitHubPushResult
import com.codingagent.github.GitHubReply
import com.codingagent.github.GitHubTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPushTest {
    @Test
    fun branchMovesOnlyAfterTheCommitExists() {
        val calls = mutableListOf<String>()
        val transport = GitHubTransport { method, url, _, _ ->
            calls += "$method ${url.substringAfter("/repos/owner/name")}"
            when {
                method == "GET" && url.endsWith("/git/ref/heads/main") ->
                    GitHubReply(200, """{"object":{"sha":"abc123"}}""", false)
                method == "GET" && url.contains("/git/commits/") ->
                    GitHubReply(200, """{"tree":{"sha":"tree1"}}""", false)
                method == "POST" && url.endsWith("/git/blobs") ->
                    GitHubReply(200, """{"sha":"blob1"}""", false)
                method == "POST" && url.endsWith("/git/trees") ->
                    GitHubReply(200, """{"sha":"tree2"}""", false)
                method == "POST" && url.endsWith("/git/commits") ->
                    GitHubReply(200, """{"sha":"commit2","html_url":"https://github.com/owner/name/commit/commit2"}""", false)
                method == "PATCH" ->
                    GitHubReply(200, """{"ref":"refs/heads/main"}""", false)
                else -> GitHubReply(500, """{"message":"unexpected"}""", false)
            }
        }
        val result = GitHubPushClient(transport).push(
            GitHubPushRequest(
                repo = "owner/name",
                branch = "main",
                message = "Update from the phone",
                files = listOf(GitHubFile("src/A.kt", "hi".toByteArray()))
            ),
            "ghp_secret"
        )
        assertTrue(result is GitHubPushResult.Pushed)
        assertEquals("commit2", (result as GitHubPushResult.Pushed).sha)
        assertEquals(
            listOf(
                "GET /git/ref/heads/main",
                "GET /git/commits/abc123",
                "POST /git/blobs",
                "POST /git/trees",
                "POST /git/commits",
                "PATCH /git/ref/heads/main"
            ),
            calls
        )
    }

    @Test
    fun redirectStopsTheSendAndATokenInAnErrorIsHidden() {
        val redirect = GitHubPushClient(GitHubTransport { _, _, _, _ ->
            GitHubReply(302, "", true)
        }).push(sample(), "ghp_secret")
        assertTrue(redirect is GitHubPushResult.Failed)
        assertTrue((redirect as GitHubPushResult.Failed).reason.contains("different address"))
        assertFalse(redirect.reason.contains("ghp_secret"))

        val rejected = GitHubPushClient(GitHubTransport { _, _, _, _ ->
            GitHubReply(401, """{"message":"bad token ghp_secret"}""", false)
        }).push(sample(), "ghp_secret")
        assertTrue(rejected is GitHubPushResult.Failed)
        assertFalse((rejected as GitHubPushResult.Failed).reason.contains("ghp_secret"))
        assertTrue(rejected.reason.contains("[redacted]"))
    }

    private fun sample() = GitHubPushRequest(
        repo = "owner/name",
        branch = "main",
        message = "Update from the phone",
        files = listOf(GitHubFile("src/A.kt", "hi".toByteArray()))
    )
}
