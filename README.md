# vedika-chat-android

Public mirror of the Vedika chat Android library, version 0.1.0.

## Install

Via [JitPack](https://jitpack.io/#vedika-io/vedika-chat-android) (Gradle):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.vedika-io:vedika-chat-android:0.1.0")
}
```

The library is an Android AAR (minSdk 23). It is not on Maven Central.

Vedika chat for Android phones and tablets.

This module is the Kotlin counterpart of the Vedika chat Swift package
(`vedika-chat-swift`, WKWebView). It hosts the bundled Vedika chat inside a `WebView` and
bridges a short-lived token from the host app down to the page through
`WebViewCompat.addWebMessageListener` (the recommended Android bridge
with platform-level origin checks). No `addJavascriptInterface` and no
in-house SSE framer — the page streams itself.

## Hosted by default

The chat page is loaded from its https URL (`chatUrl`). Chat fixes and
new features reach every installed app the next time the chat opens, with
no app release and no store review.

If the page cannot be reached (offline, DNS, TLS, a slow server, or an
HTTP error from the server) the view shows a small offline page with a
**Retry** link, and calls `onLoadError` with a `VedikaChatLoadException`
that names the failure. Retry reloads the hosted URL. A host can also
call `chat.retry()` itself, for example when connectivity returns. Only
failures of the main page show the offline page; a missing image or a
failed sub-request does not replace a working chat. The offline text is
`VedikaChatConfig.offlineText`, so a host can localise it.

A copy of the page can instead be shipped inside the app with
`pinnedBundle`. That is an explicit opt-in: a pinned copy stays at the
version it was built with until you release a new app, so it does not get
chat fixes on its own. Choose it only when the chat must open with no
network at all. The pinned copy keeps its manifest check (SHA-256 over
every file) and is served from `https://appassets.androidplatform.net`.

## Layout

```
mobile-android/
├── build.gradle.kts            Root project config (com.android.library
│                               plugin, no Compose dependency needed)
├── settings.gradle.kts         Modules: :library, :example
├── library/                    The chat SDK
│   ├── build.gradle.kts        com.android.library, kotlin android,
│   │                           kotlinx.serialization
│   ├── consumer-rules.pro      Proguard keep rules for the envelope types
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/io/vedika/chat/
│       │       ├── VedikaChatConfig.kt
│       │       ├── VedikaChatSource.kt     hosted vs pinned, origins
│       │       ├── VedikaChatOffline.kt    load tracking + offline page
│       │       ├── VedikaChatManifest.kt
│       │       ├── VedikaChatToken.kt
│       │       ├── VedikaChatBridge.kt
│       │       ├── VedikaChatBridgeLogger.kt
│       │       └── VedikaChatView.kt
│       └── test/kotlin/io/vedika/chat/
│           ├── VedikaChatConfigTest.kt
│           ├── VedikaChatOfflineTest.kt
│           ├── VedikaChatManifestTest.kt
│           └── VedikaChatBridgeTest.kt
└── example/                    A runnable example app
    ├── build.gradle.kts        com.android.application
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/io/vedika/chat/example/MainActivity.kt
        └── assets/vedika-chat/  A placeholder for the pinned opt-in
```

## What is NOT here

- The chat page itself. It is hosted. For the pinned opt-in, Vedika
  provides a bundle to drop into `assets/vedika-chat/`; the example
  ships a placeholder so the build is self-contained.
- A native SSE / stream core. The page streams itself.
- Native Compose / View chat components. The page owns the layout.
- Any reference to a provider or model name. Wire contract only.

## Usage

