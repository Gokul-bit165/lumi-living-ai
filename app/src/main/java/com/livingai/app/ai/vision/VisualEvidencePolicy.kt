package com.livingai.app.ai.vision

/**
 * Evaluates candidate visual signals (pixel-level detections, OCR text, and lightweight image classifier labels)
 * into a structured [VisualEvidence] with an assigned [VisualEvidenceCategory].
 *
 * Core Principles (Phase 9E):
 * 1. Pixel detections from compact local vision models are prioritized for physical objects.
 * 2. High confidence (>= 0.75): treated as strong object evidence.
 * 3. Medium confidence (0.50 <= conf < 0.75): treated as tentative / candidate observation.
 * 4. Low confidence (< 0.50): filtered out completely.
 * 5. Visual descriptions are generated deterministically via [VisualDescriptionGenerator] (never hallucinated).
 * 6. Reading text on a surface is decoupled from object inference unless corroborated by real detections.
 */
class VisualEvidencePolicy(
    val strongObjectThreshold: Float = 0.75f,
    val candidateObjectThreshold: Float = 0.50f,
    val minOcrTextLength: Int = 4,
    val genericNoisyLabels: Set<String> = setOf(
        "Sky", "Cloud", "Atmosphere", "Daytime", "Plant", "Wood", "Pattern"
    )
) {
    fun evaluate(
        ocrText: String?,
        rawLabelConfidences: Map<String, Float> = emptyMap(),
        detections: List<VisualDetection> = emptyList(),
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        ocrLatencyMs: Long = 0,
        labelLatencyMs: Long = 0,
        visionModelLatencyMs: Long = 0
    ): VisualEvidence {
        val cleanText = ocrText?.trim()?.takeIf { it.length >= minOcrTextLength }
        val hasText = !cleanText.isNullOrBlank()

        // 1. Filter pixel detections by threshold
        val validDetections = detections.filter { it.confidence >= candidateObjectThreshold }
        val strongDetections = validDetections.filter { it.confidence >= strongObjectThreshold }

        // 2. Generate deterministic visual description
        val visualDescription = VisualDescriptionGenerator.generate(validDetections)

        // 3. Filter lightweight classifier candidate labels
        val candidateLabels = rawLabelConfidences.filter { it.value >= candidateObjectThreshold }
        val hasConcreteSignals = candidateLabels.keys.any { it !in genericNoisyLabels } || validDetections.isNotEmpty()
        val consistentLabels = candidateLabels.filterNot { (label, _) ->
            (hasText && genericNoisyLabels.contains(label)) ||
            (!hasConcreteSignals && genericNoisyLabels.contains(label))
        }

        val strongLabels = consistentLabels.filter { (label, conf) ->
            conf >= strongObjectThreshold && !genericNoisyLabels.contains(label)
        }

        // 4. Merge detections into label confidences if not present
        val mergedLabelConfidences = consistentLabels.toMutableMap()
        for (det in validDetections) {
            val key = det.label.replaceFirstChar { it.uppercase() }
            if (!mergedLabelConfidences.containsKey(key) || (mergedLabelConfidences[key] ?: 0f) < det.confidence) {
                mergedLabelConfidences[key] = det.confidence
            }
        }

        val category = when {
            strongDetections.isNotEmpty() || strongLabels.isNotEmpty() -> VisualEvidenceCategory.STRONG_OBJECT
            hasText -> VisualEvidenceCategory.STRONG_TEXT
            validDetections.isNotEmpty() || consistentLabels.isNotEmpty() -> VisualEvidenceCategory.WEAK_OBJECT
            else -> VisualEvidenceCategory.NO_RELIABLE_EVIDENCE
        }

        return VisualEvidence(
            ocrText = cleanText,
            detections = validDetections,
            labels = mergedLabelConfidences.keys.toList(),
            labelConfidences = mergedLabelConfidences,
            visualDescription = visualDescription,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            hasTextEvidence = hasText,
            hasObjectEvidence = validDetections.isNotEmpty() || consistentLabels.isNotEmpty() || !visualDescription.isNullOrBlank(),
            confidenceLevel = category,
            ocrLatencyMs = ocrLatencyMs,
            labelLatencyMs = labelLatencyMs,
            visionModelLatencyMs = visionModelLatencyMs
        )
    }
}
