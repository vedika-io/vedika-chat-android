// The chat WebView wrapper.
//
// Hosts a `WebView` pointed at the chat page. By default the page is
// HOSTED: the WebView loads `VedikaChatConfig.chatUrl` over https, so a
// chat fix reaches every installed app without an app release. If the
// hosted page cannot be reached (offline, DNS, TLS, timeout, or an HTTP
// error), the view shows a small offline page with a Retry link instead
// of a blank or half-loaded screen.
//
// A copy of the chat page can be shipped inside the app instead
// (`VedikaChatConfig.pinnedBundle`). That is an explicit opt-in: the
// bundle is served by `WebViewAssetLoader` from
// `https://appassets.androidplatform.net/assets/<path>/` after its
// manifest verifies, and it stays at its build-time version until the
// host ships a new app.
//
// In both modes the page calls into the host via
// `WebViewCompat.addWebMessageListener`, locked to the page's own
// origin, and the bridge speaks the same JSON envelope as the iOS
// bridge.
//
// Per Google Play policy, `addJavascriptInterface` is "Low (No origin
// checks)" and not recommended. `addWebMessageListener` is the
// recommended bridge path for browsers/WebViews loading JavaScript.
// See `https://developer.android.com/develop/ui/views/layout/webapps/native-api-access-jsbridge`
// (updated 2026-09-16).
//
// The chat layout is rendered by the page itself. The native side
// owns no chat pixel — it just sets the WebView's bounds, applies
// safe-area insets and injects theme tokens for the page to consume.

package io.vedika.chat

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * A `WebView` subclass that hosts the bundled Vedika chat page and
 * bridges a short-lived token from the host app down to the page.
 *
 * Construct the view in code or in XML, then call [setConfig],
 * [setTokenProvider] and [loadChat] in `onAttachedToWindow` or later
 * (the host decides the right moment). The view surfaces load
 * failures through [onLoadError].
 *
 * There is no default config: [setConfig] must be called with a
 * [VedikaChatConfig] that names the hosted `chatUrl` (or, as an
 * explicit opt-in, a `pinnedBundle`).
 */
class VedikaChatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var config: VedikaChatConfig? = null
    private val bridge = VedikaChatBridgeCore()
    private var assetLoader: WebViewAssetLoader? = null
    private val loadedRef = AtomicReference(false)
    private val tracker = VedikaChatLoadTracker()
    private var hostedUrl: String? = null
    private val loadTimeout = Runnable { onLoadTimeout() }

    init {
        // Only the page's own origin (plus any origins the host allowed)
        // is loadable. Anything else is refused before the network
        // request is issued. A pinned bundle is served from the staged
        // bundle directory by the `WebViewAssetLoader` installed in
        // `installAssetLoader`; a hosted page loads from the network.
        webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (VedikaChatNavigationPolicy.isNonNetwork(url)) return null
                val cfg = config
                if (cfg == null || !VedikaChatNavigationPolicy(cfg.networkOrigins).allows(url)) {
                    VedikaChatLogging.emit("NAVIGATION_REJECTED")
                    return WebResourceResponse(
                        "text/plain", "utf-8", 404, "Not Found", emptyMap(), null
                    )
                }
                if (cfg.source !is VedikaChatSource.Pinned) return null
                val loader = assetLoader
                    ?: return WebResourceResponse(
                        "text/plain", "utf-8", 503, "Asset loader not ready",
                        emptyMap(), null
                    )
                return loader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (VedikaChatOfflinePage.isRetryUrl(url)) {
                    retry()
                    return true
                }
                val cfg = config ?: return true
                if (!VedikaChatNavigationPolicy(cfg.networkOrigins).allows(url)) {
                    VedikaChatLogging.emit("NAVIGATION_REJECTED")
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val cfg = config ?: return
                if (cfg.source is VedikaChatSource.Hosted && tracker.pageFinished()) {
                    removeCallbacks(loadTimeout)
                    injectTheme(cfg.theme)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat
            ) {
                if (!request.isForMainFrame) return
                val failure = tracker.mainFrameError(
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
                        error.errorCode
                    } else {
                        ERROR_UNKNOWN
                    }
                )
                showOffline(failure)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                showOffline(tracker.mainFrameHttpError(errorResponse.statusCode))
            }
        }

        // Window insets are the safe-area source of truth on Android.
        // We forward them to the page as CSS `env()` variables the
        // chat layout consumes.
        setOnApplyWindowInsetsListener { _, insets ->
            applySafeAreaInsets(insets)
            insets
        }
    }

    /** Optional host-installed token factory. Set before [loadChat]. */
    fun setTokenProvider(provider: VedikaChatTokenProvider?) {
        bridge.tokenProvider = provider
    }

    /** Replace the chat configuration. Must be called before [loadChat]. */
    fun setConfig(newConfig: VedikaChatConfig) {
        // Configuration is locked once the page begins loading.
        if (loadedRef.get()) {
            throw IllegalStateException("VedikaChatView config is locked once loadChat() has been called.")
        }
        config = newConfig
    }

    /**
     * Fired once after the manifest verifies and the page begins
     * loading. Useful for hiding a launch screen.
     */
    var onReady: (() -> Unit)? = null

    /** Fired when the bundle cannot be verified or the page errors out. */
    var onLoadError: ((Throwable) -> Unit)? = null

    /**
     * Load the chat page: the hosted `chatUrl` by default, or, when the
     * config opts in with `pinnedBundle`, the verified copy in the app.
     */
    fun loadChat() {
        val cfg = config ?: throw IllegalStateException(
            "VedikaChatView has no config: call setConfig() before loadChat()."
        )
        when (val source = cfg.source) {
            is VedikaChatSource.Hosted -> loadHosted(cfg, source)
            is VedikaChatSource.Pinned -> loadPinned(cfg, source.bundle)
        }
    }

    /**
     * Try the hosted page again after the offline page was shown. Does
     * nothing unless the offline page is up, so it is safe to call from
     * a connectivity callback.
     */
    fun retry() {
        val url = hostedUrl ?: return
        if (!tracker.retry()) return
        startHostedLoad(url)
    }

    private fun loadHosted(cfg: VedikaChatConfig, source: VedikaChatSource.Hosted) {
        installMessageListener(cfg)
        applyCommonSettings(cfg)
        // Chat history and session state live in the page's own storage.
        settings.domStorageEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        hostedUrl = source.chatUrl
        loadedRef.set(true)
        tracker.begin()
        startHostedLoad(source.chatUrl)
        onReady?.invoke()
    }

    private fun startHostedLoad(url: String) {
        removeCallbacks(loadTimeout)
        postDelayed(loadTimeout, config?.loadTimeoutMillis ?: VedikaChatConfig.DEFAULT_LOAD_TIMEOUT_MILLIS)
        loadUrl(url)
    }

    private fun onLoadTimeout() {
        val failure = tracker.timedOut() ?: return
        stopLoading()
        showOffline(failure)
    }

    /** Replace the failed page with the offline page and tell the host why. */
    private fun showOffline(failure: VedikaChatLoadException?) {
        if (failure == null) return
        removeCallbacks(loadTimeout)
        val cfg = config ?: return
        loadDataWithBaseURL(
            null,
            VedikaChatOfflinePage.render(cfg.offlineText, cfg.theme),
            "text/html",
            "utf-8",
            null
        )
        onLoadError?.invoke(failure)
    }

    private fun loadPinned(cfg: VedikaChatConfig, bundle: VedikaChatPinnedBundle) {
        val bundleDir = stageBundleToCache(bundle)
        if (bundleDir == null) {
            val err = IllegalStateException(
                "Chat bundle not found in assets at '${bundle.assetsPath}'."
            )
            onLoadError?.invoke(err)
            return
        }

        val manifestResult = VedikaChatManifestLoader.loadAndVerify(bundleDir)
        val manifest = manifestResult.getOrNull()
        if (manifest == null) {
            onLoadError?.invoke(manifestResult.exceptionOrNull() ?: IllegalStateException("manifest failed"))
            return
        }

        installAssetLoader(bundleDir, bundle)
        installMessageListener(cfg)

        val entry = (bundle.entryPoint ?: manifest.entry).ifBlank { "index.html" }
        val url = "${VedikaChatConfig.ASSET_LOADER_ORIGIN}/assets/${bundle.assetsPath}/$entry"

        applyCommonSettings(cfg)
        loadUrl(url)
        injectTheme(cfg.theme)
        loadedRef.set(true)
        onReady?.invoke()
    }

    private fun applyCommonSettings(cfg: VedikaChatConfig) {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // The page owns its own background; expose ours only as a
        // hint so the chrome blends during initial paint.
        setBackgroundColor(cfg.theme.backgroundColor ?: Color.WHITE)
        @Suppress("DEPRECATION")
        WebView.setWebContentsDebuggingEnabled(cfg.inspectable)
    }

    /**
     * Inject the host's theme tokens into the chat page as CSS
     * custom properties on `:root`. The page can read them via
     * `var(--vedika-*)`. Theme changes after load also flow through
     * this method so a "dark mode" toggle only has to call it.
     */
    fun injectTheme(theme: VedikaChatTheme) {
        val css = buildThemeCss(theme)
        if (css.isBlank()) return
        val js = "(function(){" +
            "var s=document.createElement('style');" +
            "s.setAttribute('data-vedika-theme','1');" +
            "s.textContent=" + css.toJsString() + ";" +
            "var prev=document.head.querySelector('style[data-vedika-theme]');" +
            "if(prev)prev.remove();" +
            "document.head.appendChild(s);" +
            "})();"
        evaluateJavascript(js, null)
    }

    private fun buildThemeCss(theme: VedikaChatTheme): String {
        val parts = mutableListOf<String>()
        theme.backgroundColor?.let { parts.add("--vedika-bg:${cssColor(it)};") }
        theme.textColor?.let { parts.add("--vedika-fg:${cssColor(it)};") }
        theme.accentColor?.let { parts.add("--vedika-accent:${cssColor(it)};") }
        theme.fontFamily?.let { parts.add("--vedika-font:${cssString(it)};") }
        theme.customCss?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        if (parts.isEmpty()) return ""
        return ":root{${parts.joinToString("")}}"
    }

    private fun cssColor(argb: Int): String = VedikaChatCss.color(argb)

    private fun cssString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun String.toJsString(): String {
        val escaped = this
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }

    /**
     * Copy the chat bundle from `assets/vedika-chat/` to a cache
     * directory so the `File` API can read it for manifest verification
     * without holding an `AssetManager` open across calls. Returns
     * `null` if the bundle is missing.
     */
    private fun stageBundleToCache(bundle: VedikaChatPinnedBundle): File? {
        val root = File(context.cacheDir, "vedika-chat-staging")
        val bundleDir = File(root, bundle.assetsPath)
        if (bundleDir.exists() && File(bundleDir, "manifest.json").exists()) {
            return bundleDir
        }
        val assetsRoot = bundle.assetsPath.trim('/')
        val manifestAssetPath = "$assetsRoot/manifest.json"
        try {
            context.assets.open(manifestAssetPath).use { /* existence confirmed */ }
        } catch (e: Throwable) {
            return null
        }
        try {
            bundleDir.mkdirs()
            copyAssetTree(assetsRoot, bundleDir)
        } catch (e: Throwable) {
            VedikaChatLogging.emit("STAGE_FAILED")
            return null
        }
        return bundleDir
    }

    /** Recursively copy an asset sub-tree into [dest]. */
    private fun copyAssetTree(path: String, dest: File) {
        val children = context.assets.list(path) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        for (child in children) {
            val childPath = "$path/$child"
            val childDest = File(dest, child)
            copyAssetTree(childPath, childDest)
        }
    }

    /** Install the asset loader that serves the staged bundle. */
    private fun installAssetLoader(bundleDir: File, bundle: VedikaChatPinnedBundle) {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/${bundle.assetsPath}/") { assetPath ->
                val file = File(bundleDir, assetPath.trimStart('/'))
                if (file.exists() && file.isFile) {
                    WebResourceResponse(
                        guessMimeType(file.path),
                        "utf-8",
                        FileInputStream(file)
                    )
                } else {
                    WebResourceResponse(
                        "text/plain", "utf-8", 404, "Not Found", emptyMap(), null
                    )
                }
            }
            .build()
        assetLoader = loader
    }

    /**
     * Install the message listener that routes page messages to the
     * bridge core. Origin is locked to the asset loader origin at the
     * platform level — `addWebMessageListener` rejects messages from
     * any other origin before our handler is invoked. We still verify
     * the origin string inside the handler as defence in depth, and
     * the verification path never logs the token value.
     */
    private fun installMessageListener(cfg: VedikaChatConfig) {
        val allowed = cfg.effectiveAllowedOrigins
        val listener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, _, replyProxy ->
            val body = message.data ?: ""
            // Defence in depth: `addWebMessageListener` already
            // enforces origin rules, but a buggy platform build or a
            // future API change should not be the only line of
            // defence. Compare the full origin against the host's
            // allowlist.
            val originString = sourceOrigin.toString()
            val originAllowed = allowed.any { rule ->
                VedikaChatOrigins.ruleMatchesOrigin(rule, originString)
            }
            if (!originAllowed) {
                VedikaChatLogging.emit("ORIGIN_REJECTED")
                replyProxy.postMessage("""{"ok":false,"error":{"code":"BAD_ORIGIN","message":"unknown origin"}}""")
                return@WebMessageListener
            }

            // Run the suspending bridge on a default-dispatched
            // coroutine and reply asynchronously. The page awaits
            // the Promise that `replyProxy.postMessage` resolves.
            //
            // `GlobalScope` is intentional: the message listener is
            // short-lived and the reply is fire-and-forget once we
            // hand the JSON back to the page. The listener is tied
            // to the WebView's lifecycle and cleaned up in
            // `onDetachedFromWindow`.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.Default) {
                val replyJson = bridge.handle(body)
                replyProxy.postMessage(replyJson)
            }
        }
        WebViewCompat.addWebMessageListener(
            this,
            HANDLER_NAME,
            allowed,
            listener
        )
    }

    /**
     * Forward window insets into the WebView as CSS `env()` variables
     * so the chat layout can pad its scroll area from the system
     * bars (status bar, navigation bar, notches, display cutouts).
     */
    private fun applySafeAreaInsets(insets: WindowInsets) {
        val systemBars = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsets.Type.ime())
        val js = buildString {
            append("(function(){")
            append("var s=document.documentElement.style;")
            append("s.setProperty('--vedika-safe-top','${systemBars.top}px');")
            append("s.setProperty('--vedika-safe-bottom','${systemBars.bottom}px');")
            append("s.setProperty('--vedika-safe-left','${systemBars.left}px');")
            append("s.setProperty('--vedika-safe-right','${systemBars.right}px');")
            append("s.setProperty('--vedika-ime-bottom','${ime.bottom}px');")
            append("})();")
        }
        evaluateJavascript(js, null)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(loadTimeout)
        super.onDetachedFromWindow()
        loadedRef.set(false)
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "map" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    companion object {
        /** Asset loader host used by a pinned bundle. */
        const val ASSET_LOADER_HOST: String = "appassets.androidplatform.net"

        /** Bridge handler name; matches the iOS SDK and the page. */
        const val HANDLER_NAME: String = "vedika"

        /** `WebViewClient.ERROR_UNKNOWN`, used when the platform cannot say. */
        private const val ERROR_UNKNOWN: Int = -1
    }
}