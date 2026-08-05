package com.codingagent.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
        while (attempts < 12) {
            try {
                transfer(url, destination, expectedBytes, onProgress)
                val actual = sha256(destination)
                if (destination.length() == expectedBytes && actual == expectedSha256) return
                throw DownloadBlockedException("Verification failed: ${destination.name} has ${destination.length()}/$expectedBytes bytes")
            } catch (error: DownloadBlockedException) {
                if (++attempts >= 12) throw error
                Thread.sleep((attempts * 2_000L).coerceAtMost(30_000L))
            } catch (error: Exception) {
                if (++attempts >= 12) throw DownloadBlockedException("Download blocked for ${destination.name} after $attempts attempts; partial data was kept for resume", error)
                Thread.sleep((attempts * 2_000L).coerceAtMost(30_000L))
            }
        }
    }

    private fun transfer(url: String, destination: File, expectedBytes: Long, onProgress: (Long) -> Unit) {
        val existing = destination.length().coerceAtMost(expectedBytes)
        var connection = connectionFactory(url)
        var resumed = existing > 0L
        try {
            configure(connection, resumed, existing)
            connection.connect()
            var response = connection.responseCode
            if (response == 301 || response == 302 || response == 303 || response == 307) {
                val location = connection.getHeaderField("Location")
                    ?: throw DownloadBlockedException("HTTP $response ${connection.responseMessage ?: "Redirect without Location"}")
                connection.disconnect()
                connection = connectionFactory(location)
                configure(connection, resumed, existing)
                connection.connect()
                response = connection.responseCode
            }
            if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                val message = connection.responseMessage.orEmpty().ifBlank { "Unknown response" }
                throw DownloadBlockedException("HTTP $response $message")
            }
            if (resumed && response == HttpURLConnection.HTTP_OK) {
                destination.delete()
                resumed = false
                val retryUrl = connection.url.toString()
                connection.disconnect()
                connection = connectionFactory(retryUrl)
                configure(connection, false, 0L)
                connection.connect()
                response = connection.responseCode
                if (response == 301 || response == 302 || response == 303 || response == 307) {
                    val location = connection.getHeaderField("Location")
                        ?: throw DownloadBlockedException("HTTP $response ${connection.responseMessage ?: "Redirect without Location"}")
                    connection.disconnect()
                    connection = connectionFactory(location)
                    configure(connection, false, 0L)
                    connection.connect()
                    response = connection.responseCode
                }
                if (response != HttpURLConnection.HTTP_OK) {
                    val message = connection.responseMessage.orEmpty().ifBlank { "Unknown response" }
                    throw DownloadBlockedException("HTTP $response $message")
                }
            }
            val append = resumed && response == HttpURLConnection.HTTP_PARTIAL
            val start = if (append) existing else 0L
            var downloaded = start
            connection.inputStream.use { input ->
                java.io.RandomAccessFile(destination, "rw").use { output ->
                    if (append) output.seek(start) else output.setLength(0L)
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

    private fun configure(connection: HttpURLConnection, resume: Boolean, existing: Long) {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 60_000
        connection.readTimeout = 600_000
        connection.setRequestProperty("User-Agent", "CodingAgent/1.0 (Android)")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (resume && existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
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
