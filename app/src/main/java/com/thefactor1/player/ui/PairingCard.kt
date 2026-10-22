package com.thefactor1.player.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.thefactor1.player.R
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.companion.QrCode

/**
 * Scan-to-pair: a QR code that opens the phone page with the code filled in, plus the address and code
 * to type by hand. The code changes once a phone pairs, so the card notices, says so, and closes.
 */
object PairingCard {
    private const val QR_DP = 240
    private const val QUIET_DP = 14
    private const val WATCH_MS = 1_000L
    private const val CLOSE_AFTER_PAIRED_MS = 1_800L

    fun show(context: Context, fonts: Fonts) {
        val url = PhoneCompanion.pairUrl()
        val address = PhoneCompanion.address()
        val code = PhoneCompanion.code
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun text(sp: Float, color: Int, typeface: android.graphics.Typeface, value: String) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(ContextCompat.getColor(context, color))
            this.typeface = typeface
        }

        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val handler = Handler(Looper.getMainLooper())
        val status = text(16f, R.color.ink_dim, fonts.medium, context.getString(R.string.pair_close))

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(28f, R.color.ink, fonts.bold, context.getString(if (url != null) R.string.pair_title else R.string.companion_title)))
            addView(text(17f, R.color.ink_secondary, fonts.medium,
                context.getString(if (url != null) R.string.pair_scan else R.string.companion_offline)).apply {
                setPadding(0, dp(10), 0, 0)
                setLineSpacing(0f, 1.3f)
            })
            if (address != null) {
                addView(text(15f, R.color.ink_dim, fonts.medium, context.getString(R.string.pair_manual, address)).apply {
                    setPadding(0, dp(28), 0, 0)
                })
                addView(text(44f, R.color.ink, fonts.mono, code).apply {
                    letterSpacing = 0.18f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            addView(status.apply { setPadding(0, dp(28), 0, 0) })
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), dp(36), dp(44), dp(36))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ContextCompat.getColor(context, R.color.ground))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.hairline))
            }
            if (url != null) {
                val qr = ImageView(context).apply {
                    setImageBitmap(QrCode.bitmap(url, dp(QR_DP)))
                    setPadding(dp(QUIET_DP), dp(QUIET_DP), dp(QUIET_DP), dp(QUIET_DP))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.WHITE)
                    }
                    contentDescription = context.getString(R.string.pair_title)
                }
                val side = dp(QR_DP + QUIET_DP * 2)
                addView(qr, LinearLayout.LayoutParams(side, side).apply { marginEnd = dp(40) })
            }
            addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.pause_veil))
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        // A new code means a phone just paired with the one on screen.
        val watch = object : Runnable {
            override fun run() {
                if (PhoneCompanion.code != code) {
                    status.text = context.getString(R.string.pair_done)
                    status.setTextColor(ContextCompat.getColor(context, R.color.ink))
                    handler.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, CLOSE_AFTER_PAIRED_MS)
                } else {
                    handler.postDelayed(this, WATCH_MS)
                }
            }
        }

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnKeyListener { _, keyCode, event ->
            val close = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            if (close && event.action == KeyEvent.ACTION_UP) dialog.dismiss()
            close
        }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.show()
        root.requestFocus()
        if (url != null) handler.postDelayed(watch, WATCH_MS)
    }
}
