// What the user sees when the hosted chat page cannot be reached.
//
// The fallback is a small static page drawn inside the WebView itself
// (no script, no network, no bridge). Its Retry link points at a private
// URL scheme that the view intercepts and turns into a reload of the
// hosted chat URL. Nothing here touches Android types, so the decisions
// are unit-tested on the JVM.

package io.vedika.chat

/**
 * Why the hosted chat page did not load.
 */
enum class VedikaChatLoadFailure {
    /** Offline, DNS failure, connection refused or dropped. */
    NETWORK,

    /** The server did not answer in time. */
    TIMEOUT,

    /** The secure connection could not be established. */
    SECURE_CONNECTION,

    /** The server answered with an HTTP status of 400 or higher. */
    HTTP_ERROR,

    /** Anything else the WebView reported for the main page. */
    OTHER
}

/** Delivered to `onLoadError` when the hosted chat page fails to load. */
class VedikaChatLoadException(
    val failure: VedikaChatLoadFailure,
    val httpStatus: Int? = null
) : Exception(
    if (httpStatus != null) "Chat page failed to load: $failure ($httpStatus)"
    else "Chat page failed to load: $failure"
)

/**
 * Text of the offline fallback. Hosts localise it by passing their own
 * strings; the defaults are English.
 */
data class VedikaChatOfflineText(
    val title: String = "You're offline",
    val message: String = "Chat can't be reached right now. Check your connection, then try again.",
    val retryLabel: String = "Retry"
)

/**
 * Tracks one chat page load and decides when the fallback is shown.
 *
 * Only failures of the main page count. A missing image or a failed
 * sub-request must never replace a working chat with the fallback, so
 * callers pass main-frame callbacks only.
 *
 * The WebView reports a finished load for the error page it shows
 * after a failure, and again for the fallback we draw. Both are ignored
 * while the state is [State.OFFLINE]; only [retry] leaves it.
 */
internal class VedikaChatLoadTracker {

    enum class State { IDLE, LOADING, READY, OFFLINE }

    var state: State = State.IDLE
        private set

    /** The hosted page is being requested. */
    fun begin() {
        state = State.LOADING
    }

    /** The main page finished loading. Returns true when it became READY. */
    fun pageFinished(): Boolean {
        if (state != State.LOADING) return false
        state = State.READY
        return true
    }

    /**
     * The main page failed with a WebView error code. Returns the error
     * to report, or `null` when this load already failed.
     */
    fun mainFrameError(errorCode: Int): VedikaChatLoadException? =
        failWith(VedikaChatLoadException(classify(errorCode)))

    /**
     * The main page answered with [status]. Returns the error to report
     * for 400 and above, or `null` for a success or a repeat failure.
     */
    fun mainFrameHttpError(status: Int): VedikaChatLoadException? {
        if (status < HTTP_ERROR_FLOOR) return null
        return failWith(VedikaChatLoadException(VedikaChatLoadFailure.HTTP_ERROR, status))
    }

    /** The load did not finish in time. Only a load still in flight times out. */
    fun timedOut(): VedikaChatLoadException? {
        if (state != State.LOADING) return null
        return failWith(VedikaChatLoadException(VedikaChatLoadFailure.TIMEOUT))
    }

    /** The user asked to try again. Returns true when a reload should start. */
    fun retry(): Boolean {
        if (state != State.OFFLINE) return false
        state = State.LOADING
        return true
    }

    private fun failWith(error: VedikaChatLoadException): VedikaChatLoadException? {
        if (state == State.OFFLINE) return null
        state = State.OFFLINE
        return error
    }

    companion object {
        private const val HTTP_ERROR_FLOOR = 400

        // WebViewClient.ERROR_* values. They are fixed by the platform and
        // copied here so this class stays free of Android types.
        private const val ERROR_HOST_LOOKUP = -2
        private const val ERROR_CONNECT = -6
        private const val ERROR_IO = -7
        private const val ERROR_TIMEOUT = -8
        private const val ERROR_FAILED_SSL_HANDSHAKE = -11

        fun classify(errorCode: Int): VedikaChatLoadFailure = when (errorCode) {
            ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_IO -> VedikaChatLoadFailure.NETWORK
            ERROR_TIMEOUT -> VedikaChatLoadFailure.TIMEOUT
            ERROR_FAILED_SSL_HANDSHAKE -> VedikaChatLoadFailure.SECURE_CONNECTION
            else -> VedikaChatLoadFailure.OTHER
        }
    }
}

/** The static fallback page and its Retry link. */
internal object VedikaChatOfflinePage {

    /** Private scheme the Retry link points at. The view intercepts it. */
    const val RETRY_SCHEME: String = "vedika-chat-retry"
    const val RETRY_URL: String = "$RETRY_SCHEME://now"

    fun isRetryUrl(url: String?): Boolean =
        url != null && url.startsWith("$RETRY_SCHEME:")

    fun render(text: VedikaChatOfflineText, theme: VedikaChatTheme): String {
        val background = VedikaChatCss.color(theme.backgroundColor ?: DEFAULT_BACKGROUND)
        val foreground = VedikaChatCss.color(theme.textColor ?: DEFAULT_FOREGROUND)
        val accent = VedikaChatCss.color(theme.accentColor ?: DEFAULT_ACCENT)
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>${escapeHtml(text.title)}</title>
<style>
html,body{height:100%;margin:0}
body{display:flex;align-items:center;justify-content:center;background:$background;color:$foreground;font:16px/1.5 system-ui,sans-serif;text-align:center}
main{max-width:22rem;padding:1.5rem}
h1{font-size:1.25rem;margin:0 0 .5rem}
p{margin:0 0 1.5rem;opacity:.8}
a.retry{display:inline-block;min-width:8rem;padding:.75rem 1.5rem;border-radius:.5rem;background:$accent;color:#fff;text-decoration:none;font-weight:600}
</style></head>
<body><main>
<h1>${escapeHtml(text.title)}</h1>
<p>${escapeHtml(text.message)}</p>
<a class="retry" href="$RETRY_URL">${escapeHtml(text.retryLabel)}</a>
</main></body></html>
"""
    }

    fun escapeHtml(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private const val DEFAULT_BACKGROUND: Int = 0xFFFFFFFF.toInt()
    private const val DEFAULT_FOREGROUND: Int = 0xFF1B1F23.toInt()
    private const val DEFAULT_ACCENT: Int = 0xFF1F6FEB.toInt()
}

/** CSS value helpers shared by the theme injection and the fallback page. */
internal object VedikaChatCss {

    /** ARGB int to `#RRGGBB`, or `#AARRGGBB` when alpha is not 255. */
    fun color(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return if (a == 0xFF) {
            "#%02X%02X%02X".format(r, g, b)
        } else {
            "#%02X%02X%02X%02X".format(a, r, g, b)
        }
    }
}
