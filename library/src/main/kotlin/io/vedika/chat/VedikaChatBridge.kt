// The native <-> page bridge for Android.
//
// Same wire envelope as the iOS bridge so the same chat page talks
// to either host. The host installs a `VedikaChatTokenProvider`; the
// bridge forwards `acquireToken` requests to it and replies with a
// typed `VedikaChatBridgeReply`.
//
// The bridge is wired through `WebViewCompat.addWebMessageListener`
// with `allowedOriginRules` set to the asset loader origin
// (`https://appassets.androidplatform.net`). Per Google's bridge
// documentation, this is the recommended path because origins are
// checked at the platform level — `addJavascriptInterface` is "Low
// (No origin checks)" and not recommended.

package io.vedika.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON envelope sent from the page to the bridge. */
@Serializable
data class VedikaChatBridgeMessage(
    /** The kind of request. Today only `acquireToken` is defined. */
    val kind: String,
    /** Per-kind payload. Decoded by the matching handler. */
    val payload: BridgePayload? = null
) {
    /**
     * Discriminated union of payloads. The wrapper key on the wire is
     * the case name, e.g. `{"acquireToken": {...}}`. The `kind` field
     * on the outer envelope must match.
     */
    @Serializable
    data class BridgePayload(
        val acquireToken: VedikaChatTokenRequest? = null
    )
}

/** JSON envelope sent from the bridge back to the page. */
@Serializable
data class VedikaChatBridgeReply(
    val ok: Boolean,
    val data: ReplyData? = null,
    val error: ReplyError? = null
) {
    @Serializable
    data class ReplyError(
        val code: String,
        val message: String
    )

    @Serializable
    data class ReplyData(
        val token: VedikaChatToken? = null,
        val echo: String? = null
    )

    companion object {
        fun token(t: VedikaChatToken): VedikaChatBridgeReply =
            VedikaChatBridgeReply(ok = true, data = ReplyData(token = t))

        fun echo(message: String): VedikaChatBridgeReply =
            VedikaChatBridgeReply(ok = true, data = ReplyData(echo = message))

        fun failure(code: String, message: String): VedikaChatBridgeReply =
            VedikaChatBridgeReply(
                ok = false,
                data = null,
                error = ReplyError(code = code, message = message)
            )
    }
}

/**
 * Pure-message logic for the bridge. The Android wrapper class
 * (`VedikaChatBridgeListener`) handles thread and View threading, and
 * delegates the message handling to this class for testability.
 */
class VedikaChatBridgeCore(
    @Volatile var tokenProvider: VedikaChatTokenProvider? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Decodes [body], dispatches to the matching handler, and returns
     * the JSON reply. Errors are converted to typed `ReplyError`s, never
     * thrown — the bridge must never surface a stack trace to the page.
     */
    suspend fun handle(body: String): String {
        val envelope = try {
            json.decodeFromString(VedikaChatBridgeMessage.serializer(), body)
        } catch (e: Throwable) {
            return encode(VedikaChatBridgeReply.failure("BAD_JSON", e.message ?: "decode failed"))
        }

        return when (envelope.kind) {
            "acquireToken" -> handleAcquire(envelope.payload)
            "ping" -> encode(VedikaChatBridgeReply.echo("pong"))
            else -> encode(
                VedikaChatBridgeReply.failure(
                    "UNKNOWN_KIND",
                    "Unknown message kind: ${envelope.kind}"
                )
            )
        }
    }

    private suspend fun handleAcquire(payload: VedikaChatBridgeMessage.BridgePayload?): String {
        val request = payload?.acquireToken
            ?: return encode(
                VedikaChatBridgeReply.failure(
                    "BAD_PAYLOAD",
                    "acquireToken requires payload { reason, threadId? }"
                )
            )
        val provider = tokenProvider
            ?: return encode(
                VedikaChatBridgeReply.failure(
                    "NO_PROVIDER",
                    "Host app has not installed a VedikaChatTokenProvider."
                )
            )
        return try {
            val token = provider.acquire(request)
            encode(VedikaChatBridgeReply.token(token))
        } catch (e: Throwable) {
            encode(
                VedikaChatBridgeReply.failure(
                    "PROVIDER_ERROR",
                    e.message ?: e::class.qualifiedName ?: "unknown error"
                )
            )
        }
    }

    private fun encode(reply: VedikaChatBridgeReply): String =
        json.encodeToString(VedikaChatBridgeReply.serializer(), reply)
}
