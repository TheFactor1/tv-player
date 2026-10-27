package com.thefactor1.player.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Pairing by pointing the camera at the TV. Every TV Player on the Wi-Fi is asked to lay its
 * invisible code over the picture; the camera, with a short fixed shutter, finds the picture
 * wherever it is in view and reads whichever code it is looking at, and answering with that code
 * lets this phone in. The code starts faint and grows a little at a time only while it isn't being
 * read, so it stays as close to invisible as the room allows.
 */
class ScannerActivity : Activity() {
    private val store by lazy { TvStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    /** Finding and reading the code, kept off the camera's thread so frames never wait on it. */
    private lateinit var readingThread: HandlerThread
    private lateinit var readingHandler: Handler
    /** Frames waiting to be read; on a slow phone a few are let go rather than falling behind. */
    private val waiting = AtomicInteger()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var size = Size(1280, 720)
    private var flipped = false
    private var isoRange = 100 to 3200
    @Volatile private var iso = 0
    @Volatile private var frameNs = CameraTiming.frameNs(60f)
    private var exposureChanges = 0
    private var sinceExposure = 0

    private lateinit var preview: TextureView
    private lateinit var guide: GuideView
    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var progressTrack: View

    private var locator = BeaconLocator()
    private var finder: TvFinder? = null
    /** Every TV asked to show its code, by address. */
    private val tvs = ConcurrentHashMap<String, FoundTv>()
    @Volatile private var strength = START_STRENGTH
    @Volatile private var manual = false
    @Volatile private var resetPending = true
    @Volatile private var answering = false
    @Volatile private var done = false
    @Volatile private var bestSure = 0f
    private var startedAt = 0L
    private var autoExposureNs = 0L
    private var autoIso = 0
    private var autoFrames = 0
    private var tvAnswered = false
    private var foundOnce = false
    private var statusKey = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }
        start()
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != CAMERA_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else say(R.string.scan_no_camera)
    }

    private fun start() {
        if (done || (::cameraThread.isInitialized && cameraThread.isAlive)) return
        cameraThread = HandlerThread("scanner").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        readingThread = HandlerThread("scanner-reading").apply { start() }
        readingHandler = Handler(readingThread.looper)
        startedAt = SystemClock.elapsedRealtime()
        strength = START_STRENGTH
        bestSure = 0f
        tvAnswered = false
        foundOnce = false
        resetPending = true
        exposureChanges = 0
        say(R.string.scan_aim)
        guide.show(null, 0f)
        findTvs()
        if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = textureListener
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        finder?.stop()
        finder = null
        if (!::cameraThread.isInitialized) return
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        cameraThread.quitSafely()
        readingThread.quitSafely()
        waiting.set(0)
        manual = false
        autoFrames = 0
        // Take the code off every TV straight away rather than letting it run out.
        val asked = tvs.values.toList()
        Thread { asked.forEach { tv -> runCatching { TvApi(tv.host, tv.port, null).beacon(strength, 0) } } }.start()
        tvs.clear()
    }

    // ---- The TVs ---------------------------------------------------------------------------------

    /** The TV this phone knew, if any, and whatever else answers on the Wi-Fi. */
    private fun findTvs() {
        val known = store.host
        if (known != null) addTv(FoundTv(store.name ?: MainActivity.TV_NAME, known, store.port))
        finder = TvFinder(this) { tv -> addTv(tv) }.also { it.start() }
        main.postDelayed({ if (tvs.isEmpty() || !tvAnswered) say(R.string.scan_no_tv) }, NO_TV_MS)
        main.postDelayed({ if (tvAnswered && !foundOnce && !done) say(R.string.scan_hint) }, HINT_MS)
        main.postDelayed(ramp, RAMP_MS)
    }

    private fun addTv(tv: FoundTv) {
        if (tvs.putIfAbsent(tv.host, tv) != null) return
        ask(tv)
    }

    /** Asks one TV to show the code (again, to keep it going, or stronger). */
    private fun ask(tv: FoundTv) {
        val asked = strength
        Thread {
            val answer = runCatching { TvApi(tv.host, tv.port, null).beacon(asked, BEACON_SECONDS) }.getOrNull()
            main.post {
                if (done || isFinishing) return@post
                when (answer?.first) {
                    200 -> {
                        answer?.second?.optDouble("refresh", 0.0)?.takeIf { it > 0 }?.let { matchRefresh(it.toFloat()) }
                        if (!tvAnswered) {
                            tvAnswered = true
                            say(R.string.scan_aim)
                        }
                    }
                    409 -> if (!tvAnswered) status.text = getString(R.string.scan_refresh, answer?.second?.optDouble("refresh", 0.0) ?: 0.0)
                    503 -> if (!tvAnswered) say(R.string.scan_open_tv)
                    429 -> if (!tvAnswered) say(R.string.scan_wait)
                }
            }
        }.start()
    }

    /** The camera's frame rate follows the TV's refresh, so its frames see both halves of the flicker. */
    private fun matchRefresh(refreshHz: Float) {
        val next = CameraTiming.frameNs(refreshHz)
        if (next == frameNs || !::cameraHandler.isInitialized) return
        frameNs = next
        resetPending = true
        cameraHandler.post { if (manual) repeatManual() }
    }

    /**
     * Every few seconds: renew the code, and if it isn't being read yet, make it a shade stronger.
     * It never goes past the point where it could start to show.
     */
    private val ramp = object : Runnable {
        override fun run() {
            if (done) return
            if (bestSure < RAMP_UNTIL_SURE && strength < MAX_STRENGTH) strength++
            bestSure = 0f
            tvs.values.forEach(::ask)
            main.postDelayed(this, RAMP_MS)
        }
    }

    /** The camera read a code: try it with each TV; the one showing it lets this phone in. */
    private fun answer(code: Int) {
        if (answering || done) return
        answering = true
        val device = DeviceName.of(this)
        val candidates = tvs.values.toList()
        Thread {
            val match = candidates.firstNotNullOfOrNull { tv ->
                runCatching { TvApi(tv.host, tv.port, null).beaconPair(code, device) }.getOrNull()?.let { tv to it }
            }
            main.post {
                answering = false
                if (isFinishing) return@post
                if (match == null) {
                    // A misread: keep looking with a clean slate.
                    resetPending = true
                    say(R.string.scan_steady)
                    return@post
                }
                paired(match.first, match.second)
            }
        }.start()
    }

    private fun paired(tv: FoundTv, token: String) {
        done = true
        guide.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        store.host = tv.host
        store.port = tv.port
        store.name = tv.name
        store.token = token
        guide.confirm()
        setProgress(1f)
        say(R.string.scan_connected)
        NowPlayingService.start(this)
        main.postDelayed({
            if (isFinishing) return@postDelayed
            startActivity(Intent(this, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, CONNECTED_PAUSE_MS)
    }

    // ---- The camera ------------------------------------------------------------------------------

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitPreview()
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null || !::cameraHandler.isInitialized || !cameraThread.isAlive) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return say(R.string.scan_no_camera)
        val traits = manager.getCameraCharacteristics(id)
        val capabilities = traits.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) return say(R.string.scan_no_manual)
        val map = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        size = map.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .minByOrNull { abs(it.width - 1280) } ?: Size(1280, 720)
        // Most back cameras sit at 90 degrees, which is upright in landscape; the odd one at 270 is upside down.
        flipped = traits.get(CameraCharacteristics.SENSOR_ORIENTATION) == 270
        locator = BeaconLocator(flipped)
        isoRange = traits.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower to it.upper } ?: (100 to 3200)
        fitPreview()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                startSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
                main.post { say(R.string.scan_no_camera) }
            }
        }, cameraHandler)
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val shown = Surface(texture)
        val frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        frames.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                if (manual && !done) {
                    if (waiting.get() < MAX_WAITING) {
                        val plane = image.planes[0]
                        val blocks = Blocks.of(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                        waiting.incrementAndGet()
                        readingHandler.post {
                            waiting.decrementAndGet()
                            analyse(blocks)
                        }
                    }
                }
            } finally {
                image.close()
            }
        }, cameraHandler)
        reader = frames
        previewSurface = shown
        device.createCaptureSession(listOf(shown, frames.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                // A moment on automatic first, to learn how bright the picture is.
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(shown)
                    addTarget(frames.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                configured.setRepeatingRequest(request.build(), captureCallback, cameraHandler)
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                main.post { say(R.string.scan_no_camera) }
            }
        }, cameraHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (manual) return
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { autoExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { autoIso = it }
            if (++autoFrames >= AUTO_FRAMES && autoExposureNs > 0 && autoIso > 0) {
                iso = (autoIso.toDouble() * autoExposureNs / SHUTTER_NS).toInt().coerceIn(isoRange.first, isoRange.second)
                manual = true
                resetPending = true
                repeatManual()
            }
        }
    }

    /**
     * The fixed settings the code needs: a shutter short enough to catch single screen refreshes, the
     * same brightness as automatic by raising the gain (then nudged so the picture is neither burnt
     * out nor murky), and a frame rate that doesn't divide evenly into the TV's refresh, so the camera
     * lands on both halves of the flicker.
     */
    private fun repeatManual() {
        val device = camera ?: return
        val current = session ?: return
        val shown = previewSurface ?: return
        val frames = reader ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(shown)
            addTarget(frames.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, SHUTTER_NS)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        try {
            current.setRepeatingRequest(request.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            main.post { say(R.string.scan_no_camera) }
        }
    }

    // ---- Reading ---------------------------------------------------------------------------------

    /** One frame's blocks: keep the exposure right, look for the picture, and read the code once found. */
    private fun analyse(blocks: FloatArray) {
        if (done) return
        if (resetPending) {
            resetPending = false
            locator.reset()
            sinceExposure = 0
        }
        if (adjustExposure(blocks)) return
        val result = locator.add(blocks) ?: return
        val sure = (result.reading.confidence / BeaconDecoder.ACCEPT).coerceIn(0f, 1f)
        val seen = if (result.found) sure else 0f
        if (seen > bestSure) bestSure = seen
        val quad = result.quad.takeIf { result.found }
        main.post {
            if (done) return@post
            guide.show(quad, seen, flipped)
            setProgress(seen)
            if (answering) return@post
            when {
                quad == null -> Unit
                seen > SHOW_READING_AT -> say(R.string.scan_reading)
                quad.width < SMALL_PICTURE -> say(R.string.scan_closer)
                else -> say(R.string.scan_found)
            }
            if (quad != null) foundOnce = true
        }
        if (result.ready) result.reading.code?.let { code -> main.post { answer(code) } }
    }

    /**
     * A few small corrections in the first seconds: a picture burnt out to white, or lost in the
     * dark, shows the flicker poorly. Each change starts the reading over, so they stop early.
     */
    private fun adjustExposure(blocks: FloatArray): Boolean {
        if (exposureChanges >= MAX_EXPOSURE_CHANGES || ++sinceExposure < EXPOSURE_SETTLE) return false
        val quad = locator.quad?.takeIf { locator.found }
        val level = Blocks.level(blocks, quad, if (quad != null) 0.9f else 0.98f)
        val factor = when {
            level > TOO_BRIGHT -> 0.7
            level < TOO_DARK -> 1.5
            else -> return false
        }
        val next = (iso * factor).toInt().coerceIn(isoRange.first, isoRange.second)
        if (next == iso) return false
        iso = next
        exposureChanges++
        sinceExposure = 0
        locator.reset()
        cameraHandler.post { if (manual) repeatManual() }
        return true
    }

    // ---- Screen ----------------------------------------------------------------------------------

    private fun say(text: Int) {
        if (statusKey == text) return
        statusKey = text
        status.animate().cancel()
        status.animate().alpha(0f).setDuration(SWAP_MS).withEndAction {
            status.setText(text)
            status.animate().alpha(1f).setDuration(SWAP_MS).setInterpolator(easeOut).start()
        }.start()
        progressTrack.animate().alpha(if (text == R.string.scan_reading) 1f else 0f).setDuration(SWAP_MS * 2).start()
    }

    private fun setProgress(fraction: Float) {
        progress.pivotX = 0f
        progress.animate().scaleX(fraction.coerceIn(0.02f, 1f)).setDuration(PROGRESS_MS).setInterpolator(easeOut).start()
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.ground)) }
        preview = TextureView(this)
        guide = GuideView(this)
        status = text(16f, R.color.ink, bold())
        progress = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(getColor(R.color.ink)) }
            scaleX = 0.02f
        }
        progressTrack = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(getColor(R.color.hairline)) }
            addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            alpha = 0f
        }
        val other = text(14f, R.color.ink_secondary, medium()).apply {
            setText(R.string.scan_other_ways)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@ScannerActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAIR_AGAIN, true))
                finish()
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(12), dp(14))
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(progressTrack, LinearLayout.LayoutParams(dp(120), dp(3)).apply { marginEnd = dp(12) })
            addView(other)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE60B0C0E.toInt(), 0x000B0C0E))
        }
        root.addView(preview, FrameLayout.LayoutParams(0, 0))
        root.addView(guide, FrameLayout.LayoutParams(0, 0))
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
    }

    /** The preview at the camera's 16:9, as large as fits, with the guide laid over it. */
    private fun fitPreview() {
        val parent = preview.parent as? View ?: return
        if (parent.width == 0) return
        val width = minOf(parent.width, parent.height * size.width / size.height)
        val height = width * size.height / size.width
        for (view in listOf(preview, guide)) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
                params.width = width
                params.height = height
                params.gravity = Gravity.CENTER
                view.layoutParams = params
            }
        }
        // The camera's picture comes sideways from the sensor; turn it to match the landscape screen.
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val bufferRect = RectF(0f, 0f, size.height.toFloat(), size.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(height.toFloat() / size.height, width.toFloat() / size.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        preview.setTransform(matrix)
    }

    private fun text(sp: Float, color: Int, face: Typeface) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
        typeface = face
    }

    private fun bold() = resources.getFont(R.font.manrope_bold)
    private fun medium() = resources.getFont(R.font.manrope_medium)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * Four corners over the preview. Until the TV is found they mark a loose frame to aim with; then
     * they glide to the picture's own corners, wherever it sits, and firm up as the reading does.
     */
    private class GuideView(context: Context) : View(context) {
        private val target = AIM.copyOf()
        private val shown = AIM.copyOf()
        private var targetSure = 0f
        private var shownSure = 0f
        private var found = false
        private var flipped = false
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.ink)
        }

        /** [quad] is in the camera's frame; null goes back to the aiming frame. */
        fun show(quad: Quad?, sure: Float, flipped: Boolean = this.flipped) {
            this.flipped = flipped
            found = quad != null
            if (quad == null) AIM.copyInto(target) else for (i in 0 until 8) {
                target[i] = if (flipped) 1 - quad.corners[i] else quad.corners[i]
            }
            targetSure = sure.coerceIn(0f, 1f)
            postInvalidateOnAnimation()
        }

        fun confirm() {
            targetSure = 1f
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            // Eases toward the latest reading a little each frame, so the corners glide rather than jump.
            var moving = false
            for (i in 0 until 8) {
                shown[i] += (target[i] - shown[i]) * EASE
                if (abs(target[i] - shown[i]) > 0.0005f) moving = true else shown[i] = target[i]
            }
            shownSure += (targetSure - shownSure) * EASE
            if (abs(targetSure - shownSure) > 0.002f) moving = true else shownSure = targetSure
            if (moving) postInvalidateOnAnimation()
            val firm = if (found) 0.35f + 0.65f * shownSure else 0f
            corner.alpha = (110 + 145 * firm).toInt()
            for (c in 0 until 4) {
                val x = shown[c * 2] * width
                val y = shown[c * 2 + 1] * height
                for (n in NEIGHBOURS[c]) {
                    val dx = shown[n * 2] * width - x
                    val dy = shown[n * 2 + 1] * height - y
                    val arm = 0.06f + 0.1f * firm
                    canvas.drawLine(x, y, x + dx * arm, y + dy * arm, corner)
                }
            }
        }

        private companion object {
            const val EASE = 0.16f
            /** The aiming frame: top left, top right, bottom left, bottom right. */
            val AIM = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f)
            /** Each corner's two neighbours along the picture's edges. */
            val NEIGHBOURS = arrayOf(intArrayOf(1, 2), intArrayOf(0, 3), intArrayOf(3, 0), intArrayOf(2, 1))
        }
    }

    private companion object {
        const val CAMERA_REQUEST = 7
        const val START_STRENGTH = 3
        const val MAX_STRENGTH = 10
        const val RAMP_MS = 4_000L
        /** Below this much sureness in a ramp step, the code is made a shade stronger. */
        const val RAMP_UNTIL_SURE = 0.5f
        const val SHOW_READING_AT = 0.3f
        const val BEACON_SECONDS = 20
        const val NO_TV_MS = 8_000L
        const val CONNECTED_PAUSE_MS = 900L
        const val AUTO_FRAMES = 20
        const val SHUTTER_NS = 8_000_000L
        /** A picture less than this share of the view's width reads better from closer. */
        const val SMALL_PICTURE = 0.33f
        const val HINT_MS = 12_000L
        const val MAX_EXPOSURE_CHANGES = 4
        const val MAX_WAITING = 12
        /** Frames for a new exposure to come through the camera and settle. */
        const val EXPOSURE_SETTLE = 8
        const val TOO_BRIGHT = 240f
        const val TOO_DARK = 45f
        const val SWAP_MS = 140L
        const val PROGRESS_MS = 260L
        val easeOut = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
