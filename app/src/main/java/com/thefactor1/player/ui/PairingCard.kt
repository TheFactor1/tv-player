package com.thefactor1.player.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.thefactor1.player.R
import com.thefactor1.player.companion.PhoneCompanion
import com.thefactor1.player.companion.QrCode

/**
 * Scan-to-pair: a small card in the corner, over the film rather than instead of it, with a QR code
 * that opens the phone page with the code filled in, plus the address and code to type by hand. The
 * code changes once a phone pairs, so the card notices, says so, and slips away.
 */
object PairingCard {
    private const val QR_DP = 132
    private const val QUIET_DP = 8
    private const val EDGE_DP = 48
    private const val RISE_DP = 14f
    private const val IN_MS = 320L
    private const val OUT_MS = 220L
    private const val WATCH_MS = 1_000L
    private const val CLOSE_AFTER_PAIRED_MS = 1_600L
    private const val ASK_TIMEOUT_MS = 60_000L

    // Material's emphasized curves: a soft landing on the way in, an easy start on the way out.
    private val easeOut = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val easeIn = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /**
     * Only a press that starts while the card is up counts. The card can appear while OK is held
     * (to pause, say), and that press's release must not answer it: above all, not let a phone in.
     */
    private class KeyPresses {
        private var down = KeyEvent.KEYCODE_UNKNOWN

        fun completes(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) down = event.keyCode
                return false
            }
            if (event.action != KeyEvent.ACTION_UP || event.keyCode != down) return false
            down = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
    }

    fun show(context: Context, fonts: Fonts) {
        val url = PhoneCompanion.pairUrl()
        val address = PhoneCompanion.address()
        val code = PhoneCompanion.code
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun text(sp: Float, color: Int, typeface: Typeface, value: String) = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(ContextCompat.getColor(context, color))
            this.typeface = typeface
        }

        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val handler = Handler(Looper.getMainLooper())
        val status = text(12f, R.color.ink_faint, fonts.medium, context.getString(R.string.pair_close))

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(18f, R.color.ink, fonts.bold, context.getString(if (url != null) R.string.pair_title else R.string.companion_title)))
            addView(text(13f, R.color.ink_secondary, fonts.medium,
                context.getString(if (url != null) R.string.pair_scan else R.string.companion_offline)).apply {
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(0f, 1.2f)
                maxWidth = dp(230)
            })
            if (address != null) {
                addView(text(12f, R.color.ink_dim, fonts.medium, context.getString(R.string.pair_manual, address)).apply {
                    setPadding(0, dp(14), 0, 0)
                })
                addView(text(26f, R.color.ink, fonts.mono, code).apply { letterSpacing = 0.16f })
            }
            addView(status.apply { setPadding(0, dp(10), 0, 0) })
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(24), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(context, R.color.panel_ground))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.hairline))
            }
            if (url != null) {
                val qr = ImageView(context).apply {
                    setImageBitmap(QrCode.bitmap(url, dp(QR_DP)))
                    setPadding(dp(QUIET_DP), dp(QUIET_DP), dp(QUIET_DP), dp(QUIET_DP))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.WHITE)
                    }
                    contentDescription = context.getString(R.string.pair_title)
                }
                val side = dp(QR_DP + QUIET_DP * 2)
                addView(qr, LinearLayout.LayoutParams(side, side).apply { marginEnd = dp(20) })
            }
            addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            alpha = 0f
            translationY = RISE_DP * density
        }

        // No veil: the film keeps playing and stays in view around the card.
        val root = FrameLayout(context).apply {
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP))
            })
            isFocusable = true
        }

        var closing = false
        fun close() {
            if (closing) return
            closing = true
            handler.removeCallbacksAndMessages(null)
            card.animate().alpha(0f).translationY(RISE_DP * density / 2).setDuration(OUT_MS).setInterpolator(easeIn)
                .withEndAction { if (dialog.isShowing) dialog.dismiss() }.start()
        }

        // A new code means a phone just paired with the one on screen.
        val watch = object : Runnable {
            override fun run() {
                if (PhoneCompanion.code != code) {
                    status.text = context.getString(R.string.pair_done)
                    status.setTextColor(ContextCompat.getColor(context, R.color.ink))
                    handler.postDelayed({ close() }, CLOSE_AFTER_PAIRED_MS)
                } else {
                    handler.postDelayed(this, WATCH_MS)
                }
            }
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
        }
        val keys = KeyPresses()
        dialog.setOnKeyListener { _, keyCode, event ->
            val handled = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            if (handled && keys.completes(event)) close()
            handled
        }
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null) }
        dialog.show()
        root.requestFocus()
        card.animate().alpha(1f).translationY(0f).setDuration(IN_MS).setInterpolator(easeOut).start()
        if (url != null) handler.postDelayed(watch, WATCH_MS)
    }

    /**
     * The phone app asking to connect: the same corner card, with the phone's name. OK lets it in,
     * Back turns it away, and an unanswered card bows out after a minute.
     */
    fun ask(context: Context, fonts: Fonts, device: String, answer: (Boolean) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        val handler = Handler(Looper.getMainLooper())

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(26), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(context, R.color.panel_ground))
                setStroke(dp(1), ContextCompat.getColor(context, R.color.hairline))
            }
            addView(TextView(context).apply {
                text = context.getString(R.string.pair_ask_title, device)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                typeface = fonts.bold
                maxWidth = dp(360)
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.pair_ask_detail)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(context, R.color.ink_secondary))
                typeface = fonts.medium
                setPadding(0, dp(4), 0, 0)
            })
            alpha = 0f
            translationY = RISE_DP * density
        }
        val root = FrameLayout(context).apply {
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP))
            })
            isFocusable = true
        }

        var answered = false
        fun close(approved: Boolean) {
            if (answered) return
            answered = true
            answer(approved)
            handler.removeCallbacksAndMessages(null)
            card.animate().alpha(0f).translationY(RISE_DP * density / 2).setDuration(OUT_MS).setInterpolator(easeIn)
                .withEndAction { if (dialog.isShowing) dialog.dismiss() }.start()
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
        }
        val keys = KeyPresses()
        dialog.setOnKeyListener { _, keyCode, event ->
            val allow = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            val handled = allow || keyCode == KeyEvent.KEYCODE_BACK
            if (handled && keys.completes(event)) close(approved = allow)
            handled
        }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (!answered) {
                answered = true
                answer(false)
            }
        }
        dialog.show()
        root.requestFocus()
        card.animate().alpha(1f).translationY(0f).setDuration(IN_MS).setInterpolator(easeOut).start()
        handler.postDelayed({ close(approved = false) }, ASK_TIMEOUT_MS)
    }
}
