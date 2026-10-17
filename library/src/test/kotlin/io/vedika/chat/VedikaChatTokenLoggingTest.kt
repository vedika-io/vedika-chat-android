// Tests that prove the chat library never logs the token.
//
// The Android WebView wrapper has only one place where a log call
// could plausibly receive the token value: if a future refactor
// accidentally pipes `request`, `token`, or the bridge reply through
// `Log.*`, `println`, or `System.out.*`. These tests scan the
// production source for every such call and assert the inputs do not
// reference the token field.
//
// The library is also exercised end-to-end with a captured logger so
// the no-token-in-logs invariant is verified at runtime, not just
// statically. A capturing logger is installed before any bridge call,
// the bridge is exercised with a known token value, and the
// captured log list is asserted to contain none of the secret.
//
// The marker itself is generated at runtime from a UUID — a static
// marker would trip GitGuardian's secret scanner on every PR. The
// invariant still holds: the bridge never sees the marker outside the
// token payload, and the logger never sees the token at all.

package io.vedika.chat

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class VedikaChatTokenLoggingTest {

    /** A UUID-shaped marker that mimics a real token. Generated fresh
     * per test invocation so the string never appears as a literal in
     * source and never trips the GitGuardian scanner on a diff. */
    private fun freshSecretMarker(): String =
        "tk_" + UUID.randomUUID().toString().replace("-", "")

    private val originalLogger = currentLogger()
    private val captured = mutableListOf<String>()

    private fun currentLogger(): VedikaChatBridgeLogger =
        VedikaChatLogging.peek()

    @Before
    fun setUp() {
        captured.clear()
        VedikaChatLogging.install { event -> captured.add(event) }
    }

    @After
    fun tearDown() {
        VedikaChatLogging.install(originalLogger)
    }

    /**
     * A real `acquireToken` round-trip with a token whose value is
     * the secret marker. The bridge MUST NOT log the marker.
     */
    @Test
    fun acquireTokenDoesNotLeakThroughLogger() = runBlocking {
        val secretMarker = freshSecretMarker()
        val secret = VedikaChatToken(value = secretMarker, expiresAt = 1)
        val provider = VedikaChatTokenProvider { _ -> secret }
        val core = VedikaChatBridgeCore(provider)
        val reply = core.handle(
            """{"kind":"acquireToken","payload":{"acquireToken":{"reason":"init","threadId":null}}}"""
        )
        // The reply legitimately contains the token (it must, the
        // page asked for it), but the LOGGER MUST NOT.
        assertTrue("bridge reply should contain the token", reply.contains(secretMarker))
        assertNoCapturedEventContains(secretMarker)
        assertFalse(
            "logger captured something",
            captured.any { it.contains(secretMarker) }
        )
    }

    /**
     * A provider that throws with a message containing the token
     * value. The provider error path MUST NOT log the token either.
     */
    @Test
    fun providerErrorDoesNotLeakThroughLogger() = runBlocking {
        val secretMarker = freshSecretMarker()
        val provider = VedikaChatTokenProvider { _ ->
            throw IllegalStateException("explosion: $secretMarker")
        }
        val core = VedikaChatBridgeCore(provider)
        core.handle(
            """{"kind":"acquireToken","payload":{"acquireToken":{"reason":"init","threadId":null}}}"""
        )
        // The bridge DOES surface the provider's error message back
        // to the page (it's the page's job to display it). We are
        // only asserting the LOGGER never received the secret.
        assertNoCapturedEventContains(secretMarker)
    }

    /**
     * Static check: production source must not contain any log-style
     * call (Log.*, println, System.out.*, System.err.*, Throwable.printStackTrace)
     * that references the token field.
     *
     * The check uses a simple regex against the bundled library
     * source tree. If this test fails, a contributor has wired the
     * token into a log line.
     */
    @Test
    fun noLogCallReferencesTokenField() {
        val libDir = locateLibrarySource()
        val ktFiles = libDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains("/test/") }
            .toList()

        assertTrue(
            "expected library sources under ${libDir.absolutePath}, found ${ktFiles.size} files",
            ktFiles.isNotEmpty()
        )

        val tokenReferences = listOf("token", "Token")
        val logPatterns = listOf(
            Regex("""\bLog\.[a-z]+\s*\("""),
            Regex("""\bprintln\s*\("""),
            Regex("""\bSystem\.out\.print"""),
            Regex("""\bSystem\.err\.print"""),
            Regex("""\.printStackTrace\s*\(""")
        )

        val offenders = mutableListOf<String>()
        for (file in ktFiles) {
            val text = file.readText()
            for (pattern in logPatterns) {
                for (match in pattern.findAll(text)) {
                    val idx = match.range.first
                    val lineStart = text.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
                    val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
                    val line = text.substring(lineStart, lineEnd)
                    if (tokenReferences.any { line.contains(it) }) {
                        offenders.add("${file.name}:$line")
                    }
                }
            }
        }
        assertTrue(
            "Library must not log token fields. Offending lines:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * The bridge reply path MUST serialise the token (the page needs
     * it). The logger, however, only sees fixed event codes.
     */
    @Test
    fun loggerOnlyReceivesFixedEventCodes() = runBlocking {
        val secretMarker = freshSecretMarker()
        val secret = VedikaChatToken(value = secretMarker, expiresAt = 0)
        val core = VedikaChatBridgeCore { _ -> secret }
        core.handle("""{"kind":"acquireToken","payload":{"acquireToken":{"reason":"init","threadId":"t"}}}""")
        // Acquire-token path itself does not emit any log event.
        assertEquals(0, captured.size)
    }

    private fun assertNoCapturedEventContains(needle: String) {
        val hits = captured.filter { it.contains(needle) }
        assertTrue(
            "logger captured events containing secret: $hits",
            hits.isEmpty()
        )
    }

    /**
     * Walk up from the working directory until we find a folder whose
     * layout matches the library source layout. This avoids hard-
     * coding absolute paths.
     */
    private fun locateLibrarySource(): File {
        var dir: File? = File(".").canonicalFile
        repeat(6) {
            val candidate = File(dir, "src/main/kotlin/io/vedika/chat")
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile ?: return@repeat
        }
        throw IllegalStateException("could not locate library source directory")
    }
}

/**
 * Test-only accessor for the currently-installed logger so the test
 * can restore it after installing a capturing one.
 */
internal fun VedikaChatLogging.peek(): VedikaChatBridgeLogger {
    val field = VedikaChatLogging::class.java.getDeclaredField("logger")
    field.isAccessible = true
    return field.get(VedikaChatLogging) as VedikaChatBridgeLogger
}