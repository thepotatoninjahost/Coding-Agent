package com.codingagent.github

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import com.codingagent.workspace.ChangeOperation
import com.codingagent.workspace.ChangeRecord

/**
 * Sends one saved set of files to GitHub as a single commit.
 * The branch moves only after the commit exists. A redirect is refused.
 */
data class GitHubFile(val path: String, val bytes: ByteArray, val delete: Boolean = false)

data class GitHubPushRequest(
    val repo: String,
    val branch: String,
    val message: String,
    val files: List<GitHubFile>
)

sealed class GitHubPushResult {
    data class Ready(val files: List<GitHubFile>) : GitHubPushResult()
    data class Pushed(val sha: String, val url: String) : GitHubPushResult()
    data class Failed(val reason: String) : GitHubPushResult()
}

fun interface GitHubTransport {
    fun exchange(method: String, url: String, body: String?, token: String): GitHubReply
}

data class GitHubReply(val code: Int, val body: String, val redirected: Boolean)

object GitHubPushFingerprint {
    fun of(files: List<GitHubFile>): String {
        val material = files.joinToString("\n") { file ->
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.bytes)
            val hex = digest.joinToString("") { "%02x".format(it) }
            listOf(file.path, file.delete.toString(), hex).joinToString("\u0000")
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object GitHubChangeFiles {
    const val MAX_FILES = 40
    const val MAX_BYTES = 1_000_000

    fun fromChanges(root: File, changes: List<ChangeRecord>): GitHubPushResult {
        if (changes.isEmpty()) return GitHubPushResult.Failed("Nothing saved is waiting to send.")
        if (changes.size > MAX_FILES) {
            return GitHubPushResult.Failed("That is too many files to send at once.")
        }
        val files = mutableListOf<GitHubFile>()
        for (change in changes) {
            val path = change.path.replace('\\', '/').trim().trimStart('/')
            if (path.isBlank() || path.split('/').any { it == ".." || it.isBlank() }) {
                return GitHubPushResult.Failed("One of the file names is not safe to send.")
            }
            if (change.operation == ChangeOperation.REMOVE) {
                files += GitHubFile(path, ByteArray(0), delete = true)
                continue
            }
            val file = root.resolve(path)
            if (!file.isFile) return GitHubPushResult.Failed("I could not read $path, so nothing was sent.")
            val bytes = file.readBytes()
            if (bytes.size > MAX_BYTES) {
                return GitHubPushResult.Failed("$path is too large to send from the phone.")
            }
            files += GitHubFile(path, bytes)
        }
        return GitHubPushResult.Ready(files)
    }
}

class GitHubPushClient(private val transport: GitHubTransport) {
    fun push(request: GitHubPushRequest, token: String): GitHubPushResult {
        val cleanToken = token.filterNot { it.isWhitespace() }
        if (cleanToken.isBlank()) {
            return GitHubPushResult.Failed("Paste a GitHub token in Setup first. It stays on this phone.")
        }
        if (!REPO.matches(request.repo)) {
            return GitHubPushResult.Failed("The GitHub project name should look like owner/name.")
        }
        if (!BRANCH.matches(request.branch)) {
            return GitHubPushResult.Failed("The branch name is not one I can send to.")
        }
        if (request.files.isEmpty()) return GitHubPushResult.Failed("Nothing saved is waiting to send.")
        val root = "https://api.github.com/repos/${request.repo}"
        val refUrl = "$root/git/ref/heads/${request.branch}"
        val ref = call("GET", refUrl, null, cleanToken) ?: return failedRedirect(cleanToken)
        if (ref.code !in 200..299) return fail(ref, cleanToken, "I could not read the GitHub branch.")
        val commitSha = runCatching { JSONObject(ref.body).getJSONObject("object").getString("sha") }.getOrNull()
            ?: return GitHubPushResult.Failed("GitHub did not tell me the current commit. Nothing was sent.")
        val commit = call("GET", "$root/git/commits/$commitSha", null, cleanToken) ?: return failedRedirect(cleanToken)
        if (commit.code !in 200..299) return fail(commit, cleanToken, "I could not read the current commit. Nothing was sent.")
        val treeSha = runCatching { JSONObject(commit.body).getJSONObject("tree").getString("sha") }.getOrNull()
            ?: return GitHubPushResult.Failed("GitHub did not tell me the current file tree. Nothing was sent.")
        val treeEntries = JSONArray()
        for (file in request.files) {
            val entry = JSONObject().put("path", file.path).put("mode", "100644").put("type", "blob")
            if (file.delete) {
                entry.put("sha", JSONObject.NULL)
            } else {
                val blobBody = JSONObject()
                    .put("content", Base64.getEncoder().encodeToString(file.bytes))
                    .put("encoding", "base64")
                    .toString()
                val blob = call("POST", "$root/git/blobs", blobBody, cleanToken) ?: return failedRedirect(cleanToken)
                if (blob.code !in 200..299) return fail(blob, cleanToken, "I could not upload ${file.path}. Nothing was sent.")
                val blobSha = runCatching { JSONObject(blob.body).getString("sha") }.getOrNull()
                    ?: return GitHubPushResult.Failed("GitHub did not accept ${file.path}. Nothing was sent.")
                entry.put("sha", blobSha)
            }
            treeEntries.put(entry)
        }
        val treeBody = JSONObject().put("base_tree", treeSha).put("tree", treeEntries).toString()
        val tree = call("POST", "$root/git/trees", treeBody, cleanToken) ?: return failedRedirect(cleanToken)
        if (tree.code !in 200..299) return fail(tree, cleanToken, "I could not build the new file tree. Nothing was sent.")
        val newTree = runCatching { JSONObject(tree.body).getString("sha") }.getOrNull()
            ?: return GitHubPushResult.Failed("GitHub did not accept the file tree. Nothing was sent.")
        val commitBody = JSONObject()
            .put("message", request.message.ifBlank { "Update from the phone" })
            .put("tree", newTree)
            .put("parents", JSONArray().put(commitSha))
            .toString()
        val created = call("POST", "$root/git/commits", commitBody, cleanToken) ?: return failedRedirect(cleanToken)
        if (created.code !in 200..299) return fail(created, cleanToken, "I could not create the commit. The branch was not moved.")
        val newSha = runCatching { JSONObject(created.body).getString("sha") }.getOrNull()
            ?: return GitHubPushResult.Failed("GitHub did not return the new commit. The branch was not moved.")
        val updated = call("PATCH", refUrl, JSONObject().put("sha", newSha).toString(), cleanToken)
            ?: return failedRedirect(cleanToken)
        if (updated.code !in 200..299) return fail(updated, cleanToken, "The commit was created but the branch was not updated.")
        val url = runCatching { JSONObject(created.body).optString("html_url") }.getOrDefault("")
            .ifBlank { "https://github.com/${request.repo}/commit/$newSha" }
        return GitHubPushResult.Pushed(newSha, url)
    }

    private fun call(method: String, url: String, body: String?, token: String): GitHubReply? {
        val reply = transport.exchange(method, url, body, token)
        if (reply.redirected || reply.code in 300..399) return null
        return reply
    }

    private fun failedRedirect(token: String): GitHubPushResult =
        GitHubPushResult.Failed(redact("GitHub tried to send me to a different address. I stopped, and nothing was sent.", token))

    private fun fail(reply: GitHubReply, token: String, fallback: String): GitHubPushResult {
        val message = runCatching { JSONObject(reply.body).optString("message") }.getOrDefault("").ifBlank { fallback }
        return GitHubPushResult.Failed(redact(message, token))
    }

    private fun redact(text: String, token: String): String {
        if (token.isBlank()) return text
        return text.replace(token, "[redacted]")
    }

    companion object {
        private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val BRANCH = Regex("^[A-Za-z0-9._/-]+$")
    }
}

class UrlConnectionGitHubTransport : GitHubTransport {
    override fun exchange(method: String, url: String, body: String?, token: String): GitHubReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Coding-Agent")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(UTF_8)) }
            }
            val code = connection.responseCode
            if (code in 300..399) return GitHubReply(code, "", true)
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            GitHubReply(code, text, false)
        } finally {
            connection.disconnect()
        }
    }
}
