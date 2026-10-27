package com.thefactor1.player.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The companion itself: the TV's own transcript, search, cast and remote page, opened already
 * signed in. If the TV moved to a new address, it's found again by name first. When it can't be
 * reached, a plain note says the likeliest reason and keeps trying, instead of the browser's error.
 */
class CompanionActivity : Activity() {
    private val store by lazy { TvStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var away: View
    private lateinit var awayDetail: TextView
    private lateinit var awayStatus: TextView
    @Volatile private var looking = false
    private var pageFailed = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val retry = Runnable { open() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!store.paired) return pairAgain()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(getColor(R.color.ground))
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    pageFailed = false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    pageFailed = true
                    // Keeps the browser's own error page from showing underneath.
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    showAway()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!pageFailed && url != null && url != "about:blank") hideAway()
                }
            }
            // The page shows a pairing button when it's opened here rather than in a browser.
            addJavascriptInterface(Bridge(), "TvCompanionApp")
            // Fade in once the page has drawn, rather than flashing white first.
            alpha = 0f
            visibility = View.INVISIBLE
        }
        away = buildAway()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.ground))
            addView(web)
            addView(away)
        })
        NowPlayingService.start(this)
        open()
    }

    override fun onStart() {
        super.onStart()
        // Back on the network (Wi-Fi on, VPN off): try again straight away.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { if (away.visibility == View.VISIBLE) open() }
            }
        }
        runCatching { getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    override fun onStop() {
        super.onStop()
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        networkCallback = null
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
        if (looking) return
        looking = true
        main.removeCallbacks(retry)
        if (away.visibility == View.VISIBLE) awayStatus.setText(R.string.reach_trying)
        Thread {
            val url = Locate.current(this, store)?.pageUrl
            val why = when {
                url != null -> null
                store.api()?.probe() == HTTP_UNAUTHORIZED -> Unreachable.FORGOTTEN
                else -> Reach.why(this, store.host)
            }
            main.post {
                looking = false
                if (isDestroyed) return@post
                if (url == null) {
                    showAway(why)
                    return@post
                }
                web.loadUrl(url)
                web.visibility = View.VISIBLE
                web.animate().alpha(1f).setStartDelay(FADE_DELAY_MS).setDuration(FADE_MS).start()
            }
        }.start()
    }

    /** The note over the page, with the likeliest reason; it tries again by itself every few seconds. */
    private fun showAway(why: Unreachable? = null) {
        val reason = why ?: Reach.why(this, store.host)
        awayDetail.text = when (reason) {
            Unreachable.OFFLINE -> getString(R.string.reach_offline)
            Unreachable.MOBILE_DATA -> getString(R.string.reach_mobile)
            Unreachable.VPN -> getString(R.string.reach_vpn)
            Unreachable.OTHER_WIFI -> getString(R.string.reach_other_wifi, store.host ?: "")
            Unreachable.FORGOTTEN -> getString(R.string.reach_forgotten)
            Unreachable.TV_SIDE -> getString(R.string.reach_tv)
        }
        awayStatus.setText(if (reason == Unreachable.FORGOTTEN) R.string.reach_forgotten_hint else R.string.reach_retrying)
        if (away.visibility != View.VISIBLE) {
            away.alpha = 0f
            away.visibility = View.VISIBLE
            away.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        main.removeCallbacks(retry)
        if (reason != Unreachable.FORGOTTEN) main.postDelayed(retry, RETRY_MS)
    }

    private fun hideAway() {
        main.removeCallbacks(retry)
        if (away.visibility != View.VISIBLE) return
        away.animate().alpha(0f).setDuration(FADE_MS).withEndAction { away.visibility = View.GONE }.start()
    }

    private fun buildAway(): View {
        val pad = dp(28)
        awayDetail = text(15f, R.color.ink_secondary, medium()).apply {
            setPadding(0, dp(10), 0, dp(24))
            setLineSpacing(0f, 1.15f)
        }
        awayStatus = text(13f, R.color.ink_dim, medium()).apply { setPadding(0, dp(18), 0, 0) }
        val tryAgain = text(16f, R.color.ink, bold()).apply {
            setText(R.string.reach_try)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.raised))
                setStroke(dp(1), getColor(R.color.hairline))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { open() }
        }
        val pairAgain = text(14f, R.color.ink_secondary, medium()).apply {
            setText(R.string.reach_pair)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { pairAgain() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.ground))
            visibility = View.GONE
            isClickable = true
            addView(text(26f, R.color.ink, bold()).apply { setText(R.string.reach_title) })
            addView(awayDetail)
            addView(tryAgain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(pairAgain, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(awayStatus)
        }
    }

    private fun text(sp: Float, color: Int, face: Typeface) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
        typeface = face
    }

    private fun bold() = resources.getFont(R.font.manrope_bold)
    private fun medium() = resources.getFont(R.font.manrope_medium)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** What the TV's page can ask of the app; nothing beyond opening its own screens. */
    private inner class Bridge {
        @JavascriptInterface
        fun openPairing() {
            main.post { startActivity(Intent(this@CompanionActivity, PairingActivity::class.java)) }
        }
    }

    private fun pairAgain() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAIR_AGAIN, true))
        finish()
    }

    private companion object {
        const val FADE_DELAY_MS = 150L
        const val FADE_MS = 240L
        const val RETRY_MS = 5_000L
        const val HTTP_UNAUTHORIZED = 401
    }
}
