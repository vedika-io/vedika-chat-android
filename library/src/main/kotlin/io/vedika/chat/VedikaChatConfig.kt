// Configuration for `VedikaChatView`.
//
// The chat page is HOSTED by default: pass `chatUrl` and the WebView
// loads it from there, so a chat fix reaches every installed app
// without an app release. Shipping a copy of the page inside the app
// is an explicit opt-in (`pinnedBundle`); a pinned copy only changes
// when the host app is released.
//
// Theming: `theme` exposes optional colour, font and CSS overrides that
// are injected into the chat page via CSS custom properties. Hosts can
// keep the page default and pass `null` for everything.

package io.vedika.chat

import android.graphics.Color

/**
 * Optional theme overrides for the chat page. Any field set
 * to `null` is left at the page's own default. Hosts that need a
 * full restyle should ship a `customCss` block; the bridge injects it
 * on first load and on every theme change.
 */
data class VedikaChatTheme(
    /** Background colour behind the chat surface. */
    val backgroundColor: Int? = null,
    /** Primary text colour. */
    val textColor: Int? = null,
    /** Accent colour for interactive controls. */
    val accentColor: Int? = null,
    /** Font family stack forwarded to the page. */
    val fontFamily: String? = null,
    /**
     * Free-form CSS appended after the bridge's own theming layer.
     * Useful for hosts that ship their own design system. The bridge
     * never wraps this in an extra selector; the host is responsible
     * for selector specificity.
     */
    val customCss: String? = null
) {
    init {
        // Background colour must be a valid ARGB int if set.
        backgroundColor?.let { require(it == (it and 0xFFFFFFFF.toInt())) { "backgroundColor must be a 32-bit ARGB int" } }
        textColor?.let { require(it == (it and 0xFFFFFFFF.toInt())) { "textColor must be a 32-bit ARGB int" } }
        accentColor?.let { require(it == (it and 0xFFFFFFFF.toInt())) { "accentColor must be a 32-bit ARGB int" } }
    }
}

/**
 * How the chat view loads the chat page.
 *
 * Set exactly one of [chatUrl] (hosted, the default and the recommended
 * choice) or [pinnedBundle] (a copy of the page shipped inside the app,
 * which stays at its build-time version until the host ships an update).
 *
 * @property chatUrl https URL of the hosted chat page. Loaded from the
 *                     network on every open, so the chat is always
 *                     current. No credentials in the URL.
 * @property pinnedBundle explicit opt-in to a copy of the page shipped
 *                     in the app's `assets/`. Mutually exclusive with
 *                     [chatUrl].
 * @property allowedOrigins origins the page may talk to the bridge from
 *                     (e.g. `["https://chat.example.com"]`). Empty means
 *                     the page's own origin: the origin of [chatUrl], or
 *                     the asset loader origin for a pinned bundle. A
 *                     non-empty set replaces that default.
 * @property inspectable whether `WebView.setWebContentsDebuggingEnabled`
 *                     is on. Defaults to false.
 * @property theme optional theme overrides (background, text, accent,
 *                     font, free-form CSS).
 * @property offlineText text of the page shown when the hosted chat
 *                     cannot be reached. Hosts localise it here.
 * @property loadTimeoutMillis how long the hosted page may take to load
 *                     before the offline page is shown instead.
 */
data class VedikaChatConfig(
    val chatUrl: String? = null,
    val pinnedBundle: VedikaChatPinnedBundle? = null,
    val allowedOrigins: Set<String> = emptySet(),
    val inspectable: Boolean = false,
    val theme: VedikaChatTheme = VedikaChatTheme(),
    val offlineText: VedikaChatOfflineText = VedikaChatOfflineText(),
    val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS
) {
    /** Where the page comes from: hosted unless a pinned bundle was asked for. */
    val source: VedikaChatSource = when {
        chatUrl != null && pinnedBundle != null ->
            throw IllegalArgumentException("set chatUrl or pinnedBundle, not both")
        pinnedBundle != null -> VedikaChatSource.Pinned(pinnedBundle)
        chatUrl != null -> VedikaChatSource.Hosted(chatUrl)
        else -> throw IllegalArgumentException(
            "chatUrl is required: pass the https URL of the hosted chat page"
        )
    }

    init {
        require(loadTimeoutMillis > 0) { "loadTimeoutMillis must be positive" }
        allowedOrigins.forEach { rule ->
            require(VedikaChatOrigins.isValidRule(rule)) {
                "allowedOrigins entries must be https origins (https://host) or " +
                    "wildcards (*.host); got \"$rule\""
            }
        }
        val hosted = source as? VedikaChatSource.Hosted
        if (hosted != null && allowedOrigins.isNotEmpty()) {
            require(allowedOrigins.any { VedikaChatOrigins.ruleMatchesOrigin(it, hosted.origin) }) {
                "allowedOrigins must include the origin of chatUrl (${hosted.origin})"
            }
        }
    }

    /** Origins allowed to reach the bridge. */
    val effectiveAllowedOrigins: Set<String>
        get() = allowedOrigins.ifEmpty { setOf(source.origin) }

    /**
     * Origins the WebView may load anything from. A pinned bundle may only
     * load from the asset loader, whatever the bridge allowlist says; a
     * hosted page may load from the origins the host allowed.
     */
    internal val networkOrigins: Set<String>
        get() = when (source) {
            is VedikaChatSource.Pinned -> setOf(ASSET_LOADER_ORIGIN)
            is VedikaChatSource.Hosted -> effectiveAllowedOrigins
        }

    companion object {
        /** Conventional default location inside the host app's `assets/`. */
        const val DEFAULT_ASSETS_PATH: String = "vedika-chat"

        /** Origin `WebViewAssetLoader` serves a pinned bundle from. */
        const val ASSET_LOADER_ORIGIN: String = "https://appassets.androidplatform.net"

        const val DEFAULT_LOAD_TIMEOUT_MILLIS: Long = 30_000L

        /** Default background colour used when [VedikaChatTheme.backgroundColor] is `null`. */
        val DEFAULT_BACKGROUND_COLOR: Int = Color.WHITE
    }
}
