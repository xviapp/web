package app.xvi.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Scans a QR code with the back camera, or from a picture the user picks, and returns its text as
 * [EXTRA_TEXT]. Frames are decoded on a background thread and never stored.
 */
class ScannerActivity : AppCompatActivity() {
    private lateinit var ui: BrowserUi
    private lateinit var texture: TextureView
    private lateinit var torchButton: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var previewSize = Size(1280, 720)
    private var torch = false
    @Volatile private var done = false
    private val decoder = qrReader(tryHarder = false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openWhenReady() else toast(R.string.scan_camera_denied)
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::decodePicture) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = BrowserUi(this, BrowserPalette(this, BrowserPrefs(this)))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openWhenReady()
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fit()
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
        root.addView(texture, ui.full())
        root.addView(Viewfinder(this), ui.full())
        val controls = FrameLayout(this)
        controls.addView(ui.iconAction(R.drawable.ic_close_24, getString(R.string.action_close), Color.WHITE) { finish() },
            FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(8), ui.dp(8), 0, 0) })
        controls.addView(ui.label(getString(R.string.scan_hint), 14f, Color.WHITE).apply { gravity = Gravity.CENTER },
            ui.anchored(Gravity.BOTTOM).apply { bottomMargin = ui.dp(104) })
        val actions = ui.row().apply { gravity = Gravity.CENTER }
        torchButton = scanAction(getString(R.string.scan_torch_on)) { setTorch(!torch) }
        actions.addView(scanAction(getString(R.string.scan_from_album)) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, ui.weighted().apply { marginEnd = ui.dp(12) })
        actions.addView(torchButton, ui.weighted())
        controls.addView(actions, ui.anchored(Gravity.BOTTOM).apply { setMargins(ui.dp(24), 0, ui.dp(24), ui.dp(32)) })
        root.addView(controls, ui.full())
        ViewCompat.setOnApplyWindowInsetsListener(controls) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.CAMERA)
    }

    private fun scanAction(text: String, click: () -> Unit) = ui.asButton(ui.label(text, 14f, Color.WHITE)).apply {
        gravity = Gravity.CENTER
        minimumHeight = ui.dp(48)
        background = ui.pressable(0x66000000, 24)
        setOnClickListener { click() }
    }

    override fun onResume() {
        super.onResume()
        thread = HandlerThread("scanner").also { it.start(); handler = Handler(it.looper) }
        openWhenReady()
    }

    override fun onPause() {
        close()
        thread?.quitSafely()
        thread = null; handler = null
        super.onPause()
    }

    @SuppressLint("MissingPermission") // Checked just before opening.
    private fun openWhenReady() {
        if (camera != null || done || handler == null || !texture.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java)
        val id = runCatching {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull()
        }.getOrNull() ?: run { toast(R.string.scan_no_camera); finish(); return }
        val characteristics = manager.getCameraCharacteristics(id)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // Big enough for small codes, small enough to decode several frames a second.
        previewSize = sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height } ?: sizes.firstOrNull() ?: previewSize
        torchButton.visibility = if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) View.VISIBLE else View.GONE
        reader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener(::decodeFrame, handler)
        }
        fit()
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { camera = device; startPreview(device) }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); if (camera === device) camera = null
                    runOnUiThread { toast(R.string.scan_camera_failed) }
                }
            }, handler)
        }.onFailure { toast(R.string.scan_camera_failed) }
    }

    @Suppress("DEPRECATION") // The list form is all that minSdk 26 offers.
    private fun startPreview(device: CameraDevice) {
        val texture = texture.surfaceTexture ?: return
        val output = reader ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val preview = Surface(texture)
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview); addTarget(output.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        device.createCaptureSession(listOf(preview, output.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                if (camera !== device) { created.close(); return }
                session = created; request = builder
                runCatching { created.setRepeatingRequest(builder.build(), null, handler) }
            }
            override fun onConfigureFailed(failed: CameraCaptureSession) { runOnUiThread { toast(R.string.scan_camera_failed) } }
        }, handler)
    }

    private fun setTorch(on: Boolean) {
        val builder = request ?: return
        torch = on
        builder.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        runCatching { session?.setRepeatingRequest(builder.build(), null, handler) }
        torchButton.text = getString(if (on) R.string.scan_torch_off else R.string.scan_torch_on)
    }

    /** Fills the view with the preview, cropping instead of stretching. */
    private fun fit() {
        val width = texture.width.toFloat()
        val height = texture.height.toFloat()
        if (width == 0f || height == 0f) return
        // The sensor is landscape; in this portrait screen the frame shows rotated, so its sides swap.
        val shownWidth = previewSize.height.toFloat()
        val shownHeight = previewSize.width.toFloat()
        val scale = max(width / shownWidth, height / shownHeight)
        texture.setTransform(Matrix().apply { setScale(shownWidth * scale / width, shownHeight * scale / height, width / 2, height / 2) })
    }

    private fun decodeFrame(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (done) return
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val buffer = plane.buffer
            val data = ByteArray(width * height)
            if (plane.rowStride == width) buffer.get(data, 0, width * height)
            else for (row in 0 until height) { buffer.position(row * plane.rowStride); buffer.get(data, row * width, width) }
            // Only the middle of the frame, under the viewfinder.
            val side = (min(width, height) * 0.85f).toInt()
            val source = PlanarYUVLuminanceSource(data, width, height, (width - side) / 2, (height - side) / 2, side, side, false)
            val text = try { decode(decoder, source) } finally { decoder.reset() }
            if (text != null) { done = true; runOnUiThread { deliver(text) } }
        } catch (_: RuntimeException) {
        } finally { image.close() }
    }

    /** QR codes only; [tryHarder] trades speed for codes that are small or skewed in a still picture. */
    private fun qrReader(tryHarder: Boolean) = MultiFormatReader().apply {
        setHints(buildMap {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.CHARACTER_SET, "UTF-8")
            if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
        })
    }

    /** Light-on-dark codes are tried inverted. */
    private fun decode(reader: MultiFormatReader, source: LuminanceSource): String? =
        listOf(source, source.invert()).firstNotNullOfOrNull { candidate ->
            runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text }.getOrNull()
        }

    private fun decodePicture(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val bitmap = decodeSampled(2048) { options -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }
                        ?: return@runCatching null
                    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                    decode(qrReader(tryHarder = true), RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
                }.getOrNull()
            }
            if (text == null) toast(R.string.scan_none_in_picture) else { done = true; deliver(text) }
        }
    }

    private fun deliver(text: String) {
        texture.performHapticFeedback(CONFIRM_HAPTIC)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        finish()
    }

    private fun close() {
        if (torch) setTorch(false)
        session?.close(); session = null; request = null
        camera?.close(); camera = null
        reader?.close(); reader = null
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Dims everything but the scanning square and marks its corners. */
    @SuppressLint("ViewConstructor")
    private class Viewfinder(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val shade = Paint().apply { color = 0x88000000.toInt() }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4 * density; strokeCap = Paint.Cap.ROUND
        }
        private val frame = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val side = min(width, height) * 0.7f
            frame.set((width - side) / 2, (height - side) / 2 - 40 * density, (width + side) / 2, (height + side) / 2 - 40 * density)
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shade)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shade)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shade)
            val arm = 24 * density
            path.apply {
                reset()
                moveTo(frame.left, frame.top + arm); lineTo(frame.left, frame.top); lineTo(frame.left + arm, frame.top)
                moveTo(frame.right - arm, frame.top); lineTo(frame.right, frame.top); lineTo(frame.right, frame.top + arm)
                moveTo(frame.right, frame.bottom - arm); lineTo(frame.right, frame.bottom); lineTo(frame.right - arm, frame.bottom)
                moveTo(frame.left + arm, frame.bottom); lineTo(frame.left, frame.bottom); lineTo(frame.left, frame.bottom - arm)
            }
            canvas.drawPath(path, corner)
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
