package org.ramgpt.cad2cnycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.ramgpt.cad2cnycam.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private data class AnalysisFrame(
        val bitmap: Bitmap,
        val frameWidth: Int,
        val frameHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val analysisTransform: OutputTransform
    )

    private data class OcrPass(
        val id: String,
        val bitmap: Bitmap,
        val tile: Rect,
        val scale: Float
    )

    private data class SourcedCandidate(
        val source: String,
        val candidate: RetailPriceCandidate,
        val updatedAt: Long
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val priceTracker = MultiPriceTracker()
    private val prefs by lazy { getSharedPreferences("scanner_settings", MODE_PRIVATE) }
    private var rate = BigDecimal("5.20")
    private var taxOverrideMode = TaxOverrideMode.AUTO
    @Volatile private var debugOcrEnabled = false
    private var stableTracks: List<StablePriceTrack> = emptyList()
    private var tiledOcrEnabled = true
    private var ocrPassIndex = 0
    private val ocrJobTimes = java.util.ArrayDeque<Long>()
    private val candidateCache = mutableMapOf<String, List<SourcedCandidate>>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        rate = prefs.getString("cad_cny_rate", "5.20")?.toBigDecimalOrNull() ?: BigDecimal("5.20")
        updateRateButton()
        binding.taxModeButton.setOnClickListener {
            taxOverrideMode = when (taxOverrideMode) {
                TaxOverrideMode.AUTO -> TaxOverrideMode.ZERO_PERCENT
                TaxOverrideMode.ZERO_PERCENT -> TaxOverrideMode.THIRTEEN_PERCENT
                TaxOverrideMode.THIRTEEN_PERCENT -> TaxOverrideMode.AUTO
            }
            updateTaxModeButton()
            renderTracks()
        }
        binding.debugOcrSwitch.setOnCheckedChangeListener { _, enabled ->
            debugOcrEnabled = (BuildConfig.DEBUG && enabled)
            binding.priceOverlay.showDebugBounds = enabled
            if (!enabled) binding.priceOverlay.showDebugCandidateBoxes(emptyList())
            binding.ocrDebugText.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) binding.ocrDebugText.text = getString(R.string.ocr_debug_waiting)
        }
        binding.debugOcrSwitch.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.tiledOcrSwitch.isChecked = true
        binding.tiledOcrSwitch.setOnCheckedChangeListener { _, enabled -> tiledOcrEnabled = enabled }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_ocr", false)) {
            binding.debugOcrSwitch.isChecked = true
        }
        binding.replaceModeSwitch.setOnCheckedChangeListener { _, append ->
            binding.priceOverlay.displayMode = if (append) PriceDisplayMode.APPEND else PriceDisplayMode.REPLACE
        }
        binding.rateButton.setOnClickListener { showRateDialog() }
        binding.aboutPrivacyButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
        binding.controlsToggle.setOnClickListener {
            val expanding = binding.controlsContent.visibility != View.VISIBLE
            binding.controlsContent.visibility = if (expanding) View.VISIBLE else View.GONE
            binding.controlsToggle.setText(if (expanding) R.string.done else R.string.settings)
        }
        setupCameraGestures()
        val controlsPaddingBottom = binding.controlsPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlsPanel) { view, insets ->
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight,
                controlsPaddingBottom + navigationBar.bottom)
            insets
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (error: Exception) {
                Toast.makeText(this, error.localizedMessage ?: "Unable to start camera", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val frame = try { prepareFullFrame(proxy) } catch (_: Exception) { null }
        if (frame == null) { proxy.close(); return }
        val pass = prepareOcrPass(frame)
        val startedAt = SystemClock.elapsedRealtime()
        recognizer.process(InputImage.fromBitmap(pass.bitmap, 0))
            .addOnSuccessListener { result ->
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val now = SystemClock.elapsedRealtime()
                ocrJobTimes.addLast(now)
                while (ocrJobTimes.isNotEmpty() && now - ocrJobTimes.first > 1000) ocrJobTimes.removeFirst()
                val prefix = if (pass.id == "FULL") "FULL" else pass.id
                if (debugOcrEnabled) {
                    Log.d(OCR_LOG_TAG, prefix + "_OCR_MS=" + elapsed)
                    Log.d(OCR_LOG_TAG, "TOTAL_OCR_JOBS_PER_SEC=" + ocrJobTimes.size)
                    result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.forEach { element ->
                        element.boundingBox?.let { local ->
                            Log.d(OCR_LOG_TAG, prefix + " element: text=\"" + element.text +
                                "\" rect=" + toFullRect(local, pass))
                        }
                    }
                    runOnUiThread { binding.ocrDebugText.text = getString(R.string.ocr_debug_value,
                        result.text.ifBlank { getString(R.string.ocr_debug_empty) }) }
                }
                val localDecision = RetailPriceDetector.detect(result)
                candidateCache[pass.id] = localDecision.candidates.map {
                    SourcedCandidate(pass.id, toFullCandidate(it, pass), now)
                }
                candidateCache.entries.removeAll { entry ->
                    entry.value.all { now - it.updatedAt > CANDIDATE_CACHE_MS }
                }
                val candidates = dedupeCandidates(candidateCache.values.flatten())
                val debugCandidates = localDecision.debugCandidates.map { debug ->
                    debug.copy(sourceBoundingBox = toFullRect(debug.sourceBoundingBox, pass))
                }
                if (debugOcrEnabled) localDecision.diagnostics.forEach { Log.d(OCR_LOG_TAG, prefix + " " + it) }
                val rawElements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
                    val value = element.text.trim()
                    element.boundingBox?.takeIf { RAW_DECIMAL.matches(value) }?.let { value to toFullRect(it, pass) }
                }
                val background = candidates.associateWith { estimateLightBackground(frame.bitmap, it.sourceBoundingBox) }
                runOnUiThread {
                    val observations = candidates.mapNotNull { candidate ->
                        mapToPreview(candidate.sourceBoundingBox, frame)?.let { mapped ->
                            MappedPriceObservation(candidate.copy(mappedPreviewBoundingBox = RectF(mapped)),
                                mapped, background[candidate] ?: true)
                        }
                    }
                    stableTracks = priceTracker.update(observations, if (debugOcrEnabled) 1 else null)
                    renderTracks()
                    if (stableTracks.isNotEmpty()) { binding.resultText.visibility = View.GONE; binding.detailText.visibility = View.GONE }
                    if (debugOcrEnabled) {
                        binding.priceOverlay.showDebugCandidateBoxes(
                            rawElements.mapNotNull { pair -> mapToPreview(pair.second, frame)?.let {
                                DebugOverlayItem(it, prefix + " " + pair.first, DebugOverlayStatus.RAW)
                            } } + debugCandidates.mapNotNull { debug -> mapToPreview(debug.sourceBoundingBox, frame)?.let {
                                DebugOverlayItem(it, debug.label, if (debug.accepted) DebugOverlayStatus.ACCEPTED else DebugOverlayStatus.REJECTED)
                            } } + priceTracker.snapshots().map { track ->
                                DebugOverlayItem(RectF(track.previewBoundingBox), "TRACK " + track.trackId +
                                    " price=" + track.cadPrice + " hits=" + track.hits,
                                    if (track.isStable) DebugOverlayStatus.ACCEPTED else DebugOverlayStatus.PENDING)
                            })
                        priceTracker.events().forEach { Log.d(OCR_LOG_TAG, it) }
                    }
                }
            }
            .addOnCompleteListener {
                if (pass.bitmap !== frame.bitmap) pass.bitmap.recycle()
                frame.bitmap.recycle()
                proxy.close()
            }
    }

    private fun prepareOcrPass(frame: AnalysisFrame): OcrPass {
        if (!tiledOcrEnabled) return OcrPass("FULL", frame.bitmap, Rect(0, 0, frame.bitmap.width, frame.bitmap.height), 1f)
        val slot = ocrPassIndex++ % 5
        if (slot == 0) return OcrPass("FULL", frame.bitmap, Rect(0, 0, frame.bitmap.width, frame.bitmap.height), 1f)
        val width = frame.bitmap.width
        val height = frame.bitmap.height
        val overlapX = (width * 0.15f).toInt()
        val overlapY = (height * 0.15f).toInt()
        val stepX = (width - overlapX) / 2
        val stepY = (height - overlapY) / 2
        val left = if (slot == 1 || slot == 3) 0 else stepX
        val top = if (slot == 1 || slot == 2) 0 else stepY
        val tile = Rect(left, top, if (left == 0) width - stepX else width, if (top == 0) height - stepY else height)
        val crop = Bitmap.createBitmap(frame.bitmap, tile.left, tile.top, tile.width(), tile.height())
        val scaled = Bitmap.createScaledBitmap(crop, tile.width() * 2, tile.height() * 2, true)
        crop.recycle()
        val id = when (slot) { 1 -> "TILE_TL"; 2 -> "TILE_TR"; 3 -> "TILE_BL"; else -> "TILE_BR" }
        return OcrPass(id, scaled, tile, 2f)
    }

    private fun toFullRect(local: Rect, pass: OcrPass): Rect = Rect(
        pass.tile.left + (local.left / pass.scale).toInt(), pass.tile.top + (local.top / pass.scale).toInt(),
        pass.tile.left + (local.right / pass.scale).toInt(), pass.tile.top + (local.bottom / pass.scale).toInt())

    private fun toFullCandidate(candidate: RetailPriceCandidate, pass: OcrPass) = candidate.copy(
        sourceBoundingBox = toFullRect(candidate.sourceBoundingBox, pass),
        sourceElements = candidate.sourceElements.map { it.copy(boundingBox = toFullRect(it.boundingBox, pass)) })

    private fun dedupeCandidates(input: List<SourcedCandidate>): List<RetailPriceCandidate> {
        val canonical = mutableListOf<SourcedCandidate>()
        input.sortedByDescending { it.candidate.score }.forEach { incoming ->
            val matchIndex = canonical.indexOfFirst { existing -> duplicateCandidate(existing.candidate, incoming.candidate) }
            if (matchIndex < 0) {
                canonical += incoming
            } else {
                val existing = canonical[matchIndex]
                val winner = if (incoming.candidate.score > existing.candidate.score) incoming else existing
                canonical[matchIndex] = winner
                if (debugOcrEnabled && existing.source != incoming.source) {
                    Log.d(OCR_LOG_TAG, "DEDUP: " + existing.source + " " + existing.candidate.price.amount +
                        " rect=" + existing.candidate.sourceBoundingBox + " + " + incoming.source + " " +
                        incoming.candidate.price.amount + " rect=" + incoming.candidate.sourceBoundingBox +
                        " -> canonical " + winner.candidate.price.amount)
                }
            }
        }
        return canonical.map { it.candidate }
    }

    private fun duplicateCandidate(a: RetailPriceCandidate, b: RetailPriceCandidate): Boolean {
        if (a.price.amount.setScale(2, RoundingMode.HALF_UP).compareTo(
                b.price.amount.setScale(2, RoundingMode.HALF_UP)) != 0) return false
        val boxA = a.sourceBoundingBox
        val boxB = b.sourceBoundingBox
        val intersection = Rect()
        val intersectionArea = if (intersection.setIntersect(boxA, boxB))
            intersection.width().toFloat() * intersection.height() else 0f
        val unionArea = boxA.width().toFloat() * boxA.height() +
            boxB.width().toFloat() * boxB.height() - intersectionArea
        val iou = if (unionArea > 0f) intersectionArea / unionArea else 0f
        val centerDistance = kotlin.math.hypot((boxA.centerX() - boxB.centerX()).toDouble(),
            (boxA.centerY() - boxB.centerY()).toDouble())
        val scale = maxOf(boxA.width(), boxA.height(), boxB.width(), boxB.height()).coerceAtLeast(1)
        val areaA = (boxA.width() * boxA.height()).coerceAtLeast(1).toFloat()
        val areaB = (boxB.width() * boxB.height()).coerceAtLeast(1).toFloat()
        val sizeSimilarity = minOf(areaA, areaB) / maxOf(areaA, areaB)
        return iou >= 0.35f || (centerDistance <= scale * 0.8 && sizeSimilarity >= 0.4f)
    }

    private fun prepareFullFrame(proxy: ImageProxy): AnalysisFrame {
        val analysisTransform = ImageProxyTransformFactory().apply {
            setUsingCropRect(false)
            setUsingRotationDegrees(true)
        }.getOutputTransform(proxy)
        val source = proxy.toBitmap()
        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = if (rotation == 0) source else Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true
        ).also { source.recycle() }
        return AnalysisFrame(
            bitmap = rotated,
            frameWidth = rotated.width,
            frameHeight = rotated.height,
            cropLeft = 0,
            cropTop = 0,
            analysisTransform = analysisTransform
        )
    }

    private fun setupCameraGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCamera = camera ?: return false
                    val zoom = activeCamera.cameraInfo.zoomState.value ?: return false
                    val target = (zoom.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                    activeCamera.cameraControl.setZoomRatio(target)
                    return true
                }
            })
        var downX = 0f
        var downY = 0f
        binding.previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val tapSlop = 24 * resources.displayMetrics.density
                    if (!scaleDetector.isInProgress &&
                        kotlin.math.hypot(event.x - downX, event.y - downY) <= tapSlop &&
                        isInsideScanArea(event.x, event.y, view.width, view.height)) {
                        focusAt(event.x, event.y)
                        view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun isInsideScanArea(x: Float, y: Float, width: Int, height: Int): Boolean {
        val halfWidth = width * 0.82f / 2f
        val halfHeight = height * 0.25f / 2f
        return x in (width / 2f - halfWidth)..(width / 2f + halfWidth) &&
            y in (height / 2f - halfHeight)..(height / 2f + halfHeight)
    }

    private fun focusAt(x: Float, y: Float) {
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    private fun mapToPreview(box: android.graphics.Rect, frame: AnalysisFrame): RectF? {
        val previewTransform = binding.previewView.outputTransform ?: return null

        // ML Kit coordinates are ROI-local. Restore the ROI origin first so this
        // rectangle is in the full, upright ImageAnalysis coordinate system.
        val previewRect = RectF(box).apply {
            offset(frame.cropLeft.toFloat(), frame.cropTop.toFloat())
        }
        CoordinateTransform(frame.analysisTransform, previewTransform).mapRect(previewRect)

        // CoordinateTransform returns PreviewView-local pixels. Convert only the
        // sibling-view origin; do not introduce status/navigation-bar coordinates.
        val previewLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        binding.previewView.getLocationInWindow(previewLocation)
        binding.priceOverlay.getLocationInWindow(overlayLocation)
        previewRect.offset(
            (previewLocation[0] - overlayLocation[0]).toFloat(),
            (previewLocation[1] - overlayLocation[1]).toFloat()
        )
        if (debugOcrEnabled) {
            Log.d(OCR_LOG_TAG, "Mapped ROI box=" + box + " full/preview/overlay=" + previewRect +
                " rotation-aware CameraX transform")
        }
        return previewRect
    }

    private fun estimateLightBackground(bitmap: Bitmap, box: android.graphics.Rect): Boolean {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        val stepX = ((right - left) / 12).coerceAtLeast(1)
        val stepY = ((bottom - top) / 8).coerceAtLeast(1)
        var luminance = 0.0
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                luminance += (0.2126 * android.graphics.Color.red(pixel) +
                    0.7152 * android.graphics.Color.green(pixel) +
                    0.0722 * android.graphics.Color.blue(pixel)) / 255.0
                samples++
                x += stepX
            }
            y += stepY
        }
        return samples == 0 || luminance / samples >= 0.55
    }

    private fun renderTracks() {
        val items = stableTracks.map { track ->
            val taxClass = TaxClassifier.classify(track.productText)
            val taxRate = when (taxOverrideMode) {
                TaxOverrideMode.ZERO_PERCENT -> BigDecimal.ZERO
                TaxOverrideMode.THIRTEEN_PERCENT -> ONTARIO_HST_RATE
                TaxOverrideMode.AUTO -> when (taxClass) {
                    TaxClass.ZERO_RATED -> BigDecimal.ZERO
                    TaxClass.TAXABLE -> ONTARIO_HST_RATE
                    TaxClass.UNKNOWN -> DEFAULT_TAX_RATE
                }
            }
            if (debugOcrEnabled) {
                Log.d(OCR_LOG_TAG, "productText=\"" + track.productText + "\" taxClass=" +
                    taxClass + " taxRate=" + taxRate.toPlainString())
            }
            val cny = track.cadPrice.multiply(BigDecimal.ONE.add(taxRate)).multiply(rate)
                .setScale(2, RoundingMode.HALF_UP)
            PriceOverlayItem(
                trackId = track.trackId,
                cadPrice = track.cadPrice,
                cnyPrice = cny,
                sourceBoundingBox = android.graphics.Rect(track.sourceBoundingBox),
                previewBoundingBox = RectF(track.previewBoundingBox),
                confidenceScore = track.score.toFloat(),
                stabilizationState = OverlayStabilizationState.STABLE,
                isLightBackground = track.isLightBackground,
                missedFrames = track.missedFrames
            )
        }
        binding.priceOverlay.showItems(items)
    }

    private fun updateTaxModeButton() {
        binding.taxModeButton.setText(when (taxOverrideMode) {
            TaxOverrideMode.AUTO -> R.string.tax_mode_auto
            TaxOverrideMode.ZERO_PERCENT -> R.string.tax_mode_zero
            TaxOverrideMode.THIRTEEN_PERCENT -> R.string.tax_mode_thirteen
        })
    }

    private fun showRateDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(rate.stripTrailingZeros().toPlainString())
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rate_title)
            .setMessage(R.string.rate_message)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newRate = input.text.toString().toBigDecimalOrNull()
                if (newRate == null || newRate <= BigDecimal.ZERO) {
                    input.error = getString(R.string.invalid_rate)
                } else {
                    rate = newRate
                    prefs.edit().putString("cad_cny_rate", rate.toPlainString()).apply()
                    updateRateButton()
                    renderTracks()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun updateRateButton() {
        binding.rateButton.text = getString(R.string.rate_label, rate.stripTrailingZeros().toPlainString())
    }

    private fun showPermissionDenied() {
        AlertDialog.Builder(this)
            .setMessage(R.string.camera_permission_denied)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        recognizer.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CANDIDATE_CACHE_MS = 1500L
        private val ONTARIO_HST_RATE = BigDecimal("0.13")
        private val DEFAULT_TAX_RATE = BigDecimal("0.13")
        private val RAW_DECIMAL = Regex("""^\$?\s*\d{1,4}\s*[.,]\s*\d{2,3}$""")
        private const val OCR_LOG_TAG = "PriceScannerOCR"
    }
}
