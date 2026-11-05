package com.thefactor1.player.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The pairing itself: which TV this phone is connected to, connecting afresh (or to another TV),
 * and forgetting it, which also tells the TV to let this phone go.
 */
class PairingActivity : Activity() {
    private val store by lazy { TvStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!store.paired) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val pad = dp(28)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(88), pad, pad)
            addView(text(30f, R.color.ink, bold()).apply { setText(R.string.pairing_title) })
            addView(text(15f, R.color.ink_secondary, medium()).apply {
                text = getString(R.string.pairing_connected, "${store.name ?: MainActivity.TV_NAME} · ${store.host}")
                setPadding(0, dp(6), 0, dp(28))
            })
            addView(row(R.string.pairing_again, R.string.pairing_again_detail) {
                startActivity(Intent(this@PairingActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAIR_AGAIN, true))
                finish()
            })
            addView(row(R.string.pairing_forget, R.string.pairing_forget_detail) { forget() })
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        })
    }

    private fun forget() {
        val api = store.api()
        Thread { runCatching { api?.unpair() } }.start()
        store.forget()
        stopService(Intent(this, NowPlayingService::class.java))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun row(title: Int, detail: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(getColor(R.color.raised))
            setStroke(dp(1), getColor(R.color.hairline))
        }
        isClickable = true
        isFocusable = true
        addView(text(17f, R.color.ink, bold()).apply { setText(title) })
        addView(text(13f, R.color.ink_dim, medium()).apply {
            setText(detail)
            setPadding(0, dp(3), 0, 0)
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
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
}
