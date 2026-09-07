package com.livingai.app.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualEvidencePolicyTest {

    private val policy = VisualEvidencePolicy(
        strongObjectThreshold = 0.85f,
        candidateObjectThreshold = 0.65f,
        minOcrTextLength = 4
    )

    @Test
    fun `empty OCR and empty labels evaluates to NO_RELIABLE_EVIDENCE`() {
        val evidence = policy.evaluate(
            ocrText = null,
            rawLabelConfidences = emptyMap()
        )

        assertEquals(VisualEvidenceCategory.NO_RELIABLE_EVIDENCE, evidence.confidenceLevel)
        assertFalse(evidence.hasTextEvidence)
        assertFalse(evidence.hasObjectEvidence)
        assertTrue(evidence.labels.isEmpty())
    }

    @Test
    fun `labels below candidate threshold evaluate to NO_RELIABLE_EVIDENCE`() {
        val evidence = policy.evaluate(
            ocrText = "",
            rawLabelConfidences = mapOf("Sky" to 0.40f, "Plant" to 0.55f)
        )

        assertEquals(VisualEvidenceCategory.NO_RELIABLE_EVIDENCE, evidence.confidenceLevel)
        assertFalse(evidence.hasObjectEvidence)
    }

    @Test
    fun `OCR text evaluates to STRONG_TEXT and filters contradictory generic sky labels`() {
        val evidence = policy.evaluate(
            ocrText = "class Assistant { fun help() }",
            rawLabelConfidences = mapOf("Sky" to 0.78f, "Desk" to 0.72f)
        )

        assertEquals(VisualEvidenceCategory.STRONG_TEXT, evidence.confidenceLevel)
        assertTrue(evidence.hasTextEvidence)
        assertEquals("class Assistant { fun help() }", evidence.ocrText)
        // "Sky" is inconsistent with code text and filtered as noisy classifier prior
        assertFalse(evidence.labels.contains("Sky"))
        assertTrue(evidence.labels.contains("Desk"))
    }

    @Test
    fun `high confidence specific object evaluates to STRONG_OBJECT`() {
        val evidence = policy.evaluate(
            ocrText = null,
            rawLabelConfidences = mapOf("Book" to 0.91f)
        )

        assertEquals(VisualEvidenceCategory.STRONG_OBJECT, evidence.confidenceLevel)
        assertTrue(evidence.hasObjectEvidence)
        assertEquals(listOf("Book"), evidence.labels)
    }

    @Test
    fun `moderate confidence label evaluates to WEAK_OBJECT as candidate observation`() {
        val evidence = policy.evaluate(
            ocrText = null,
            rawLabelConfidences = mapOf("Musical instrument" to 0.72f)
        )

        assertEquals(VisualEvidenceCategory.WEAK_OBJECT, evidence.confidenceLevel)
        assertTrue(evidence.hasObjectEvidence)
        assertEquals(listOf("Musical instrument"), evidence.labels)
        assertEquals(0.72f, evidence.labelConfidences["Musical instrument"] ?: 0f, 0.001f)
    }

    @Test
    fun `solitary generic noisy label alone evaluates to NO_RELIABLE_EVIDENCE`() {
        val evidence = policy.evaluate(
            ocrText = null,
            rawLabelConfidences = mapOf("Sky" to 0.88f)
        )

        // Solitary uncorroborated generic label on featureless background is discarded
        assertEquals(VisualEvidenceCategory.NO_RELIABLE_EVIDENCE, evidence.confidenceLevel)
        assertTrue(evidence.labels.isEmpty())
    }

    @Test
    fun `generic label corroborated by a concrete object signal is retained`() {
        val evidence = policy.evaluate(
            ocrText = null,
            rawLabelConfidences = mapOf("Sky" to 0.88f, "Tree" to 0.75f)
        )

        assertEquals(VisualEvidenceCategory.WEAK_OBJECT, evidence.confidenceLevel)
        assertTrue(evidence.labels.contains("Sky"))
        assertTrue(evidence.labels.contains("Tree"))
    }
}
