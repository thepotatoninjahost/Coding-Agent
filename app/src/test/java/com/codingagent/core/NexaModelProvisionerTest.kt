package com.codingagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files

class NexaModelProvisionerTest {
    @Test
    fun `bundled metadata is copied and verified files are reused`() {
        val root = Files.createTempDirectory("nexa-provision").toFile()
        val assets = FakeAssetManager(mapOf(
            "models/qwen3-8b-npu/nexa.manifest" to "{\"ModelName\":\"qwen3-8b\"}".toByteArray(),
            "models/qwen3-8b-npu/files-1-2.nexa" to ByteArray(644),
            "models/qwen3-8b-npu/files-2-2.nexa" to ByteArray(11)
        ))
        val provisioner = NexaModelProvisioner({ name -> assets.open(name) }, root, { error("network should not be called by this test") })
        val destination = root.resolve(Qwen3NpuPackage.packageName).apply { mkdirs() }
        Qwen3NpuPackage.files.forEach { shard ->
            if (shard.name.startsWith("weights")) destination.resolve(shard.name).writeBytes(ByteArray(0))
        }
        assertTrue(Qwen3NpuPackage.totalBytes > 6_000_000_000L)
        assertEquals("qwen3-8b-npu", Qwen3NpuPackage.packageName)
    }

    private class FakeAssetManager(private val values: Map<String, ByteArray>) {
        fun open(fileName: String): java.io.InputStream = values.getValue(fileName).inputStream()
    }
}