```kotlin
import io.vedika.chat.VedikaChatConfig
import io.vedika.chat.VedikaChatTheme
import io.vedika.chat.VedikaChatView
import io.vedika.chat.VedikaChatToken
import io.vedika.chat.VedikaChatTokenProvider
import io.vedika.chat.VedikaChatTokenRequest

val chat = VedikaChatView(this).apply {
    setConfig(
        VedikaChatConfig(
            // The hosted chat page. Loaded from the network on every open.
            chatUrl = "https://chat.your-site.com/chat.html",
            theme = VedikaChatTheme(
                backgroundColor = 0xFF101418.toInt(),
                textColor = 0xFFF6F7F8.toInt(),
                accentColor = 0xFFFF6A00.toInt(),
                fontFamily = "Helvetica, sans-serif",
                customCss = ":root{--vedika-radius:14px;}"
            )
        )
    )
    setTokenProvider(object : VedikaChatTokenProvider {
        override suspend fun acquire(request: VedikaChatTokenRequest): VedikaChatToken {
            // POST https://harness.vedika.io/api/v1/embed/tokens ...
            return VedikaChatToken(value = "...", expiresAt = ...)
        }
    })
    // Called when the hosted page could not be reached; the view is
    // already showing its offline page with a Retry link.
    onLoadError = { error -> /* log it; error is a VedikaChatLoadException */ }
}
container.addView(chat)
chat.loadChat()
```

To ship a pinned copy instead (explicit opt-in, see above), name it and
leave `chatUrl` out:

```kotlin
VedikaChatConfig(pinnedBundle = VedikaChatPinnedBundle(assetsPath = "vedika-chat"))
```

There is no default source: a config with neither `chatUrl` nor
`pinnedBundle` is refused, so an app can never end up pinned by
accident.

Upgrading from 0.1.0: `VedikaChatConfig(assetsPath = ..., entryPoint = ...)`
becomes `VedikaChatConfig(pinnedBundle = VedikaChatPinnedBundle(...))`,
and the recommended config is now `VedikaChatConfig(chatUrl = ...)`.

## Security

The bridge speaks the same wire envelope as the iOS SDK and is locked
to the page's own origin at the platform level: the origin of `chatUrl`
(for example `https://chat.your-site.com`), or
`https://appassets.androidplatform.net` for a pinned copy.
`chatUrl` must be https, with a host and no embedded credentials. The
WebView loads nothing from any other origin, and a link or redirect to
another site is refused. `allowedOrigins` replaces the default origin
when set, and must still include the origin of `chatUrl`. Entries are
`https://host[:port]` origins or `*.host` wildcards; anything else
(an `http://` origin, a bare host, a trailing slash) is refused when the
config is built, because it could never match. Only `https` URLs are ever
loaded or answered: a wildcard rule names no scheme, so the scheme is
checked first and an `http://` page on an allowed host is refused.
Inside the handler the bridge compares the full `sourceOrigin` against
the host's allowlist as defence in depth, supports wildcard rules
(`*.example.com`), and never logs the token. The default logger is a no-op; hosts that want
diagnostics install a `VedikaChatBridgeLogger`.

## Safe areas and theming

The view listens for window insets and forwards the system-bar /
display-cutout values to the page as CSS variables
(`--vedika-safe-top`, `--vedika-safe-bottom`, `--vedika-safe-left`,
`--vedika-safe-right`, `--vedika-ime-bottom`). It also injects the
host's theme tokens (`--vedika-bg`, `--vedika-fg`, `--vedika-accent`,
`--vedika-font`) via `evaluateJavascript` on first load and on every
theme change. Call `injectTheme(theme)` after a dark-mode toggle.

## Required host permissions

- `INTERNET`. The library's manifest declares it, because the hosted
  page is loaded over https and the host backend (the one minting `vt_`
  tokens) is reached from the app. A pinned copy renders without the
  network, but it still needs it to mint tokens.

## Build

```sh
./gradlew :library:assemble
```

## Test

```sh
./gradlew :library:test
```

## Wire contract

The bridge speaks a single JSON envelope:

```json
{ "kind": "acquireToken", "payload": { "acquireToken": { "reason": "init", "threadId": null } } }
```

The page posts this string to the WebView message listener named
`vedika` and resolves a Promise with the reply:

```json
{ "ok": true, "data": { "token": { "value": "tk_...", "expiresAt": 1900000000 } } }
```

Additional kinds may be added without breaking changes by appending a
new field to `VedikaChatBridgeMessage.BridgePayload` and dispatching on
the `kind` string.
