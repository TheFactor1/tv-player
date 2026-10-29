package com.thefactor1.player.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The companion itself: the TV's own transcript, search, cast and remote page, opened already
 * signed in. If the TV moved to a new address, it's found again by name first.
 */
class CompanionActivity : Activity() {
    private val store by lazy { TvStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!store.paired) return pairAgain()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(getColor(R.color.ground))
            webViewClient = object : WebViewClient() {
                // Only the paired TV's own page stays in here; any other link opens in the phone's browser.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (isPairedTv(url)) return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (e: ActivityNotFoundException) {
                        // Nothing on the phone can open it: it just doesn't open.
                    }
                    return true
                }
            }
            // Fade in once the page has drawn, rather than flashing white first.
            alpha = 0f
            visibility = View.INVISIBLE
        }
        setContentView(web)
        NowPlayingService.start(this)
        open()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::web.isInitialized) open()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
    }

    private fun open() {
        Thread {
            val url = Locate.current(this, store)?.pageUrl
            main.post {
                if (isDestroyed) return@post
                if (url == null) {
                    // TV off or out of reach: show the page's address anyway so it loads when the TV is back.
                    store.api()?.let { web.loadUrl(it.pageUrl) }
                } else {
                    web.loadUrl(url)
                }
                web.visibility = View.VISIBLE
                web.animate().alpha(1f).setStartDelay(FADE_DELAY_MS).setDuration(FADE_MS).start()
            }
        }.start()
    }

    /** Scheme, host and port all match the TV this phone is paired with (read now: the TV may have moved). */
    private fun isPairedTv(url: Uri): Boolean {
        val port = if (url.port == -1) DEFAULT_HTTP_PORT else url.port
        return url.scheme == "http" && url.host != null && url.host == store.host && port == store.port
    }

    private fun pairAgain() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAIR_AGAIN, true))
        finish()
    }

    private companion object {
        const val FADE_DELAY_MS = 150L
        const val FADE_MS = 240L
        const val DEFAULT_HTTP_PORT = 80
    }
}
