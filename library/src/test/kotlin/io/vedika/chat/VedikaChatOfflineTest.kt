// Tests for the offline fallback: when it appears, what it says, and
// that its Retry link reloads the hosted chat URL.

package io.vedika.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VedikaChatOfflineTest {

    private fun tracker(): VedikaChatLoadTracker = VedikaChatLoadTracker().apply { begin() }

    // --- the fallback appears on a main-page failure ----------------------------

    @Test
    fun aPageThatFinishesIsReadyAndNeverOffline() {
        val t = tracker()
        assertEquals(VedikaChatLoadTracker.State.LOADING, t.state)
        assertTrue(t.pageFinished())
        assertEquals(VedikaChatLoadTracker.State.READY, t.state)
    }

    @Test
    fun aNetworkFailureShowsTheFallback() {
        val t = tracker()
        val error = t.mainFrameError(-2) // ERROR_HOST_LOOKUP
        assertNotNull(error)
        assertEquals(VedikaChatLoadFailure.NETWORK, error!!.failure)
        assertEquals(VedikaChatLoadTracker.State.OFFLINE, t.state)
    }

    @Test
    fun everyKindOfFailureIsNamed() {
        assertEquals(VedikaChatLoadFailure.NETWORK, VedikaChatLoadTracker.classify(-2)) // host lookup
        assertEquals(VedikaChatLoadFailure.NETWORK, VedikaChatLoadTracker.classify(-6)) // connect
        assertEquals(VedikaChatLoadFailure.NETWORK, VedikaChatLoadTracker.classify(-7)) // io
        assertEquals(VedikaChatLoadFailure.TIMEOUT, VedikaChatLoadTracker.classify(-8))
        assertEquals(VedikaChatLoadFailure.SECURE_CONNECTION, VedikaChatLoadTracker.classify(-11))
        assertEquals(VedikaChatLoadFailure.OTHER, VedikaChatLoadTracker.classify(-1))
        assertEquals(VedikaChatLoadFailure.OTHER, VedikaChatLoadTracker.classify(-12))
    }

    @Test
    fun anHttpErrorOnTheMainPageShowsTheFallback() {
        for (status in listOf(400, 401, 403, 404, 429, 500, 502, 503, 504)) {
            val t = tracker()
            val error = t.mainFrameHttpError(status)
            assertNotNull("$status", error)
            assertEquals(VedikaChatLoadFailure.HTTP_ERROR, error!!.failure)
            assertEquals(status, error.httpStatus)
            assertEquals(VedikaChatLoadTracker.State.OFFLINE, t.state)
        }
    }

    @Test
    fun aSuccessOrRedirectStatusIsNotAFailure() {
        for (status in listOf(200, 204, 301, 304, 399)) {
            val t = tracker()
            assertNull("$status", t.mainFrameHttpError(status))
            assertEquals(VedikaChatLoadTracker.State.LOADING, t.state)
        }
    }

    @Test
    fun aSlowLoadTimesOutIntoTheFallback() {
        val t = tracker()
        val error = t.timedOut()
        assertEquals(VedikaChatLoadFailure.TIMEOUT, error!!.failure)
        assertEquals(VedikaChatLoadTracker.State.OFFLINE, t.state)
    }

    @Test
    fun aWorkingChatIsNeverReplacedByALateTimeout() {
        val t = tracker()
        t.pageFinished()
        assertNull(t.timedOut())
        assertEquals(VedikaChatLoadTracker.State.READY, t.state)
    }

    @Test
    fun aFailureIsReportedOnceEvenIfTheWebViewReportsItTwice() {
        val t = tracker()
        assertNotNull(t.mainFrameError(-6))
        assertNull(t.mainFrameHttpError(503))
        assertNull(t.mainFrameError(-6))
        assertNull(t.timedOut())
    }

    @Test
    fun theErrorPageFinishingDoesNotTurnTheFallbackIntoReady() {
        val t = tracker()
        t.mainFrameError(-2)
        // The WebView reports a finished load for its own error page and for
        // the fallback we draw over it.
        assertFalse(t.pageFinished())
        assertFalse(t.pageFinished())
        assertEquals(VedikaChatLoadTracker.State.OFFLINE, t.state)
    }

    @Test
    fun aPageThatWasNeverRequestedIsNotReady() {
        val t = VedikaChatLoadTracker()
        assertEquals(VedikaChatLoadTracker.State.IDLE, t.state)
        assertFalse(t.pageFinished())
        assertNull(t.timedOut())
    }

    // --- Retry reloads the hosted URL ------------------------------------------

    @Test
    fun retryFromTheFallbackStartsAnotherLoad() {
        val t = tracker()
        t.mainFrameError(-2)
        assertTrue(t.retry())
        assertEquals(VedikaChatLoadTracker.State.LOADING, t.state)
        // ... and a successful second load is ready.
        assertTrue(t.pageFinished())
        assertEquals(VedikaChatLoadTracker.State.READY, t.state)
    }

    @Test
    fun retryDoesNothingWhileTheChatIsLoadingOrWorking() {
        val loading = tracker()
        assertFalse(loading.retry())
        val ready = tracker().apply { pageFinished() }
        assertFalse(ready.retry())
        assertEquals(VedikaChatLoadTracker.State.READY, ready.state)
    }

    @Test
    fun aSecondFailureAfterRetryShowsTheFallbackAgain() {
        val t = tracker()
        t.mainFrameError(-2)
        t.retry()
        assertNotNull(t.mainFrameError(-2))
        assertEquals(VedikaChatLoadTracker.State.OFFLINE, t.state)
        assertTrue(t.retry())
    }

    @Test
    fun theRetryLinkIsTheUrlTheViewInterceptsAndNothingElseIs() {
        assertTrue(VedikaChatOfflinePage.isRetryUrl(VedikaChatOfflinePage.RETRY_URL))
        assertTrue(VedikaChatOfflinePage.isRetryUrl("vedika-chat-retry://now"))
        assertFalse(VedikaChatOfflinePage.isRetryUrl("https://chat.example.com/"))
        assertFalse(VedikaChatOfflinePage.isRetryUrl("https://evil.example.net/vedika-chat-retry:"))
        assertFalse(VedikaChatOfflinePage.isRetryUrl(null))
        // The Retry link is not a network origin, so the navigation policy
        // can never confuse it with a page to load.
        val policy = VedikaChatNavigationPolicy(setOf("https://chat.example.com"))
        assertFalse(policy.allows(VedikaChatOfflinePage.RETRY_URL))
    }

    // --- the page itself --------------------------------------------------------

    @Test
    fun theFallbackShowsTheTextAndALinkToRetry() {
        val html = VedikaChatOfflinePage.render(VedikaChatOfflineText(), VedikaChatTheme())
        assertTrue(html.contains("You're offline".replace("'", "&#39;")))
        assertTrue(html.contains("Check your connection"))
        assertTrue(html.contains(">Retry</a>"))
        assertTrue(html.contains("href=\"${VedikaChatOfflinePage.RETRY_URL}\""))
    }

    @Test
    fun theFallbackRunsNoScriptAndLoadsNothing() {
        val html = VedikaChatOfflinePage.render(VedikaChatOfflineText(), VedikaChatTheme())
        assertFalse(html.contains("<script", ignoreCase = true))
        assertFalse(html.contains("http://"))
        assertFalse(html.contains("https://"))
        assertTrue(html.contains("default-src 'none'"))
    }

    @Test
    fun hostsCanLocaliseTheTextAndItIsEscaped() {
        val html = VedikaChatOfflinePage.render(
            VedikaChatOfflineText(
                title = "Sin conexion <b>",
                message = "Revisa tu conexion & vuelve a intentarlo \"ahora\"",
                retryLabel = "Reintentar"
            ),
            VedikaChatTheme()
        )
        assertTrue(html.contains("Sin conexion &lt;b&gt;"))
        assertTrue(html.contains("&amp; vuelve a intentarlo &quot;ahora&quot;"))
        assertTrue(html.contains(">Reintentar</a>"))
        assertFalse(html.contains("<b>"))
    }

    @Test
    fun theFallbackUsesTheHostTheme() {
        val html = VedikaChatOfflinePage.render(
            VedikaChatOfflineText(),
            VedikaChatTheme(
                backgroundColor = 0xFF101418.toInt(),
                textColor = 0xFFF6F7F8.toInt(),
                accentColor = 0xFFFF6A00.toInt()
            )
        )
        assertTrue(html.contains("background:#101418"))
        assertTrue(html.contains("color:#F6F7F8"))
        assertTrue(html.contains("background:#FF6A00"))
    }

    @Test
    fun theLoadErrorNamesTheFailureWithoutAnyUrl() {
        val withStatus = VedikaChatLoadException(VedikaChatLoadFailure.HTTP_ERROR, 503)
        assertEquals("Chat page failed to load: HTTP_ERROR (503)", withStatus.message)
        val withoutStatus = VedikaChatLoadException(VedikaChatLoadFailure.NETWORK)
        assertEquals("Chat page failed to load: NETWORK", withoutStatus.message)
    }
}
