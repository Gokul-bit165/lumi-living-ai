package com.livingai.app.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.livingai.app.core.LivingAiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.resume

/**
 * Production implementation of [VisualUnderstandingProvider] and [LocalVisionModel].
 *
 * Architecture (Phase 9E):
 * CAMERA FRAME
 *   ↓
 * IMAGE PREPROCESSING & RESIZING
 *   ↓
 * 1. LOCAL PIXEL PERCEPTION (MediaPipe Tasks Vision / EfficientDet-Lite0: ~13.8 MB, 30-50ms)
 *    → Consumes actual raw image pixels. Outputs physical object detections + bounding boxes.
 * 2. ML KIT LATIN TEXT RECOGNITION v2 (On-device OCR: ~40-100ms)
 *    → Extracts visible text without inferring physical objects.
 * 3. ML KIT LIGHTWEIGHT IMAGE LABELING (Supporting candidate signals)
 *   ↓
 * VISUAL EVIDENCE POLICY
 *   ↓
 * STRUCTURED VISUAL EVIDENCE (Detections + Deterministic Description + OCR)
 *
 * Runs 100% offline with zero network connectivity.
 */
class LocalVisionModelProvider(
    private val context: Context,
    private val policy: VisualEvidencePolicy = VisualEvidencePolicy()
) : VisualUnderstandingProvider, LocalVisionModel, Closeable {

    override val modelName: String = "MediaPipe EfficientDet-Lite0 (Pixels) + ML Kit Text Recognition (16.0.1)"
    override val providerName: String = "EfficientDet-Lite0 (MediaPipe Tasks Vision 0.10.14) + ML Kit Text Recognition (16.0.1)"

    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.50f)
                .build()
        )
    }

    private var objectDetector: ObjectDetector? = null
    private var detectorInitialized = false

    @Synchronized
    private fun getOrInitDetector(): ObjectDetector? {
        if (detectorInitialized) return objectDetector
        detectorInitialized = true
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("models/efficientdet_lite0.tflite")
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(8)
                .setScoreThreshold(0.40f)
                .build()
            val detector = ObjectDetector.createFromOptions(context, options)
            LivingAiLog.event("LOCAL_VISION", "Initialized MediaPipe EfficientDet-Lite0 from assets")
            objectDetector = detector
            detector
        } catch (e: Throwable) {
            LivingAiLog.event("LOCAL_VISION", "Failed to initialize MediaPipe ObjectDetector: ${e.message}")
            null
        }
    }

    override suspend fun extractText(bitmap: Bitmap): Result<String> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result -> cont.resume(Result.success(result.text)) }
            .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
    }

    override suspend fun extractLabelConfidences(bitmap: Bitmap): Result<Map<String, Float>> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        imageLabeler.process(image)
            .addOnSuccessListener { labels ->
                val map = labels.associate { it.text to it.confidence }
                cont.resume(Result.success(map))
            }
            .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
    }

    suspend fun detectObjects(bitmap: Bitmap): Result<List<VisualDetection>> = withContext(Dispatchers.Default) {
        val detector = getOrInitDetector() ?: return@withContext Result.failure(IllegalStateException("Detector unavailable"))
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val detectionResult = detector.detect(mpImage)
            val detections = detectionResult.detections().mapNotNull { det ->
                val category = det.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val box = det.boundingBox()
                VisualDetection(
                    label = category.categoryName(),
                    confidence = category.score(),
                    boundingBox = VisualBoundingBox(box.left, box.top, box.right, box.bottom)
                )
            }
            Result.success(detections)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun analyze(bitmap: Bitmap): VisualEvidence {
        // 1. Pixel Perception Inference (MediaPipe Tasks Vision)
        val pixelStart = System.currentTimeMillis()
        val detectionResult = detectObjects(bitmap)
        val pixelLatencyMs = System.currentTimeMillis() - pixelStart
        val detections = detectionResult.getOrDefault(emptyList())

        // 2. OCR Inference (ML Kit Text Recognition v2)
        val ocrStart = System.currentTimeMillis()
        val textResult = extractText(bitmap)
        val ocrLatencyMs = System.currentTimeMillis() - ocrStart
        val ocrText = textResult.getOrNull()

        // 3. Ambient Label Inference (ML Kit Image Labeling)
        val labelStart = System.currentTimeMillis()
        val labelResult = extractLabelConfidences(bitmap)
        val labelLatencyMs = System.currentTimeMillis() - labelStart
        val labelMap = labelResult.getOrDefault(emptyMap())

        return policy.evaluate(
            ocrText = ocrText,
            rawLabelConfidences = labelMap,
            detections = detections,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            ocrLatencyMs = ocrLatencyMs,
            labelLatencyMs = labelLatencyMs,
            visionModelLatencyMs = pixelLatencyMs
        )
    }

    override fun close() {
        objectDetector?.close()
        objectDetector = null
        detectorInitialized = false
    }
}
