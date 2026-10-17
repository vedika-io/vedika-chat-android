// Example app showing how a host embeds the Vedika chat SDK.
//
// The chat page is hosted: the config names its https URL and the
// WebView loads it, so a chat fix reaches this app without an app
// release. If the page cannot be reached the view shows an offline page
// with a Retry link. `USE_PINNED_COPY` flips the example to the
// explicit opt-in: a copy of the page shipped in `assets/vedika-chat/`
// that only changes when this app is released.
//
// Wires an in-memory token provider and presents the chat view full
// screen. A real host calls its backend's `vt_` mint endpoint here.
// The example also installs a custom theme so the chat picks up the
// host's brand colours, and the safe-area insets are forwarded
// automatically by `VedikaChatView` via its `OnApplyWindowInsetsListener`.

package io.vedika.chat.example

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.vedika.chat.VedikaChatConfig
import io.vedika.chat.VedikaChatPinnedBundle
import io.vedika.chat.VedikaChatTheme
import io.vedika.chat.VedikaChatToken
import io.vedika.chat.VedikaChatTokenProvider
import io.vedika.chat.VedikaChatTokenRequest
import io.vedika.chat.VedikaChatView

class MainActivity : AppCompatActivity() {

    private companion object {
        /** The hosted chat page. Replace with the URL your Vedika contact gave you. */
        const val CHAT_URL = "https://chat.example.com/chat.html"

        /** Opt in to the copy of the page shipped in this app's assets. */
        const val USE_PINNED_COPY = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)
        title = "Vedika Chat — Example"

        val theme = VedikaChatTheme(
            backgroundColor = Color.parseColor("#101418"),
            textColor = Color.parseColor("#f6f7f8"),
            accentColor = Color.parseColor("#ff6a00"),
            fontFamily = "Helvetica, sans-serif",
            customCss = ":root{--vedika-radius:14px;}"
        )
        val config = if (USE_PINNED_COPY) {
            VedikaChatConfig(pinnedBundle = VedikaChatPinnedBundle(), theme = theme)
        } else {
            VedikaChatConfig(chatUrl = CHAT_URL, theme = theme)
        }

        val chat = VedikaChatView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setConfig(config)
            setTokenProvider { request ->
                // The provider intentionally logs the request reason
                // and thread id, NOT the returned token. The bridge
                // never logs the token either.
                Log.i(
                    "VedikaChatExample",
                    "acquireToken reason=${request.reason} thread=${request.threadId}"
                )
                // Real host: call POST https://harness.vedika.io/api/v1/embed/tokens ...
                VedikaChatToken(
                    value = "tk_example_${System.currentTimeMillis()}",
                    expiresAt = 0
                )
            }
            onLoadError = { error ->
                Log.e("VedikaChatExample", "Chat load failed: $error")
            }
        }
        container.addView(chat)
        chat.loadChat()
    }
}