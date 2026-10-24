package com.thefactor1.player.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A prototype of pairing by camera: asks the TV to put its invisible code over the picture, then
 * reads it through the camera with a short, fixed shutter. It shows how sure it is as it goes, a
 * magnified view of what the camera sees flickering, and buttons to make the code fainter or
 * stronger, so the point where it's both unreadable to the eye and readable to the phone can be
 * found on a real screen. Reached from the app icon's long-press menu.
 */
class ScannerActivity : Activity() {
    private val store by lazy { TvStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var size = Size(1280, 720)
    private var flipped = false

    private lateinit var preview: TextureView
    private lateinit var guide: GuideView
    private lateinit var magnified: ImageView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var strengthButton: TextView
    private lateinit var shutterButton: TextView

    private val decoder = BeaconDecoder()
    @Volatile private var strength = START_STRENGTH
    @Volatile private var shutterIndex = 1
    @Volatile private var manual = false
    @Volatile private var readSince = 0L
    @Volatile private var resetPending = true
    @Volatile private var checking = false
    @Volatile private var lastRead: Int? = null
    private var autoExposureNs = 0L
    private var autoIso = 0
    private var autoFrames = 0
    private var lastTimestamp = 0L
    @Volatile private var cameraFps = 0f
    @Volatile private var tvRefresh = 0f
    private var reads = 0
    private var previous: FloatArray? = null
    private val pixels = IntArray(THUMB_W * THUMB_H)
    private val thumb = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        if (!store.paired) status.setText(R.string.scan_not_paired)
    }

    override fun onResume() {
        super.onResume()
        if (!store.paired) return
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
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else status.setText(R.string.scan_no_camera)
    }

    private fun start() {
        if (::cameraThread.isInitialized && cameraThread.isAlive) return
        cameraThread = HandlerThread("scanner").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        restartReading()
        askTv()
        if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = textureListener
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        if (!::cameraThread.isInitialized) return
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        cameraThread.quitSafely()
        manual = false
        autoFrames = 0
        // Take the code off the TV straight away rather than letting it run out.
        Thread { runCatching { store.api()?.beacon(strength, 0) } }.start()
    }

    // ---- The TV ----------------------------------------------------------------------------------

    /** Asks the TV to show the code, and again every so often while the scanner is open. */
    private fun askTv() {
        val asked = strength
        Thread {
            val answer = runCatching { store.api()?.beacon(asked, BEACON_SECONDS) }.getOrNull()
            main.post {
                if (isFinishing) return@post
                val (code, body) = answer ?: (0 to null)
                when (code) {
                    200 -> {
                        tvRefresh = body?.optDouble("refresh", 0.0)?.toFloat() ?: 0f
                        if (!manual) status.setText(R.string.scan_aim)
                    }
                    409 -> status.text = getString(R.string.scan_refresh, body?.optDouble("refresh", 0.0) ?: 0.0)
                    503 -> status.setText(R.string.scan_open_tv)
                    401 -> status.setText(R.string.scan_not_paired)
                    else -> status.setText(R.string.scan_no_tv)
                }
            }
        }.start()
        main.removeCallbacks(renew)
        main.postDelayed(renew, RENEW_MS)
    }

    private val renew = Runnable { askTv() }

    private fun changeStrength(step: Int) {
        strength = (strength + step).coerceIn(1, MAX_STRENGTH)
        strengthButton.text = getString(R.string.scan_strength, strength)
        restartReading()
        askTv()
    }

    private fun changeShutter() {
        shutterIndex = (shutterIndex + 1) % SHUTTERS_NS.size
        shutterButton.text = shutterLabel()
        restartReading()
        if (manual) cameraHandler.post { repeatManual() }
    }

    private fun shutterLabel() = getString(R.string.scan_shutter, (1e9 / SHUTTERS_NS[shutterIndex]).toInt())

