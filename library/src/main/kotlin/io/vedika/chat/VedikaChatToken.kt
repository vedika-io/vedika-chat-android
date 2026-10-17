// Token provider for the chat WebView.
//
// Mirrors the Swift `VedikaChatTokenProvider` exactly so the same
// exported chat page can speak to either host. The host mints a
// short-lived token from its own backend and hands it back through
// this bridge; the page holds the token only long enough to send the
// next turn.

package io.vedika.chat

import kotlinx.serialization.Serializable

/** A short-lived bearer token the page uses to call the Vedika chat API. */
@Serializable
data class VedikaChatToken(
    /** The raw token string. Must not be logged. */
    val value: String,
    /**
     * Unix epoch seconds at which the token expires. The page refreshes
     * before this moment. A value of `0` means "force-refresh on the
     * next send".
     */
    val expiresAt: Long
)

/** A request from the page for a fresh token. */
@Serializable
data class VedikaChatTokenRequest(
    /** Why the page is asking. `init` means the page just started a
     *  new thread; `refresh` means a previous token is about to
     *  expire. */
    val reason: Reason,
    val threadId: String?
) {
    @Serializable
    enum class Reason {
        /** Bridge the iOS naming: `"init"` is a reserved word in Kotlin. */
        @kotlinx.serialization.SerialName("init")
        INIT,

        @kotlinx.serialization.SerialName("refresh")
        REFRESH
    }
}

/**
 * Host-side token factory. Invoked on the main thread. Should call out
 * to the host's own backend and return the freshly minted token.
 * Errors propagate to the page as a typed `PROVIDER_ERROR` reply.
 */
fun interface VedikaChatTokenProvider {
    suspend fun acquire(request: VedikaChatTokenRequest): VedikaChatToken
}
