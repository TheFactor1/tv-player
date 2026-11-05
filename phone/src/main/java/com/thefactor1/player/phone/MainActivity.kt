package com.thefactor1.player.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Connecting to a TV, three ways: point the camera at the picture (nothing to press), pick a TV
 * found on the Wi-Fi (one OK on the TV remote), or type the TV's address and code. Once paired it
 * goes straight to the companion, and the now-playing notification takes over.
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
        status = text(15f, R.color.ink_secondary, medium()).apply {
            setText(R.string.find_looking)
            setPadding(0, dp(4), 0, dp(14))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val point = card().apply {
            addView(text(18f, R.color.ink, bold()).apply { setText(R.string.find_point) })
            addView(text(13f, R.color.ink_dim, medium()).apply {
                setText(R.string.find_point_detail)
                setPadding(0, dp(3), 0, 0)
            })
            setOnClickListener { startActivity(Intent(this@MainActivity, ScannerActivity::class.java)) }
        }
        val manual = text(15f, R.color.ink_secondary, bold()).apply {
            setText(R.string.find_manual)
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { showManual() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(88), pad, pad)
            addView(text(30f, R.color.ink, bold()).apply {
                setText(R.string.find_title)
                setPadding(0, 0, 0, dp(22))
            })
            addView(point, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(28)
            })
            addView(text(13f, R.color.ink_dim, bold()).apply {
                setText(R.string.find_nearby)
                isAllCaps = true
                letterSpacing = 0.08f
            })
            addView(status)
            addView(list)
            addView(manual)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(getColor(R.color.raised))
            setStroke(dp(1), getColor(R.color.hairline))
        }
        isClickable = true
        isFocusable = true
    }

    /** The TV's address and the 4-digit code from its settings, for when finding it doesn't work. */
    private fun showManual() {
        val address = field(R.string.manual_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        store.host?.let { address.setText(it) }
        val code = field(R.string.manual_code, InputType.TYPE_CLASS_NUMBER).apply {
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val error = text(13f, R.color.ink_secondary, medium()).apply { setPadding(0, dp(8), 0, 0) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
            addView(text(13f, R.color.ink_dim, medium()).apply { setText(R.string.manual_detail) })
            addView(address)
            addView(code)
            addView(error)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.manual_title)
            .setView(form)
            .setPositiveButton(R.string.manual_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = address.text.toString().trim().substringBefore(':').removePrefix("http://")
                val typed = code.text.toString().trim()
                if (host.isEmpty() || typed.length != 4) return@setOnClickListener
                error.text = ""
                Thread {
                    val result = TvApi(host, PORT, null).pair(typed, DeviceName.of(this))
                    main.post {
                        result.onSuccess { token ->
                            dialog.dismiss()
                            savePairing(FoundTv(TV_NAME, host, PORT), token)
                        }.onFailure { e ->
                            error.setText(if (e.message == "code") R.string.manual_wrong else R.string.manual_unreachable)
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun field(hint: Int, type: Int) = EditText(this).apply {
        setHint(hint)
        inputType = type
        setSingleLine()
        setTextColor(getColor(R.color.ink))
        setHintTextColor(getColor(R.color.ink_dim))
        typeface = medium()
    }

    private fun show(tv: FoundTv) {
        val key = "${tv.host}:${tv.port}"
        if (key in found) return
        found[key] = tv
        if (!asking) status.setText(R.string.find_pick)
        val row = card().apply {
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
        val device = DeviceName.of(this)
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
        store.forget()
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
        private const val PORT = 8765
        // What every TV Player advertises itself as on the Wi-Fi.
        const val TV_NAME = "TV Player"
    }
}
