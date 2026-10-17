// Manifest verification for the bundled chat page.
//
// Each chat bundle ships a `manifest.json` next to `index.html` listing
// every file in the bundle and its SHA-256 hash. The chat WebView
// refuses to load any bundle whose manifest does not verify.
//
// Mirrors the Swift `VedikaChatManifest` byte-for-byte so the same
// exported bundle verifies on both platforms.

package io.vedika.chat

import java.io.File
import java.security.MessageDigest

@kotlinx.serialization.Serializable
data class VedikaChatManifestEntry(
    val path: String,
    val sha256: String,
    val size: Long? = null
)

/** A chat bundle manifest. */
@kotlinx.serialization.Serializable
data class VedikaChatManifest(
    val schema: Int,
    val entry: String,
    val entries: List<VedikaChatManifestEntry>
)

/** Typed errors from [VedikaChatManifest.loadAndVerify]. */
sealed class VedikaChatManifestError(message: String) : RuntimeException(message) {

    class MissingManifest(path: String) :
        VedikaChatManifestError("Manifest not found at $path.")

    class InvalidManifestJson(path: String) :
        VedikaChatManifestError("Manifest at $path is not valid JSON.")

    class MissingFile(path: String) :
        VedikaChatManifestError("Manifest references $path, which is missing from the bundle.")

    class HashMismatch(path: String, expected: String, actual: String) :
        VedikaChatManifestError("Hash mismatch for $path: expected $expected, got $actual.")

    class SizeMismatch(path: String, expected: Long, actual: Long) :
        VedikaChatManifestError("Size mismatch for $path: expected $expected bytes, got $actual.")
}

object VedikaChatManifestLoader {

    /**
     * Loads and verifies a manifest at [bundleDir]. The result is either
     * the parsed [VedikaChatManifest] or a typed
     * [VedikaChatManifestError].
     */
    fun loadAndVerify(bundleDir: File): Result<VedikaChatManifest> {
        val manifestFile = File(bundleDir, "manifest.json")
        if (!manifestFile.exists()) {
            return Result.failure(VedikaChatManifestError.MissingManifest(manifestFile.path))
        }

        val manifest = try {
            json.decodeFromString(VedikaChatManifest.serializer(), manifestFile.readText())
        } catch (e: Throwable) {
            return Result.failure(VedikaChatManifestError.InvalidManifestJson(manifestFile.path))
        }

        for (entry in manifest.entries) {
            val file = File(bundleDir, entry.path)
            if (!file.exists()) {
                return Result.failure(VedikaChatManifestError.MissingFile(file.path))
            }
            val bytes = file.readBytes()
            val actual = sha256Hex(bytes)
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                return Result.failure(
                    VedikaChatManifestError.HashMismatch(
                        path = file.path,
                        expected = entry.sha256,
                        actual = actual
                    )
                )
            }
            entry.size?.let { expected ->
                if (bytes.size.toLong() != expected) {
                    return Result.failure(
                        VedikaChatManifestError.SizeMismatch(
                            path = file.path,
                            expected = expected,
                            actual = bytes.size.toLong()
                        )
                    )
                }
            }
        }

        return Result.success(manifest)
    }

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
