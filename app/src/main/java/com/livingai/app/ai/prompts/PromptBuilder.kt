package com.livingai.app.ai.prompts

import com.livingai.app.ai.model.AIRequest
import com.livingai.app.ai.model.AIRequestType
import com.livingai.app.ai.vision.VisualEvidence
import com.livingai.app.ai.vision.VisualEvidenceCategory
import com.livingai.app.ai.vision.VisualEvidencePolicy

/**
 * Builds the grounded prompt sent to the local model.
 *
 * Adheres to Phase 9E specifications:
 * 1. Visual object evidence from pixel perception is presented as structured facts with confidence.
 * 2. OCR text is separated into VISIBLE TEXT.
 * 3. Special "What do you see?" handling: FACT FIRST, PERSONALITY SECOND.
 * 4. Strict negative rules: zero invented birds, sky, instruments, or scanners.
 */
object PromptBuilder {

    private val SYSTEM_PROMPT = """
        You are Lumi, a friendly personal AI companion living on the user's phone.
        Be warm, concise, and natural — talk like a supportive friend, not a formal assistant.
        Keep answers short (1-3 sentences) unless asked for more detail.
        You do not have real feelings or consciousness — never claim otherwise.
        Respond in this exact JSON shape and nothing else:
        {"response": "<your reply>", "emotion": "THINKING|EXPLAINING|HAPPY|CONFUSED"}
    """.trimIndent()

    fun build(
        request: AIRequest,
        visualEvidence: VisualEvidence? = null,
        relevantMemory: List<String> = emptyList()
    ): String {
        val isCameraQuery = request.type == AIRequestType.CAMERA_QUESTION || request.imageBytes != null || visualEvidence != null

        val context = buildString {
            if (request.focusActive && request.goalTitle != null) {
                append("The user is currently focusing on: \"${request.goalTitle}\".\n")
            }
            if (relevantMemory.isNotEmpty()) {
                append("Relevant memory: ${relevantMemory.joinToString("; ")}.\n")
            }

            if (isCameraQuery) {
                val evidence = visualEvidence ?: VisualEvidence()
                val userTextLower = request.userText?.lowercase() ?: ""
                val isWhatDoYouSee = userTextLower.contains("what do you see") ||
                    userTextLower.contains("what is this") ||
                    userTextLower.contains("what's in this") ||
                    userTextLower.contains("what is in this") ||
                    userTextLower.contains("explain what i'm looking at")

                if (!evidence.visualDescription.isNullOrBlank()) {
                    appendLine("VISUAL DESCRIPTION (from local on-device pixel inspection):")
                    appendLine("\"\"\"")
                    appendLine(evidence.visualDescription)
                    appendLine("\"\"\"")
                    appendLine()
                }

                if (evidence.labelConfidences.isNotEmpty()) {
                    appendLine("VISUAL OBJECT EVIDENCE:")
                    evidence.labelConfidences.forEach { (label, conf) ->
                        appendLine("- $label (${(conf * 100).toInt()}%)")
                    }
                    appendLine()
                }

                if (!evidence.ocrText.isNullOrBlank()) {
                    appendLine("VISIBLE TEXT:")
                    appendLine("\"\"\"")
                    appendLine(evidence.ocrText)
                    appendLine("\"\"\"")
                    appendLine()
                }

                when (evidence.confidenceLevel) {
                    VisualEvidenceCategory.NO_RELIABLE_EVIDENCE -> {
                        appendLine("STATUS: No reliable visual evidence was extracted from this image.")
                        appendLine("RULES:")
                        appendLine("- You cannot see the image directly.")
                        appendLine("- Do not invent physical objects, people, locations, sky, birds, instruments, or scanners.")
                        appendLine("- State clearly: \"I can't reliably tell what's in this image yet. Try moving closer or improving the lighting.\"")
                        appendLine("- Keep answer to 1–2 sentences.")
                    }
                    else -> {
                        appendLine("RULES:")
                        appendLine("- Answer using only provided visual evidence.")
                        appendLine("- Do not invent physical objects.")
                        appendLine("- Do not infer unseen details.")
                        appendLine("- If evidence is insufficient, say so.")
                        appendLine("- Keep answer to 1–3 sentences.")
                        if (isWhatDoYouSee) {
                            appendLine("- FACT FIRST. PERSONALITY SECOND. First state the factual visual description (e.g. \"I can see a laptop on a desk with a visible screen and keyboard.\"). Supportive personality is allowed only after the factual answer.")
                        } else {
                            appendLine("- Personality is allowed only after the factual answer.")
                        }
                    }
                }
            }
        }

        return buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            if (context.isNotBlank()) {
                appendLine("CONTEXT:")
                appendLine(context.trimEnd())
                appendLine()
            }
            appendLine("USER: ${request.userText ?: "What do you see in this picture?"}")
        }
    }

    /**
     * Backward-compatible overload for callers passing raw strings/lists.
     * Evaluates them via [VisualEvidencePolicy] to preserve grounding.
     */
    fun build(
        request: AIRequest,
        extractedImageText: String?,
        detectedObjects: List<String> = emptyList(),
        relevantMemory: List<String> = emptyList()
    ): String {
        val isCameraQuery = request.type == AIRequestType.CAMERA_QUESTION || request.imageBytes != null || !extractedImageText.isNullOrBlank() || detectedObjects.isNotEmpty()
        val evidence = if (isCameraQuery) {
            val labelMap = detectedObjects.associate { str ->
                val name = str.substringBefore(" (").trim()
                val conf = runCatching {
                    str.substringAfter(" (").substringBefore("%)").toFloat() / 100f
                }.getOrDefault(0.70f)
                name to conf
            }
            VisualEvidencePolicy().evaluate(extractedImageText, labelMap)
        } else {
            null
        }
        return build(request, evidence, relevantMemory)
    }
}
