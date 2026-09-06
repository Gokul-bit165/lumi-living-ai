package com.livingai.app.ai.prompts

import com.livingai.app.ai.model.AIRequest

/**
 * Builds the compact prompt sent to the local model. Deliberately short — every extra token
 * costs latency on a 1B on-device model, and the personality only needs to be stated once.
 */
object PromptBuilder {

    private val SYSTEM_PROMPT = """
        You are Lumi, a friendly personal AI companion living on the user's phone.
        Be warm, concise, and natural — talk like a supportive friend, not a formal assistant.
        Keep answers short (2-4 sentences) unless asked for more detail.
        You do not have real feelings or consciousness — never claim otherwise.
        Respond in this exact JSON shape and nothing else:
        {"response": "<your reply>", "emotion": "THINKING|EXPLAINING|HAPPY|CONFUSED"}
    """.trimIndent()

    fun build(request: AIRequest, extractedImageText: String?, relevantMemory: List<String>): String {
        val context = buildString {
            if (request.focusActive && request.goalTitle != null) {
                append("The user is currently focusing on: \"${request.goalTitle}\".\n")
            }
            if (relevantMemory.isNotEmpty()) {
                append("Relevant memory: ${relevantMemory.joinToString("; ")}.\n")
            }
            if (!extractedImageText.isNullOrBlank()) {
                append("Text extracted from the user's camera image:\n\"\"\"\n$extractedImageText\n\"\"\"\n")
            }
        }

        return buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            if (context.isNotBlank()) {
                appendLine("CONTEXT:")
                appendLine(context)
            }
            appendLine("USER: ${request.userText ?: "Explain this."}")
        }
    }
}
