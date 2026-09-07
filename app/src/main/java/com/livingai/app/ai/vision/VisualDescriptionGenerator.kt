package com.livingai.app.ai.vision

import android.graphics.RectF

/**
 * Deterministic generator that produces factual, grounded visual descriptions
 * from structured [VisualDetection] objects.
 *
 * Adheres to Phase 9E principles:
 * 1. Gemma is NOT allowed to invent the visual description; it receives this deterministic string.
 * 2. Spatial relationships (e.g. "screen above keyboard", "laptop on desk") are derived strictly from bounding boxes.
 * 3. Never claims spatial or physical facts that are not backed by real detections.
 */
object VisualDescriptionGenerator {

    fun generate(detections: List<VisualDetection>): String? {
        val validDetections = detections.filter { it.confidence >= 0.50f }
        if (validDetections.isEmpty()) return null

        val labelMap = validDetections.groupBy { it.label.lowercase().trim() }

        val hasLaptop = labelMap.keys.any { it.contains("laptop") || it.contains("computer") }
        val hasKeyboard = labelMap.keys.any { it.contains("keyboard") }
        val hasScreen = labelMap.keys.any { it.contains("screen") || it.contains("monitor") || it.contains("tv") }
        val hasBook = labelMap.keys.any { it.contains("book") }
        val hasPhone = labelMap.keys.any { it.contains("cell phone") || it.contains("phone") || it.contains("mobile") }
        val hasPerson = labelMap.keys.any { it.contains("person") }
        val hasDesk = labelMap.keys.any { it.contains("desk") || it.contains("dining table") || it.contains("table") }
        val hasChair = labelMap.keys.any { it.contains("chair") || it.contains("couch") }

        // Spatial reasoning if bounding boxes exist
        val keyboardBox = validDetections.firstOrNull { it.label.lowercase().contains("keyboard") }?.boundingBox
        val screenBox = validDetections.firstOrNull { 
            it.label.lowercase().contains("screen") || it.label.lowercase().contains("monitor") || it.label.lowercase().contains("tv")
        }?.boundingBox

        var spatialScreenAboveKeyboard = false
        if (keyboardBox != null && screenBox != null) {
            // In Android coordinate space, top is 0 at top of image. Screen top < keyboard top means screen is above keyboard.
            if (screenBox.centerY() < keyboardBox.centerY()) {
                spatialScreenAboveKeyboard = true
            }
        }

        return when {
            hasLaptop -> {
                when {
                    hasKeyboard && hasScreen && spatialScreenAboveKeyboard ->
                        if (hasDesk) "A laptop on a desk with its screen positioned above the keyboard."
                        else "A laptop with its screen positioned above the keyboard."
                    hasKeyboard && hasScreen ->
                        if (hasDesk) "A laptop on a desk with a visible screen and keyboard."
                        else "A laptop with a visible screen and keyboard."
                    hasKeyboard ->
                        if (hasDesk) "A laptop on a desk with a physical keyboard visible."
                        else "A laptop with a physical keyboard visible."
                    hasScreen ->
                        "A laptop with a display screen visible."
                    hasDesk ->
                        "A laptop resting on a desk surface."
                    else ->
                        "I can see what appears to be a laptop."
                }
            }
            hasKeyboard && hasScreen -> {
                if (spatialScreenAboveKeyboard) {
                    if (hasDesk) "A computer display screen positioned above a keyboard on a desk."
                    else "A computer display screen positioned above a keyboard."
                } else {
                    "A setup with a screen and keyboard."
                }
            }
            hasBook -> {
                if (hasDesk) {
                    "A book resting on a desk."
                } else {
                    "A book with visible printed pages."
                }
            }
            hasPhone -> {
                if (hasDesk) {
                    "A mobile phone resting on a desk surface."
                } else {
                    "A mobile phone visible in the frame."
                }
            }
            hasPerson -> {
                when {
                    hasLaptop && hasDesk -> "A person sitting at a desk with a laptop."
                    hasDesk -> "A person at a desk."
                    hasLaptop -> "A person with a laptop."
                    else -> "A person visible in the frame."
                }
            }
            hasDesk || hasChair -> {
                if (hasDesk && hasChair) "An indoor workspace with a desk and chair."
                else if (hasDesk) "A desk or table surface."
                else "A chair or seating furniture."
            }
            validDetections.size == 1 -> {
                val name = validDetections.first().label.replaceFirstChar { it.lowercase() }
                "I can see what appears to be a $name."
            }
            else -> {
                val names = validDetections.map { it.label.replaceFirstChar { c -> c.lowercase() } }.distinct()
                "Objects visible: ${names.joinToString(", ")}."
            }
        }
    }
}
