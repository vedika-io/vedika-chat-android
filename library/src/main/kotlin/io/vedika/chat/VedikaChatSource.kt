// Where the chat page comes from.
//
// The default is HOSTED: the WebView loads the chat page from its https
// URL, so a fix to the chat reaches every installed app without an app
// release. A copy of the page can instead be shipped inside the app
// (PINNED), but only when the host asks for it by name. A pinned copy
// stays at the version it was built with until the host ships a new app,
// so it is the opt-in, not the default.
//
// Everything here is plain Kotlin with no Android types, so it can be
// unit-tested on the JVM.

package io.vedika.chat

import java.net.URI

/**
 * Opt-in: a copy of the chat page shipped inside the host app.
 *
 * A pinned copy only changes when the host app is released, so a chat
 * fix does not reach these installs until the host ships an update.
 * Prefer the hosted page unless the chat must open with no network at
 * all.
 *
 * @property assetsPath path inside the host app's `assets/` directory,
 *                     e.g. `"vedika-chat"` for `assets/vedika-chat/`.
 *                     Must contain a `manifest.json` and an entry point.
 * @property entryPoint filename inside the bundle, including any path
 *                     segments. Defaults to the manifest's own entry.
 */
data class VedikaChatPinnedBundle(
    val assetsPath: String = VedikaChatConfig.DEFAULT_ASSETS_PATH,
    val entryPoint: String? = null
) {
    init {
        require(assetsPath.isNotBlank()) { "assetsPath must not be blank" }
    }
}

/** The resolved source of the chat page for one [VedikaChatConfig]. */
sealed class VedikaChatSource {

    /** The origin the page is served from, e.g. `https://chat.example.com`. */
    abstract val origin: String

    /** The chat page loaded from its https URL. This is the default. */
    data class Hosted(val chatUrl: String) : VedikaChatSource() {
        override val origin: String

        init {
            val uri = try {
                URI(chatUrl)
            } catch (e: Exception) {
                null
            }
            require(
                uri != null &&
                    uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null
            ) { "chatUrl must be an https URL with a host and no embedded credentials" }
            origin = requireNotNull(VedikaChatOrigins.originOf(chatUrl))
        }
    }

    /** A copy of the chat page shipped inside the app. Opt-in only. */
    data class Pinned(val bundle: VedikaChatPinnedBundle) : VedikaChatSource() {
        override val origin: String = VedikaChatConfig.ASSET_LOADER_ORIGIN
    }
}

/** Origin parsing and matching shared by the bridge and the navigation policy. */
internal object VedikaChatOrigins {

    /**
     * `scheme://host[:port]` of [url], lower-cased, with the scheme's
     * default port left out. `null` when [url] is not an absolute URL
     * with a host (for example `data:` or `about:blank`).
     */
    fun originOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = uri.port
        val isDefaultPort = port == -1 ||
            (scheme == "https" && port == 443) ||
            (scheme == "http" && port == 80)
        return if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /**
     * True only for an `https` origin or URL. Every allowlist match and
     * every navigation check starts here, so a plain `http`, `ws`, `ftp`
     * or other scheme on an allowed host is never accepted, whatever the
     * rule looks like.
     */
    fun isHttps(originOrUrl: String): Boolean {
        val scheme = try {
            URI(originOrUrl).scheme
        } catch (e: Exception) {
            null
        }
        return scheme.equals("https", ignoreCase = true)
    }

    /**
     * Match an allowlist rule against a full origin URL. Supports exact
     * origin matches (`https://chat.example.com`) and host-only wildcard
     * rules prefixed by `*.` (`*.example.com`).
     *
     * Only an `https` [origin] can match. A host-only wildcard rule names
     * no scheme, so without this it would also match `http://` and every
     * other scheme on the same hosts.
     */
    fun ruleMatchesOrigin(rule: String, origin: String): Boolean {
        if (!isHttps(origin)) return false
        if (rule == origin) return true
        if (rule.startsWith("*.")) {
            val suffix = rule.substring(2)
            val host = try {
                URI(origin).host
            } catch (e: Exception) {
                null
            } ?: return false
            // A wildcard rule does NOT match its parent domain: `*.example.com`
            // matches `chat.example.com` and `deep.chat.example.com`, but NOT
            // `example.com` itself (matching RFC 6125 wildcard certificate
            // semantics).
            return host.endsWith(".$suffix")
        }
        return false
    }

    /**
     * An allowlist rule the policy can ever match: a `*.host` wildcard or
     * an `https://host[:port]` origin. Anything else (for example an
     * `http://` origin) could never match, so it is refused up front
     * rather than left to look as if it worked.
     */
    fun isValidRule(rule: String): Boolean =
        if (rule.startsWith("*.")) {
            rule.length > 2 && !rule.contains('/') && !rule.contains(':')
        } else {
            isHttps(rule) && originOf(rule) == rule
        }
}

/**
 * Decides which URLs the chat WebView may load. Anything outside the
 * allowed origins is refused before a network request is issued.
 */
internal class VedikaChatNavigationPolicy(private val allowedOrigins: Set<String>) {

    fun allows(url: String): Boolean {
        // https only, before any origin is compared.
        if (!VedikaChatOrigins.isHttps(url)) return false
        val origin = VedikaChatOrigins.originOf(url) ?: return false
        return allowedOrigins.any { VedikaChatOrigins.ruleMatchesOrigin(it, origin) }
    }

    companion object {
        /**
         * `data:` and `about:` resources are built into the WebView and
         * never reach the network, so the origin check does not apply
         * to them.
         */
        fun isNonNetwork(url: String): Boolean {
            val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
            return scheme == "data" || scheme == "about"
        }
    }
}