    /** Starts the reading afresh; the camera thread clears what it had at its next frame. */
    private fun restartReading() {
        resetPending = true
        readSince = SystemClock.elapsedRealtime()
        lastRead = null
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
        } ?: return status.setText(R.string.scan_no_camera)
        val traits = manager.getCameraCharacteristics(id)
        val capabilities = traits.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) {
            return status.setText(R.string.scan_no_manual)
        }
        val map = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        size = map.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .minByOrNull { abs(it.width - 1280) } ?: Size(1280, 720)
        // Most back cameras sit at 90 degrees, which is upright in landscape; the odd one at 270 is upside down.
        flipped = traits.get(CameraCharacteristics.SENSOR_ORIENTATION) == 270
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
                main.post { status.setText(R.string.scan_no_camera) }
            }
        }, cameraHandler)
    }

    private var isoRange = 100 to 3200

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val shown = Surface(texture)
        val frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        frames.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                if (manual) analyse(image.planes[0].buffer, image.planes[0].rowStride, image.planes[0].pixelStride)
            } finally {
                image.close()
            }
        }, cameraHandler)
        reader = frames
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
                main.post { status.setText(R.string.scan_no_camera) }
            }
        }, cameraHandler)
        previewSurface = shown
    }

    private var previewSurface: Surface? = null

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            if (lastTimestamp > 0 && timestamp > lastTimestamp) {
                val fps = 1e9f / (timestamp - lastTimestamp)
                cameraFps = if (cameraFps == 0f) fps else cameraFps * 0.9f + fps * 0.1f
            }
            lastTimestamp = timestamp
            if (manual) return
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { autoExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { autoIso = it }
            if (++autoFrames >= AUTO_FRAMES && autoExposureNs > 0 && autoIso > 0) {
                manual = true
                restartReading()
                repeatManual()
            }
        }
    }

    /**
     * The fixed settings the code needs: a shutter short enough to catch single screen refreshes, the
     * same brightness as automatic by raising the gain, and 25 frames a second, which doesn't divide
     * evenly into the TV's 60, so the camera lands on both halves of the flicker.
     */
    private fun repeatManual() {
        val device = camera ?: return
        val current = session ?: return
        val shown = previewSurface ?: return
        val frames = reader ?: return
        val shutter = SHUTTERS_NS[shutterIndex]
        val iso = (autoIso.toDouble() * autoExposureNs / shutter).toInt().coerceIn(isoRange.first, isoRange.second)
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(shown)
            addTarget(frames.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_NS)
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        try {
            current.setRepeatingRequest(request.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            main.post { status.setText(R.string.scan_no_camera) }
        }
    }

    // ---- Reading ---------------------------------------------------------------------------------

    /**
     * Averages the picture inside the guide into a small grid of blocks (the magnified view), and the
     * blocks into the code's 36 cells, keeping to the middle of each cell so a slightly off aim still reads.
     */
    private fun analyse(luma: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int) {
        if (resetPending) {
            resetPending = false
            decoder.reset()
            previous = null
            lastCells = null
        }
        val left = (size.width * GUIDE_INSET).toInt()
        val top = (size.height * GUIDE_INSET).toInt()
        val guideWidth = size.width - 2 * left
        val guideHeight = size.height - 2 * top
        val blocks = FloatArray(THUMB_W * THUMB_H)
        val counts = IntArray(THUMB_W * THUMB_H)
        var y = 0
        while (y < guideHeight) {
            val row = (y * THUMB_H / guideHeight).let { if (flipped) THUMB_H - 1 - it else it }
            val offset = (top + y) * rowStride
            var x = 0
            while (x < guideWidth) {
                val col = (x * THUMB_W / guideWidth).let { if (flipped) THUMB_W - 1 - it else it }
                val index = row * THUMB_W + col
                blocks[index] += (luma.get(offset + (left + x) * pixelStride).toInt() and 0xFF).toFloat()
                counts[index]++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        for (i in blocks.indices) if (counts[i] > 0) blocks[i] /= counts[i]

        val cellW = THUMB_W / BeaconCode.COLS
        val cellH = THUMB_H / BeaconCode.ROWS
        val cells = FloatArray(BeaconCode.COLS * BeaconCode.ROWS)
        for (cell in cells.indices) {
            val cx = (cell % BeaconCode.COLS) * cellW
            val cy = (cell / BeaconCode.COLS) * cellH
            var sum = 0f
            var n = 0
            for (by in cy + cellH / 5 until cy + cellH - cellH / 5) {
                for (bx in cx + cellW / 5 until cx + cellW - cellW / 5) {
                    sum += blocks[by * THUMB_W + bx]
                    n++
                }
            }
            cells[cell] = sum / n
        }
        decoder.add(cells)
        drawMagnified(blocks, cells)
        val reading = decoder.read()
        main.post { show(reading) }
    }

    /**
     * What changed since the last frame, magnified, and turned the same way up each time using the
     * reference column, so the code shows as a steady grid when the camera can see it.
     */
    private fun drawMagnified(blocks: FloatArray, cells: FloatArray) {
        val before = previous
        previous = blocks
        val lastCells = lastCells
        this.lastCells = cells
        if (before == null || lastCells == null) return
        var polarity = 0f
        for (row in 0 until BeaconCode.ROWS) {
            val c = row * BeaconCode.COLS
            polarity += (if (row % 2 == 0) 1 else -1) * (cells[c] - lastCells[c])
        }
        val sign = if (polarity >= 0) 1f else -1f
        var common = 0f
        for (i in blocks.indices) common += blocks[i] - before[i]
        common /= blocks.size
        for (i in blocks.indices) {
            val v = (128 + sign * (blocks[i] - before[i] - common) * MAGNIFY).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        main.post {
            thumb.setPixels(pixels, 0, THUMB_W, 0, 0, THUMB_W, THUMB_H)
            magnified.invalidate()
        }
    }

    private var lastCells: FloatArray? = null

    private fun show(reading: BeaconDecoder.Reading?) {
        if (isFinishing) return
        val seconds = (SystemClock.elapsedRealtime() - readSince) / 1000f
        val sure = ((reading?.confidence ?: 0f) / BeaconDecoder.ACCEPT * 100).toInt().coerceAtMost(100)
        detail.text = getString(R.string.scan_detail, sure, cameraFps, tvRefresh, reads)
        guide.sure = sure / 100f
        if (reading == null || checking) return
        if (!reading.accepted) {
            if (lastRead == null && tvRefresh > 0f) status.text = getString(R.string.scan_looking, sure)
            return
        }
        val code = reading.code ?: return
        if (code == lastRead) return
        checking = true
        Thread {
            val match = runCatching { store.api()?.beaconMatches(code) == true }.getOrDefault(false)
            main.post {
                checking = false
                lastRead = code
                if (match) {
                    reads++
                    status.text = getString(R.string.scan_read, seconds)
                } else {
                    status.text = getString(R.string.scan_wrong, code)
                }
            }
        }.start()
    }

    // ---- Screen ----------------------------------------------------------------------------------

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.ground)) }
        preview = TextureView(this)
        guide = GuideView(this)
        magnified = ImageView(this).apply {
            setImageDrawable(BitmapDrawable(resources, thumb).apply { paint.isFilterBitmap = false })
            scaleType = ImageView.ScaleType.FIT_XY
            background = GradientDrawable().apply { setStroke(dp(1), getColor(R.color.hairline)) }
        }
        status = text(18f, R.color.ink, bold()).apply { setText(R.string.scan_starting) }
        detail = text(12f, R.color.ink_dim, medium())
        strengthButton = button(getString(R.string.scan_strength, strength)) { changeStrength(1) }
        val fainter = button(getString(R.string.scan_fainter)) { changeStrength(-1) }
        shutterButton = button(shutterLabel()) { changeShutter() }
        val again = button(getString(R.string.scan_again)) {
            restartReading()
            status.setText(R.string.scan_aim)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(fainter)
            addView(strengthButton)
            addView(shutterButton)
            addView(again)
        }
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(status)
            addView(detail.apply { setPadding(0, dp(2), 0, dp(8)) })
            addView(controls)
        }
        root.addView(preview, FrameLayout.LayoutParams(0, 0))
        root.addView(guide, FrameLayout.LayoutParams(0, 0))
        root.addView(words, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            // Sits over the bottom of the preview, outside the guide.
        })
        words.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE60B0C0E.toInt(), 0x000B0C0E))
        root.addView(magnified, FrameLayout.LayoutParams(dp(THUMB_W * 3 / 2), dp(THUMB_H * 3 / 2), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(12), dp(12), 0)
        })
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

    private fun button(label: String, onClick: () -> Unit) = text(14f, R.color.ink, bold()).apply {
        text = label
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(getColor(R.color.raised))
            setStroke(dp(1), getColor(R.color.hairline))
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
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

    /** The frame to fit the TV picture into, with the code's grid faintly drawn and corners that fill in as it reads. */
    private class GuideView(context: Context) : View(context) {
        var sure = 0f
            set(value) {
                if (abs(value - field) < 0.01f) return
                field = value
                invalidate()
            }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density
            color = context.getColor(R.color.hairline)
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * context.resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            color = context.getColor(R.color.ink)
        }

        override fun onDraw(canvas: Canvas) {
            val l = width * GUIDE_INSET
            val t = height * GUIDE_INSET
            val r = width - l
            val b = height - t
            for (col in 1 until BeaconCode.COLS) {
                val x = l + (r - l) * col / BeaconCode.COLS
                canvas.drawLine(x, t, x, b, grid)
            }
            for (row in 1 until BeaconCode.ROWS) {
                val y = t + (b - t) * row / BeaconCode.ROWS
                canvas.drawLine(l, y, r, y, grid)
            }
            canvas.drawRect(l, t, r, b, grid)
            // Corner marks grow from a tick to a long bracket as the reading firms up.
            val arm = (r - l) * (0.04f + 0.12f * sure)
            corner.alpha = (110 + 145 * sure).toInt()
            for ((x, y, dx, dy) in listOf(floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f), floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f))) {
                canvas.drawLine(x, y, x + dx * arm, y, corner)
                canvas.drawLine(x, y, x, y + dy * arm, corner)
            }
        }
    }

    private companion object {
        const val CAMERA_REQUEST = 7
        const val START_STRENGTH = 4
        const val MAX_STRENGTH = 24
        const val BEACON_SECONDS = 20
        const val RENEW_MS = 15_000L
        const val AUTO_FRAMES = 20
        const val FRAME_NS = 40_000_000L
        val SHUTTERS_NS = longArrayOf(4_000_000L, 8_000_000L, 12_000_000L)
        const val GUIDE_INSET = 0.08f
        const val THUMB_W = 144
        const val THUMB_H = 80
        const val SAMPLE_STEP = 2
        const val MAGNIFY = 24f
    }
}
