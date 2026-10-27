package com.thefactor1.player.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.thefactor1.player.R
import com.thefactor1.player.companion.QrCode
import com.thefactor1.player.hub.FoundHub
import com.thefactor1.player.hub.HubClient
import com.thefactor1.player.hub.HubDiscovery
import com.thefactor1.player.hub.HubUnauthorized
import com.thefactor1.player.hub.PairRequest
import com.thefactor1.player.hub.PairStatus
import com.thefactor1.player.ui.Fonts

/**
 * First run (EXPERIENCE.md, "Out of the box"): find the hub on the LAN, or have its address typed
 * when none answers; then show a pairing code (and a QR to the hub's phone setup page) and wait for
 * it to be approved. Nothing has to be typed on the TV unless discovery fails or the hub is headless.
 */
class PairingSetup(
    private val root: FrameLayout,
    private val fonts: Fonts,
    private val onPaired: (address: String, token: String) -> Unit,
    private val openSettings: () -> Unit,
) {
    private val context = root.context
    private val main = Handler(Looper.getMainLooper())
    private val discovery = HubDiscovery(context)
    /** Bumped on every step, so a late answer from an earlier one is dropped. */
    private var step = 0
    private var hub: FoundHub? = null
    private var request: PairRequest? = null
    private val found = mutableListOf<FoundHub>()
    private val poll = Runnable { checkApproval() }
    /** Why setup is showing again (the hub forgot this TV), kept up beside the code until it's approved. */
    private var note: Int? = null

    private lateinit var kicker: TextView
    private lateinit var title: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var code: TextView
    private lateinit var body: TextView
    private lateinit var hubs: LinearLayout
    private lateinit var address: EditText
    private lateinit var words: EditText
    private lateinit var status: TextView
    private lateinit var primary: TextView
    private lateinit var searchAgain: TextView
    private lateinit var settings: TextView
    private lateinit var wordsLink: TextView
    private lateinit var qr: View
    private lateinit var qrImage: ImageView

    /** Starts at [known] (a hub already paired once) or, when null, by looking for one. [note] explains why we're here. */
    fun show(known: FoundHub?, note: Int? = null) {
        if (root.childCount == 0) build()
        root.visibility = View.VISIBLE
        this.note = note
        if (known == null) discover() else requestCode(known)
        note?.let { status.setText(it) }
    }

    fun hide() {
        stop()
        root.visibility = View.GONE
    }

    /** Leaving the app: stop broadcasting and polling. */
    fun pause() = stop()

    /** Back in the app: a code may have expired meanwhile, so ask again (or look again). */
    fun resume() {
        if (root.visibility != View.VISIBLE) return
        hub?.let(::requestCode) ?: discover()
    }

    /** Back from the word entry returns to the code. False when Back should leave the setup. */
    fun back(): Boolean {
        if (words.visibility != View.VISIBLE) return false
        request?.let(::showCode) ?: hub?.let(::requestCode)
        return true
    }

    private fun stop() {
        step++
        discovery.stop()
        main.removeCallbacksAndMessages(null)
    }

    // ---- Steps ----------------------------------------------------------------------------------

    private fun discover() {
        val mine = reset()
        hub = null
        found.clear()
        title.setText(R.string.setup_finding)
        spinner.visibility = View.VISIBLE
        settings.requestFocus()
        discovery.start({ answer ->
            if (mine != step) return@start
            found += answer
            if (hubs.visibility == View.VISIBLE) {
                addHubButton(answer)
            } else if (found.size == 1) {
                // One more beacon round for any other hub to answer before deciding.
                main.postDelayed({ if (mine == step) decide() }, SETTLE_MS)
            }
        }) {
            if (mine == step && found.isEmpty()) typeAddress()
        }
    }

    private fun decide() {
        if (found.size == 1) {
            requestCode(found.single())
            return
        }
        title.setText(R.string.setup_choose)
        spinner.visibility = View.GONE
        hubs.visibility = View.VISIBLE
        searchAgain.visibility = View.VISIBLE
        found.forEach(::addHubButton)
        hubs.getChildAt(0)?.requestFocus()
    }

    private fun addHubButton(found: FoundHub) {
        hubs.addView(button(found.name) { requestCode(found) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Mercury.dp(root, 10f) })
    }

    private fun typeAddress() {
        reset()
        discovery.stop()
        title.setText(R.string.setup_type_title)
        body.setText(R.string.setup_type_body)
        body.visibility = View.VISIBLE
        address.visibility = View.VISIBLE
        showPrimary(R.string.setup_connect) { connectTyped() }
        searchAgain.visibility = View.VISIBLE
        address.requestFocus()
    }

    private fun connectTyped() {
        val typed = HubClient.normalize(address.text.toString())
        if (typed == null) {
            status.setText(R.string.setup_bad)
            return
        }
        val mine = step
        status.setText(R.string.setup_checking)
        HubClient(typed).run({ health() }) { result ->
            if (mine != step) return@run
            if (result.isSuccess) requestCode(FoundHub(typed.substringAfter("://"), typed))
            else status.text = context.getString(R.string.setup_failed, typed.substringAfter("://"))
        }
    }

    private fun requestCode(target: FoundHub) {
        val mine = reset()
        discovery.stop()
        hub = target
        request = null
        kicker.text = context.getString(R.string.setup_kicker_named, target.name.uppercase())
        title.setText(R.string.setup_pairing)
        spinner.visibility = View.VISIBLE
        settings.requestFocus()
        HubClient(target.address).run({ requestPairing(Build.MODEL.let { if (it.endsWith("TV", ignoreCase = true)) it else "$it TV" }) }) { result ->
            if (mine != step) return@run
            result.onSuccess(::showCode).onFailure {
                spinner.visibility = View.GONE
                status.text = context.getString(R.string.setup_failed, target.address.substringAfter("://"))
                showPrimary(R.string.setup_try_again) { requestCode(target) }
                searchAgain.visibility = View.VISIBLE
                primary.requestFocus()
            }
        }
    }

    private fun showCode(pairing: PairRequest) {
        reset()
        request = pairing
        kicker.text = context.getString(R.string.setup_kicker_named, hub?.name.orEmpty().uppercase())
        title.setText(R.string.setup_pairing)
        code.text = spaced(pairing.code)
        code.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        body.setText(if (pairing.setupUrl != null) R.string.setup_approve else R.string.setup_approve_only)
        body.visibility = View.VISIBLE
        pairing.setupUrl?.let { url ->
            qrImage.setImageBitmap(QrCode.bitmap(url, Mercury.dp(root, QR_DP)))
            qr.visibility = View.VISIBLE
        }
        wordsLink.visibility = View.VISIBLE
        note?.let { status.setText(it) }
        settings.requestFocus()
        main.postDelayed(poll, POLL_MS)
    }

    private fun checkApproval() {
        val target = hub ?: return
        val pairing = request ?: return
        val mine = step
        HubClient(target.address).run({ pairingStatus(pairing) }) { result ->
            if (mine != step) return@run
            when (val answer = result.getOrNull()) {
                is PairStatus.Paired -> {
                    stop()
                    onPaired(target.address, answer.token)
                }
                PairStatus.Expired -> expired(target)
                PairStatus.Waiting -> {
                    if (note == null) status.text = null else status.setText(note!!)
                    main.postDelayed(poll, POLL_MS)
                }
                // Unreachable for a moment (Wi-Fi blip, hub restarting): keep the code up and keep asking.
                null -> {
                    status.setText(R.string.setup_still_trying)
                    main.postDelayed(poll, POLL_MS)
                }
            }
        }
    }

    private fun expired(target: FoundHub) {
        reset()
        kicker.text = context.getString(R.string.setup_kicker_named, target.name.uppercase())
        title.setText(R.string.setup_expired)
        body.setText(R.string.setup_expired_body)
        body.visibility = View.VISIBLE
        showPrimary(R.string.setup_try_again) { requestCode(target) }
        primary.requestFocus()
    }

    /** For a hub with no screen or phone page: its six words, typed once. Approval polling carries on meanwhile. */
    private fun enterWords() {
        val target = hub ?: return
        words.visibility = View.VISIBLE
        wordsLink.visibility = View.GONE
        body.setText(R.string.setup_words_body)
        showPrimary(R.string.setup_pair) {
            val typed = words.text.toString()
            if (typed.isBlank()) return@showPrimary
            val mine = step
            status.setText(R.string.setup_checking)
            HubClient(target.address).run({ pair(typed) }) { result ->
                if (mine != step) return@run
                result.onSuccess { token ->
                    stop()
                    onPaired(target.address, token)
                }.onFailure { error ->
                    status.text = if (error is HubUnauthorized) context.getString(R.string.setup_unpaired)
                    else context.getString(R.string.setup_failed, target.address.substringAfter("://"))
                }
            }
        }
        words.requestFocus()
    }

    /** Clears the screen back to its frame for the next step; returns that step's ticket. */
    private fun reset(): Int {
        main.removeCallbacksAndMessages(null)
        kicker.setText(R.string.setup_kicker)
        listOf(spinner, code, body, hubs, address, words, primary, searchAgain, wordsLink, qr).forEach { it.visibility = View.GONE }
        hubs.removeAllViews()
        status.text = null
        return ++step
    }

    private fun showPrimary(label: Int, click: () -> Unit) {
        primary.setText(label)
        primary.setOnClickListener { click() }
        primary.visibility = View.VISIBLE
    }

    // ---- Views ----------------------------------------------------------------------------------

    private fun build() {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Mercury.dp(root, 56f), Mercury.dp(root, Mercury.SAFE_Y), Mercury.dp(root, 56f), Mercury.dp(root, Mercury.SAFE_Y))
        }
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        row.addView(column, LinearLayout.LayoutParams(Mercury.dp(root, 520f), ViewGroup.LayoutParams.WRAP_CONTENT))

        kicker = Mercury.text(context, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.22f)
        column.addView(kicker)
        val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        title = Mercury.title(context, fonts, 36f)
        titleRow.addView(title)
        // A quiet spinner: small, the muted colour, beside the words rather than in the middle of the screen.
        spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(context.getColor(R.color.mercury_muted))
        }
        titleRow.addView(spinner, LinearLayout.LayoutParams(Mercury.dp(root, 20f), Mercury.dp(root, 20f)).apply { marginStart = Mercury.dp(root, 16f) })
        column.addView(titleRow, margins(top = 8f))
        code = Mercury.title(context, fonts, 72f, mercuryTracking = 0.04f)
        column.addView(code, margins(top = 6f))
        body = Mercury.text(context, fonts.ui, 15f, R.color.mercury_muted).apply {
            maxLines = 4
            setLineSpacing(0f, 1.3f)
        }
        column.addView(body, margins(top = 12f))
        hubs = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(hubs, margins(top = 18f))
        address = field(R.string.hub_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(address, margins(top = 18f, height = 52f))
        words = field(R.string.setup_code, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        column.addView(words, margins(top = 18f, height = 52f))
        status = Mercury.text(context, fonts.ui, 13f, R.color.mercury_amber).apply { maxLines = 3 }
        column.addView(status, margins(top = 8f))

        val buttons = LinearLayout(context)
        column.addView(buttons, margins(top = 14f))
        primary = button(context.getString(R.string.setup_connect)) {}
        buttons.addView(primary, wrap(end = 12f))
        searchAgain = button(context.getString(R.string.setup_search_again)) { discover() }
        buttons.addView(searchAgain, wrap(end = 12f))
        settings = button(context.getString(R.string.home_settings)) { openSettings() }
        buttons.addView(settings)
        Mercury.liftRoom(buttons, levels = 2)

        wordsLink = Mercury.text(context, fonts.mercuryMono, 11f, tracking = 0.2f).apply {
            setText(R.string.setup_words_link)
            alpha = Mercury.CONTROL_REST
            isFocusable = true
            isClickable = true
            setPadding(0, Mercury.dp(root, 6f), 0, Mercury.dp(root, 6f))
            setOnClickListener { enterWords() }
            setOnFocusChangeListener { v, has ->
                v.alpha = if (has) 1f else Mercury.CONTROL_REST
                (v as TextView).paintFlags = if (has) v.paintFlags or Paint.UNDERLINE_TEXT_FLAG else v.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
        column.addView(wordsLink, margins(top = 18f))

        // The QR sits on a white tile: phone cameras need the quiet zone light.
        val qrColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        qrImage = ImageView(context).apply {
            val pad = Mercury.dp(root, 10f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = Mercury.dp(root, 10f).toFloat()
                setColor(Color.WHITE)
            }
            contentDescription = context.getString(R.string.setup_scan)
        }
        val side = Mercury.dp(root, QR_DP + 20f)
        qrColumn.addView(qrImage, LinearLayout.LayoutParams(side, side))
        qrColumn.addView(Mercury.text(context, fonts.mercuryMono, 11f, R.color.mercury_muted, 0.2f).apply { setText(R.string.setup_scan) }, wrap(top = 12f))
        qr = qrColumn
        row.addView(qrColumn, wrap().apply { marginStart = Mercury.dp(root, 64f) })
    }

    private fun field(hint: Int, type: Int) = EditText(context).apply {
        setHint(hint)
        inputType = type
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        typeface = fonts.ui
        textSize = 20f
        setTextColor(context.getColor(R.color.mercury_fg))
        setHintTextColor(context.getColor(R.color.ink_faint))
        backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.mercury_muted))
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE && primary.visibility == View.VISIBLE) primary.performClick()
            false
        }
    }

    private fun button(label: String, click: () -> Unit) = TextView(context, null, 0, R.style.ControlButton).apply {
        text = label
        typeface = fonts.ui
        textSize = 15f
        height = Mercury.dp(root, 44f)
        setOnClickListener { click() }
    }

    private fun margins(top: Float = 0f, height: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height > 0) Mercury.dp(root, height) else ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Mercury.dp(root, top) }

    private fun wrap(top: Float = 0f, end: Float = 0f) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Mercury.dp(root, top)
            marginEnd = Mercury.dp(root, end)
        }

    companion object {
        private const val POLL_MS = 2_000L
        private const val SETTLE_MS = 2_500L
        private const val QR_DP = 156f

        /** "482913" reads as "482 913"; other codes are shown as they come. */
        fun spaced(code: String): String = if (code.length == 6 && code.all { it.isDigit() }) "${code.take(3)} ${code.drop(3)}" else code
    }
}
