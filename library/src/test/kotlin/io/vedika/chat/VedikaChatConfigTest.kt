// Tests for how a `VedikaChatConfig` picks its source and which
// origins it lets through.
//
// The point of these tests: the chat page is HOSTED unless the host
// asks for a pinned copy by name, so a chat fix reaches installed apps
// without an app release.

package io.vedika.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VedikaChatConfigTest {

    private val chatUrl = "https://chat.example.com/embed/chat.html"

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- hosted is the default -------------------------------------------------

    @Test
    fun aChatUrlAloneIsHosted() {
        val config = VedikaChatConfig(chatUrl = chatUrl)
        val source = config.source
        assertTrue(source is VedikaChatSource.Hosted)
        assertEquals(chatUrl, (source as VedikaChatSource.Hosted).chatUrl)
        assertNull(config.pinnedBundle)
    }

    @Test
    fun aPinnedCopyNeedsAnExplicitOptIn() {
        val config = VedikaChatConfig(pinnedBundle = VedikaChatPinnedBundle())
        val source = config.source
        assertTrue(source is VedikaChatSource.Pinned)
        assertEquals(
            VedikaChatConfig.DEFAULT_ASSETS_PATH,
            (source as VedikaChatSource.Pinned).bundle.assetsPath
        )
    }

    @Test
    fun noSourceAtAllIsRefusedRatherThanSilentlyPinned() {
        rejected { VedikaChatConfig() }
        rejected { VedikaChatConfig(theme = VedikaChatTheme(accentColor = 0xFFFF6A00.toInt())) }
    }

    @Test
    fun aHostedUrlAndAPinnedCopyTogetherAreAmbiguous() {
        rejected {
            VedikaChatConfig(chatUrl = chatUrl, pinnedBundle = VedikaChatPinnedBundle())
        }
    }

    // --- the hosted URL ---------------------------------------------------------

    @Test
    fun theHostedUrlMustBeHttpsWithAHostAndNoCredentials() {
        rejected { VedikaChatConfig(chatUrl = "") }
        rejected { VedikaChatConfig(chatUrl = "http://chat.example.com/") }
        rejected { VedikaChatConfig(chatUrl = "chat.example.com/chat.html") }
        rejected { VedikaChatConfig(chatUrl = "https:///chat.html") }
        rejected { VedikaChatConfig(chatUrl = "https://user:secret@chat.example.com/") }
        rejected { VedikaChatConfig(chatUrl = "https://chat example.com/") }
        rejected { VedikaChatConfig(chatUrl = "file:///sdcard/chat.html") }
        rejected { VedikaChatConfig(chatUrl = "javascript:alert(1)") }
    }

    @Test
    fun theBridgeOriginFollowsTheHostedUrl() {
        assertEquals(
            setOf("https://chat.example.com"),
            VedikaChatConfig(chatUrl = chatUrl).effectiveAllowedOrigins
        )
        assertEquals(
            setOf("https://chat.example.com"),
            VedikaChatConfig(chatUrl = "HTTPS://Chat.Example.com:443/a?b=c#d").effectiveAllowedOrigins
        )
        assertEquals(
            setOf("https://chat.example.com:8443"),
            VedikaChatConfig(chatUrl = "https://chat.example.com:8443/x").effectiveAllowedOrigins
        )
    }

    @Test
    fun explicitAllowedOriginsReplaceTheDefaultButMustKeepTheChatOrigin() {
        val extra = setOf("https://chat.example.com", "*.example.org")
        assertEquals(extra, VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = extra).effectiveAllowedOrigins)
        // A list that left the chat page's own origin out would lock the page
        // out of the bridge, so it is refused up front.
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://other.example.com")) }
    }

    @Test
    fun aPinnedCopyDefaultsToTheAssetLoaderOrigin() {
        val config = VedikaChatConfig(pinnedBundle = VedikaChatPinnedBundle())
        assertEquals(setOf(VedikaChatConfig.ASSET_LOADER_ORIGIN), config.effectiveAllowedOrigins)
    }

    // --- what the WebView may load ---------------------------------------------

    @Test
    fun aHostedPageMayLoadOnlyFromItsOwnOrigin() {
        val policy = VedikaChatNavigationPolicy(VedikaChatConfig(chatUrl = chatUrl).networkOrigins)
        assertTrue(policy.allows(chatUrl))
        assertTrue(policy.allows("https://chat.example.com/embed/frame.js"))
        assertFalse(policy.allows("https://evil.example.net/"))
        assertFalse(policy.allows("https://chat.example.com.evil.net/"))
        assertFalse(policy.allows("http://chat.example.com/embed/chat.html"))
        assertFalse(policy.allows("https://chat.example.com:8443/"))
        assertFalse(policy.allows("data:text/html,hi"))
        assertFalse(policy.allows("javascript:alert(1)"))
        assertFalse(policy.allows("not a url"))
    }

    @Test
    fun aPinnedCopyMayLoadOnlyFromTheAssetLoaderEvenWithExtraBridgeOrigins() {
        val config = VedikaChatConfig(
            pinnedBundle = VedikaChatPinnedBundle(),
            allowedOrigins = setOf(VedikaChatConfig.ASSET_LOADER_ORIGIN, "https://chat.example.com")
        )
        val policy = VedikaChatNavigationPolicy(config.networkOrigins)
        assertTrue(policy.allows("https://appassets.androidplatform.net/assets/vedika-chat/index.html"))
        assertFalse(policy.allows("https://chat.example.com/"))
    }

    @Test
    fun httpAndHttpsOnTheSameHostGiveDifferentAnswers() {
        val exact = VedikaChatNavigationPolicy(setOf("https://chat.example.com"))
        assertTrue(exact.allows("https://chat.example.com/embed/chat.html"))
        assertFalse(exact.allows("http://chat.example.com/embed/chat.html"))

        val wildcard = VedikaChatNavigationPolicy(setOf("*.example.com"))
        assertTrue(wildcard.allows("https://chat.example.com/"))
        assertFalse(wildcard.allows("http://chat.example.com/"))
    }

    @Test
    fun aWildcardRuleNeverLetsANonHttpsSchemeIn() {
        val policy = VedikaChatNavigationPolicy(setOf("*.example.com"))
        assertFalse(policy.allows("http://chat.example.com/"))
        assertFalse(policy.allows("http://chat.example.com:80/"))
        assertFalse(policy.allows("http://chat.example.com:8080/"))
        assertFalse(policy.allows("HTTP://chat.example.com/"))
        assertFalse(policy.allows("ws://chat.example.com/"))
        assertFalse(policy.allows("wss://chat.example.com/"))
        assertFalse(policy.allows("ftp://chat.example.com/"))
        assertFalse(policy.allows("file://chat.example.com/x"))
        assertFalse(policy.allows("//chat.example.com/"))
        assertTrue(policy.allows("HTTPS://Chat.Example.com/"))
    }

    @Test
    fun anHttpRuleNeverMatchesAnHttpPage() {
        val policy = VedikaChatNavigationPolicy(setOf("http://chat.example.com"))
        assertFalse(policy.allows("http://chat.example.com/"))
        assertFalse(policy.allows("https://chat.example.com/"))
    }

    @Test
    fun dataAndAboutPagesNeverReachTheNetwork() {
        assertTrue(VedikaChatNavigationPolicy.isNonNetwork("data:text/html;base64,AAAA"))
        assertTrue(VedikaChatNavigationPolicy.isNonNetwork("about:blank"))
        assertFalse(VedikaChatNavigationPolicy.isNonNetwork("https://chat.example.com/"))
        assertFalse(VedikaChatNavigationPolicy.isNonNetwork("javascript:alert(1)"))
    }

    // --- origin rules (kept from the bridge allowlist) -------------------------

    @Test
    fun originRulesMatchExactlyOrByWildcardSubdomain() {
        val match = VedikaChatOrigins::ruleMatchesOrigin
        assertTrue(match("https://chat.example.com", "https://chat.example.com"))
        assertTrue(match("*.example.com", "https://chat.example.com"))
        assertTrue(match("*.example.com", "https://deep.chat.example.com"))
        assertFalse(match("*.example.com", "https://example.com"))
        assertFalse(match("*.example.com", "https://badexample.com"))
        assertFalse(match("https://chat.example.com", "https://chat.example.com:8443"))
    }

    @Test
    fun theBridgeOriginCheckAlsoRefusesNonHttpsPages() {
        val match = VedikaChatOrigins::ruleMatchesOrigin
        // The bridge compares the page's origin with the same function, so a
        // page served over http is refused there too.
        assertFalse(match("https://chat.example.com", "http://chat.example.com"))
        assertFalse(match("http://chat.example.com", "http://chat.example.com"))
        assertFalse(match("*.example.com", "http://chat.example.com"))
        assertFalse(match("*.example.com", "http://deep.chat.example.com:8080"))
        assertFalse(match("*.example.com", "ws://chat.example.com"))
        assertFalse(match("*.example.com", "chat.example.com"))
        assertTrue(match("*.example.com", "https://chat.example.com"))
    }

    @Test
    fun theAllowlistTakesOnlyOriginsThatCanMatch() {
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://chat.example.com", "http://other.example.com")) }
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://chat.example.com", "other.example.com")) }
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://chat.example.com/")) }
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://chat.example.com", "*.")) }
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://chat.example.com", "*.example.com/path")) }
        rejected { VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("*.example.com:8080", "https://chat.example.com")) }
        VedikaChatConfig(chatUrl = chatUrl, allowedOrigins = setOf("https://chat.example.com", "*.example.org", "https://x.example.net:8443"))
    }

    // --- the rest of the config -------------------------------------------------

    @Test
    fun theLoadTimeoutMustBePositive() {
        rejected { VedikaChatConfig(chatUrl = chatUrl, loadTimeoutMillis = 0) }
        assertEquals(
            VedikaChatConfig.DEFAULT_LOAD_TIMEOUT_MILLIS,
            VedikaChatConfig(chatUrl = chatUrl).loadTimeoutMillis
        )
    }

    @Test
    fun aBlankPinnedPathIsRefused() {
        rejected { VedikaChatPinnedBundle(assetsPath = " ") }
    }
}
