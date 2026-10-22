package com.thefactor1.player.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First run: finds the TV on the Wi-Fi and asks it to let this phone in (one OK on the TV remote).
 * Once paired it goes straight to the companion, and the now-playing notification takes over.
 */
class MainActivity : Activity() {
    private val store by lazy { TvStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var finder: TvFinder
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val found = LinkedHashMap<String, FoundTv>()
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (store.paired && intent?.getBooleanExtra(EXTRA_PAIR_AGAIN, false) != true) {
            NowPlayingService.start(this)
            startActivity(Intent(this, CompanionActivity::class.java))
            finish()
            return
        }
        store.forget()
        askForNotifications()
        buildScreen()
        finder = TvFinder(this) { tv -> show(tv) }
        main.postDelayed({ if (found.isEmpty()) status.setText(R.string.find_none) }, NONE_FOUND_MS)
    }

    override fun onStart() {
        super.onStart()
        if (::finder.isInitialized) finder.start()
    }

    override fun onStop() {
        super.onStop()
        if (::finder.isInitialized) finder.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
    }

    private fun buildScreen() {
        val pad = dp(28)
        status = text(16f, R.color.ink_secondary, medium()).apply {
            setText(R.string.find_looking)
            setPadding(0, dp(10), 0, dp(24))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(96), pad, pad)
            addView(text(30f, R.color.ink, bold()).apply { setText(R.string.find_title) })
            addView(status)
            addView(list)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        })
    }

    private fun show(tv: FoundTv) {
        val key = "${tv.host}:${tv.port}"
        if (key in found) return
        found[key] = tv
        if (!asking) status.setText(R.string.find_pick)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.raised))
                setStroke(dp(1), getColor(R.color.hairline))
            }
            isClickable = true
            isFocusable = true
            addView(text(18f, R.color.ink, bold()).apply { text = tv.name })
            addView(text(13f, R.color.ink_dim, medium()).apply { text = tv.host })
            setOnClickListener { ask(tv) }
            alpha = 0f
            translationY = dp(8).toFloat()
        }
        list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        row.animate().alpha(1f).translationY(0f).setDuration(260).start()
    }

    /** Puts the question on the TV, then checks back once a second for the answer. */
    private fun ask(tv: FoundTv) {
        if (asking) return
        asking = true
        status.setText(R.string.find_asking)
        val device = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        Thread {
            val api = TvApi(tv.host, tv.port, null)
            val request = api.hello(device)
            val id = request.getOrNull()
            if (id == null) {
                val screen = request.exceptionOrNull()?.message == "screen"
                return@Thread showResult(if (screen) R.string.find_screen else R.string.find_failed)
            }
            val deadline = System.currentTimeMillis() + ANSWER_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(CHECK_MS)
                val (answer, token) = try {
                    api.helloAnswer(id)
                } catch (e: Exception) {
                    continue
                }
                when (answer) {
                    "approved" -> return@Thread paired(tv, token ?: return@Thread showResult(R.string.find_failed))
                    "denied", "expired", "unknown" -> return@Thread showResult(R.string.find_denied)
                }
            }
            showResult(R.string.find_denied)
        }.start()
    }

    private fun showResult(message: Int) {
        main.post {
            asking = false
            status.setText(message)
        }
    }

    private fun paired(tv: FoundTv, token: String) {
        main.post { savePairing(tv, token) }
    }

    private fun savePairing(tv: FoundTv, token: String) {
        store.host = tv.host
        store.port = tv.port
        store.name = tv.name
        store.token = token
        status.setText(R.string.find_done)
        NowPlayingService.start(this)
        startActivity(Intent(this, CompanionActivity::class.java))
        finish()
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun text(sp: Float, color: Int, face: Typeface) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
        typeface = face
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun bold() = resources.getFont(R.font.manrope_bold)
    private fun medium() = resources.getFont(R.font.manrope_medium)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAIR_AGAIN = "pair_again"
        private const val NONE_FOUND_MS = 8_000L
        private const val ANSWER_WAIT_MS = 65_000L
        private const val CHECK_MS = 1_000L
    }
}
