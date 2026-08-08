package com.codingagent.model

import com.codingagent.live.ResumableFileDownloader
import com.codingagent.live.StorageGuard

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ModelDownloadProgress(
    val currentFile: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val phase: String,
    val error: String? = null
) {
    val percent: Int get() = if (totalBytes == 0L) 0 else ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

data class ProvisionedModel(val directory: File, val totalBytes: Long)

fun ProvisionedModel.modelPath(): File = directory

object Qwen3NpuPackage {
    const val assetRoot = "models/qwen3-4b-npu-mobile"
    const val packageName = "qwen3-4b-npu-mobile"
    const val modelName = "qwen3-4b"
    const val sdkVersion = "v0.2.53"
    private const val baseUrl = "https://huggingface.co/NexaAI/Qwen3-4B-Instruct-2507-npu-mobile/resolve/main"

    val files: List<ModelShard> = listOf(
        ModelShard("attachments-1-1.nexa", 575L, "3561414751276171067a70c1f72e4a4a42e0d00161c731ed6c2f29517ee53874"),
        ModelShard("files-1-1.nexa", 11422670L, "bf63e9e2b4960732b5ed44d5950f22d134101e0ab9712a5399ca3778b299fbc1"),
        ModelShard("weights-1-3.nexa", 1050538636L, "c49de0a951471875604c52c26782ccc41469a297f324ad7669a535ae114d6a87"),
        ModelShard("weights-2-3.nexa", 1506706324L, "8ccb9815d520ada5052e2f0fdb2e3d5596168ca626e0c14850e4294e95b5d7b4"),
        ModelShard("weights-3-3.nexa", 1555824804L, "04841b614cebdcad2e1a4a23458438cc2e8871b1467ad9f156ed38f8b3dd5bd8")
    )

    val totalBytes: Long = files.sumOf { it.sizeBytes }

    fun url(fileName: String): String = "$baseUrl/$fileName?download=true"
}

data class ModelShard(val name: String, val sizeBytes: Long, val sha256: String)

class NexaModelProvisioner(
    private val assetOpener: (String) -> java.io.InputStream,
    private val root: File,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val maxAttempts: Int = 8
) {
    fun ensure(onProgress: (ModelDownloadProgress) -> Unit = {}): ProvisionedModel {
        val directory = root.resolve(Qwen3NpuPackage.packageName).apply { mkdirs() }
        copyBundledFiles(directory)
        val remaining = remainingDownloadBytes(directory)
        if (remaining > 0L) {
            onProgress(
                ModelDownloadProgress(
                    currentFile = "storage-check",
                    completedBytes = Qwen3NpuPackage.totalBytes - remaining,
                    totalBytes = Qwen3NpuPackage.totalBytes,
                    phase = "checking-storage"
                )
            )
            val space = StorageGuard.requireSpace(directory, remaining)
            onProgress(
                ModelDownloadProgress(
                    currentFile = "storage-check",
                    completedBytes = Qwen3NpuPackage.totalBytes - remaining,
                    totalBytes = Qwen3NpuPackage.totalBytes,
                    phase = "storage-ok",
                    error = space.humanMessage()
                )
            )
        }
        var completed = Qwen3NpuPackage.files.sumOf { existingCompletedBytes(directory, it) }
        Qwen3NpuPackage.files.forEach { shard ->
            val destination = directory.resolve(shard.name)
            if (destination.length() == shard.sizeBytes && sha256(destination) == shard.sha256) {
                completed = Qwen3NpuPackage.files.takeWhile { it.name != shard.name }.sumOf { existingCompletedBytes(directory, it) } + shard.sizeBytes
                onProgress(ModelDownloadProgress(shard.name, completed, Qwen3NpuPackage.totalBytes, "verified"))
                return@forEach
            }
            if (destination.length() > shard.sizeBytes || (destination.length() == shard.sizeBytes && sha256(destination) != shard.sha256)) destination.delete()
            val stillNeeded = remainingDownloadBytes(directory)
            if (stillNeeded > 0L) {
                runCatching { StorageGuard.requireSpace(directory, stillNeeded) }
                    .onFailure { error ->
                        onProgress(
                            ModelDownloadProgress(
                                shard.name,
                                completed,
                                Qwen3NpuPackage.totalBytes,
                                "blocked",
                                error.message
                            )
                        )
                        throw error
                    }
            }
            download(shard, destination, completed, onProgress)
            completed += shard.sizeBytes
        }
        val invalid = Qwen3NpuPackage.files.filter { shard ->
            val file = directory.resolve(shard.name)
            file.length() != shard.sizeBytes || sha256(file) != shard.sha256
        }
        require(invalid.isEmpty()) { "Model verification failed for ${invalid.joinToString { it.name }}" }
        return ProvisionedModel(directory, Qwen3NpuPackage.totalBytes)
    }

    fun remainingDownloadBytes(directory: File = root.resolve(Qwen3NpuPackage.packageName)): Long {
        return Qwen3NpuPackage.files.sumOf { shard ->
            val file = directory.resolve(shard.name)
            when {
                !file.isFile -> shard.sizeBytes
                file.length() == shard.sizeBytes -> 0L
                file.length() < shard.sizeBytes -> shard.sizeBytes - file.length()
                else -> shard.sizeBytes
            }
        }
    }

    private fun copyBundledFiles(directory: File) {
        val manifest = directory.resolve("nexa.manifest")
        if (!manifest.isFile || manifest.length() == 0L) {
            assetOpener("${Qwen3NpuPackage.assetRoot}/nexa.manifest").use { input -> manifest.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    private fun download(shard: ModelShard, destination: File, completedBefore: Long, onProgress: (ModelDownloadProgress) -> Unit) {
        ResumableFileDownloader(connectionFactory).download(
            Qwen3NpuPackage.url(shard.name), destination, shard.sizeBytes, shard.sha256
        ) { downloaded ->
            onProgress(ModelDownloadProgress(shard.name, completedBefore + downloaded, Qwen3NpuPackage.totalBytes, "downloading"))
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun existingCompletedBytes(directory: File, shard: ModelShard): Long = directory.resolve(shard.name).takeIf { it.length() == shard.sizeBytes }?.length() ?: 0L

}
