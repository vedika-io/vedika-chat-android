// Tests for `VedikaChatBridgeCore`.
//
// The bridge is the only native <-> page handshake. It must:
//   * Decode the page's envelope.
//   * Forward `acquireToken` to the host's `VedikaChatTokenProvider`.
//   * Reply with a typed `VedikaChatBridgeReply`.
//   * Refuse unknown kinds with a typed error.
//
// These tests use `runBlocking` so the suspending `handle` can be
// driven deterministically. Real Android flow uses the same code path
// inside the `WebViewCompat.addWebMessageListener` callback.

package io.vedika.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VedikaChatBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeReply(reply: String): VedikaChatBridgeReply =
        json.decodeFromString(VedikaChatBridgeReply.serializer(), reply)

    @Test
    fun acquireTokenForwardsRequest() = runBlocking {
        val expected = VedikaChatToken(value = "tk_test_abc", expiresAt = 1_900_000_000)
        val provider = VedikaChatTokenProvider { request ->
            assertEquals(VedikaChatTokenRequest.Reason.INIT, request.reason)
            assertEquals("thread-1", request.threadId)
            expected
        }
        val core = VedikaChatBridgeCore(provider)
        val request = """{"kind":"acquireToken","payload":{"acquireToken":{"reason":"init","threadId":"thread-1"}}}"""
        val reply = core.handle(request)
        val decoded = decodeReply(reply)
        assertTrue(decoded.ok)
        assertNotNull(decoded.data)
        assertEquals(expected, decoded.data!!.token)
    }

    @Test
    fun acquireTokenWithoutProvider() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val request = """{"kind":"acquireToken","payload":{"acquireToken":{"reason":"refresh","threadId":null}}}"""
        val reply = core.handle(request)
        val decoded = decodeReply(reply)
        assertFalse(decoded.ok)
        assertEquals("NO_PROVIDER", decoded.error?.code)
    }

    @Test
    fun providerErrorPropagates() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = { _ ->
            throw RuntimeException("network gone")
        })
        val request = """{"kind":"acquireToken","payload":{"acquireToken":{"reason":"init","threadId":null}}}"""
        val reply = core.handle(request)
        val decoded = decodeReply(reply)
        assertFalse(decoded.ok)
        assertEquals("PROVIDER_ERROR", decoded.error?.code)
        assertTrue(decoded.error!!.message.contains("network gone"))
    }

    @Test
    fun unknownKind() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val reply = core.handle("""{"kind":"wat"}""")
        val decoded = decodeReply(reply)
        assertFalse(decoded.ok)
        assertEquals("UNKNOWN_KIND", decoded.error?.code)
    }

    @Test
    fun ping() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val reply = core.handle("""{"kind":"ping"}""")
        val decoded = decodeReply(reply)
        assertTrue(decoded.ok)
        assertEquals("pong", decoded.data?.echo)
    }

    @Test
    fun invalidJson() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val reply = core.handle("not json")
        val decoded = decodeReply(reply)
        assertFalse(decoded.ok)
        assertEquals("BAD_JSON", decoded.error?.code)
    }

    @Test
    fun badAcquirePayload() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val reply = core.handle("""{"kind":"acquireToken","payload":{}}""")
        val decoded = decodeReply(reply)
        assertFalse(decoded.ok)
        assertEquals("BAD_PAYLOAD", decoded.error?.code)
    }

    @Test
    fun providerSwappable() = runBlocking {
        val provider1 = VedikaChatTokenProvider { _ -> VedikaChatToken("tk_a", 1) }
        val provider2 = VedikaChatTokenProvider { _ -> VedikaChatToken("tk_b", 2) }
        val core = VedikaChatBridgeCore(provider1)
        core.tokenProvider = provider2
        val reply = core.handle("""{"kind":"acquireToken","payload":{"acquireToken":{"reason":"init","threadId":null}}}""")
        val decoded = decodeReply(reply)
        assertEquals("tk_b", decoded.data?.token?.value)
    }

    @Test
    fun emptyBody() = runBlocking {
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val reply = core.handle("")
        val decoded = decodeReply(reply)
        assertFalse(decoded.ok)
    }

    @Test
    fun replyShapeHasOkField() = runBlocking {
        // Sanity check that the wire shape uses the documented keys.
        val core = VedikaChatBridgeCore(tokenProvider = null)
        val reply = core.handle("""{"kind":"ping"}""")
        val obj = json.parseToJsonElement(reply).jsonObject
        assertTrue(obj.containsKey("ok"))
        assertEquals("pong", obj["data"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
    }
}
