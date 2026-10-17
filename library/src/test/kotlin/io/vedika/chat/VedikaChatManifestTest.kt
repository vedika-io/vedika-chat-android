// Tests for the manifest verifier.
//
// The verifier is the security boundary: a bundle whose manifest does
// not verify MUST NOT load. These tests cover the happy path and the
// failure paths (missing manifest, missing file, hash mismatch, size
// mismatch) the verifier must reject.

package io.vedika.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class VedikaChatManifestTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("vedika-chat-manifest-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun writeBundle(
        files: Map<String, String> = mapOf(
            "index.html" to "<!doctype html><html><body>hi</body></html>",
            "runtime.js" to "(function(){ window.__VEDIKA_CHAT_FIXTURE__=true; })();"
        )
    ): File {
        val dir = File(tempDir, "bundle-${java.util.UUID.randomUUID()}")
        dir.mkdirs()
        val entries = files.map { (path, contents) ->
            val bytes = contents.toByteArray(Charsets.UTF_8)
            File(dir, path).writeBytes(bytes)
            VedikaChatManifestEntry(path = path, sha256 = sha256Hex(bytes), size = bytes.size.toLong())
        }
        val manifest = VedikaChatManifest(schema = 1, entry = "index.html", entries = entries)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        File(dir, "manifest.json").writeText(
            json.encodeToString(VedikaChatManifest.serializer(), manifest)
        )
        return dir
    }

    @Test
    fun happyPath() {
        val dir = writeBundle()
        val result = VedikaChatManifestLoader.loadAndVerify(dir)
        assertTrue(result.isSuccess)
        val manifest = result.getOrNull()
        assertNotNull(manifest)
        assertEquals(1, manifest!!.schema)
        assertEquals("index.html", manifest.entry)
        assertEquals(2, manifest.entries.size)
    }

    @Test
    fun hashMismatch() {
        val dir = writeBundle()
        // Corrupt the file content but leave the manifest hashes alone.
        File(dir, "index.html").writeText("garbage")
        val result = VedikaChatManifestLoader.loadAndVerify(dir)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Expected HashMismatch, got $err", err is VedikaChatManifestError.HashMismatch)
    }

    @Test
    fun missingManifest() {
        val dir = File(tempDir, "no-manifest-${java.util.UUID.randomUUID()}")
        dir.mkdirs()
        val result = VedikaChatManifestLoader.loadAndVerify(dir)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Expected MissingManifest, got $err", err is VedikaChatManifestError.MissingManifest)
    }

    @Test
    fun missingFile() {
        val dir = File(tempDir, "missing-file-${java.util.UUID.randomUUID()}")
        dir.mkdirs()
        val manifest = VedikaChatManifest(
            schema = 1,
            entry = "absent.html",
            entries = listOf(
                VedikaChatManifestEntry(
                    path = "absent.html",
                    sha256 = sha256Hex("x".toByteArray())
                )
            )
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        File(dir, "manifest.json").writeText(
            json.encodeToString(VedikaChatManifest.serializer(), manifest)
        )
        val result = VedikaChatManifestLoader.loadAndVerify(dir)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Expected MissingFile, got $err", err is VedikaChatManifestError.MissingFile)
    }

    @Test
    fun sizeMismatch() {
        val dir = writeBundle()
        val bytes = File(dir, "index.html").readBytes()
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val manifest = VedikaChatManifest(
            schema = 1,
            entry = "index.html",
            entries = listOf(
                VedikaChatManifestEntry(
                    path = "index.html",
                    sha256 = sha256Hex(bytes),
                    size = bytes.size.toLong() + 1
                ),
                VedikaChatManifestEntry(
                    path = "runtime.js",
                    sha256 = sha256Hex("ok".toByteArray()),
                    size = 2
                )
            )
        )
        File(dir, "runtime.js").writeText("ok")
        File(dir, "manifest.json").writeText(
            json.encodeToString(VedikaChatManifest.serializer(), manifest)
        )
        val result = VedikaChatManifestLoader.loadAndVerify(dir)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Expected SizeMismatch, got $err", err is VedikaChatManifestError.SizeMismatch)
    }

    @Test
    fun invalidManifestJson() {
        val dir = File(tempDir, "bad-json-${java.util.UUID.randomUUID()}")
        dir.mkdirs()
        File(dir, "manifest.json").writeText("not json")
        val result = VedikaChatManifestLoader.loadAndVerify(dir)
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("Expected InvalidManifestJson, got $err", err is VedikaChatManifestError.InvalidManifestJson)
    }
}
