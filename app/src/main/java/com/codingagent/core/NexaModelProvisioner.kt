package com.codingagent.core

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
    const val assetRoot = "models/qwen3-8b-npu"
    const val packageName = "qwen3-8b-npu"
    const val modelName = "qwen3-8b"
    const val sdkVersion = "v0.2.53"
    private const val baseUrl = "https://huggingface.co/NexaAI/Qwen3-8B-NPU/resolve/main"

    val files: List<ModelShard> = listOf(
        ModelShard("files-1-2.nexa", 644L, "09e96eadb9dfc55116088098fe510ffacdf18e6725a3bda7ea214deb84bb103b"),
        ModelShard("files-2-2.nexa", 11422670L, "491c18ada2fc244ab71ca26cbc235abcd319a2a5570828fbc82287409b4fbdcd"),
        ModelShard("weights-1-5.nexa", 943585212L, "3353f6e25847d39223ca51f83dbe8614f98a6d9c32b1e6b5535d87f3989ef9f8"),
        ModelShard("weights-2-5.nexa", 965374644L, "41486a79dc2e38600618c516e8ffcf42a8fae578a1ffd15b14389d51e3793ae3"),
        ModelShard("weights-3-5.nexa", 965374788L, "b26a361cd8df81eed8a1eb2d6bdab79c61651b20fc75e9fe606791b8bd4314f0"),
        ModelShard("weights-4-5.nexa", 1722640740L, "0536c197ba9fa545bfe473f829b187c791b4c1ac8fb56c7ff268db0cb665232c"),
        ModelShard("weights-5-5.nexa", 2489319588L, "efd741e84aac373bd959163809323d4901125f913146badb2bd89a9302a5f715")
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
        var completed = Qwen3NpuPackage.files.sumOf { existingCompletedBytes(directory, it) }
        Qwen3NpuPackage.files.forEach { shard ->
            val destination = directory.resolve(shard.name)
            if (destination.length() == shard.sizeBytes && sha256(destination) == shard.sha256) {
                completed = Qwen3NpuPackage.files.takeWhile { it.name != shard.name }.sumOf { existingCompletedBytes(directory, it) } + shard.sizeBytes
                onProgress(ModelDownloadProgress(shard.name, completed, Qwen3NpuPackage.totalBytes, "verified"))
                return@forEach
            }
            if (destination.length() > shard.sizeBytes || (destination.length() == shard.sizeBytes && sha256(destination) != shard.sha256)) destination.delete()
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
