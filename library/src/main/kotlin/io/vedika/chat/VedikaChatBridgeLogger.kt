// Indirection between the chat WebView wrapper and the host's logging.
//
// The chat bridge never logs the token. To prove that in a test we route
// every diagnostic message through a `VedikaChatBridgeLogger` and capture
// them in the test; the production default is a no-op so the SDK ships
// with zero logging side effects.
//
// A host that wants logs wires its own logger with `setBridgeLogger`.
//
// The logger MUST NOT receive the token, the request envelope, or any
// field derived from it. The bridge trims messages to fixed strings
// (event codes, origin host only) so even a misconfigured logger cannot
// leak the secret.

package io.vedika.chat

/**
 * Diagnostic sink for the chat bridge. Default implementation is a
 * no-op so the SDK has zero logging side effects.
 *
 * Hosts that want diagnostics implement this and install it with
 * [VedikaChatBridgeLogger.Companion.install].
 */
fun interface VedikaChatBridgeLogger {
    fun log(event: String)
}

/**
 * Shared logger and origin-allowlist. Both are global so the host only
 * installs them once per process.
 */
object VedikaChatLogging {

    @Volatile
    private var logger: VedikaChatBridgeLogger = VedikaChatBridgeLogger { /* no-op */ }

    /** Install a custom logger. Pass a no-op to silence the bridge. */
    fun install(logger: VedikaChatBridgeLogger) {
        this.logger = logger
    }

    /** Internal: emit a fixed event code. The event code MUST NOT
     *  include the token value, the request payload, or the reply
     *  body. */
    internal fun emit(event: String) {
        logger.log(event)
    }
}