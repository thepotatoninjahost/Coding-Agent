package com.codingagent.core

import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

class DownloadBlockedException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ResumableFileDownloader(
    private val connectionFactory: (String) -> HttpURLConnection
) {
    fun download(
        url: String,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Long) -> Unit = {}
    ) {
        destination.parentFile?.mkdirs()
        var attempts = 0
        while (attempts < 8) {
            try {
                transfer(url, destination, expectedBytes, onProgress)
                val actual = sha256(destination)
                if (destination.length() == expectedBytes && actual == expectedSha256) return
                throw DownloadBlockedException("Verification failed: ${destination.name} has ${destination.length()}/$expectedBytes bytes")
            } catch (error: DownloadBlockedException) {
                if (++attempts >= 8) throw error
                Thread.sleep((attempts * 1_000L).coerceAtMost(8_000L))
            } catch (error: Exception) {
                if (++attempts >= 8) throw DownloadBlockedException("Download blocked for ${destination.name} after $attempts attempts; partial data was kept for resume", error)
                Thread.sleep((attempts * 1_000L).coerceAtMost(8_000L))
            }
        }
    }

    private fun transfer(url: String, destination: File, expectedBytes: Long, onProgress: (Long) -> Unit) {
        val existing = destination.length().coerceAtMost(expectedBytes)
        val connection = connectionFactory(url)
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
            connection.connect()
            val response = connection.responseCode
            if (response !in 200..299) throw DownloadBlockedException("HTTP $response")
            val append = existing > 0L && response == HttpURLConnection.HTTP_PARTIAL
            val start = if (append) existing else 0L
            if (!append && existing > 0L) destination.delete()
            var downloaded = start
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    if (append) output.channel.position(start)
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded)
                    }
                }
            }
            if (downloaded > expectedBytes) throw DownloadBlockedException("Server returned too many bytes: $downloaded/$expectedBytes")
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
