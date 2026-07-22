/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.domain.model.AssessmentStatus
import com.khateev.medvoice.domain.model.AssessmentSystem

/**
 * Recognizes structured nursing assessments — "skin within normal limits",
 * "neuro WNL", "chest clear", "skin abnormal, redness on the sacrum" — and the
 * bulk shortcut "head to toe within normal limits", which marks every system
 * normal in one utterance. Deterministic, no model.
 *
 * A system keyword alone is never enough: a status phrase is required, so
 * "respiratory rate 18" stays a vital and "page the neurologist" stays a
 * command.
 */
object AssessmentMatcher {

    /** One system, or every system at once for the head-to-toe shortcut. */
    data class Reading(
        val systems: List<AssessmentSystem>,
        val status: AssessmentStatus,
        val finding: String? = null
    )

    private val SYSTEM_KEYWORDS: List<Pair<String, AssessmentSystem>> = listOf(
        "neuro" to AssessmentSystem.NEURO,
        "neurological" to AssessmentSystem.NEURO,
        "neurologic" to AssessmentSystem.NEURO,
        "respiratory" to AssessmentSystem.RESPIRATORY,
        "resp" to AssessmentSystem.RESPIRATORY,
        "lungs" to AssessmentSystem.RESPIRATORY,
        "breath sounds" to AssessmentSystem.RESPIRATORY,
        "chest" to AssessmentSystem.RESPIRATORY,
        "cardiovascular" to AssessmentSystem.CARDIOVASCULAR,
        "cardiac" to AssessmentSystem.CARDIOVASCULAR,
        "heart sounds" to AssessmentSystem.CARDIOVASCULAR,
        "circulation" to AssessmentSystem.CARDIOVASCULAR,
        "gi" to AssessmentSystem.GI,
        "gastrointestinal" to AssessmentSystem.GI,
        "abdomen" to AssessmentSystem.GI,
        "abdominal" to AssessmentSystem.GI,
        "bowel sounds" to AssessmentSystem.GI,
        "skin" to AssessmentSystem.SKIN,
        "integumentary" to AssessmentSystem.SKIN,
        "wound" to AssessmentSystem.WOUND,
        "wounds" to AssessmentSystem.WOUND,
        "incision" to AssessmentSystem.WOUND,
        "dressing" to AssessmentSystem.WOUND,
        "mobility" to AssessmentSystem.MOBILITY,
        "musculoskeletal" to AssessmentSystem.MOBILITY,
        "gait" to AssessmentSystem.MOBILITY,
        "ambulation" to AssessmentSystem.MOBILITY,
    )

    // Checked before the normal markers — "not within normal limits" contains
    // "within normal limits".
    private val ABNORMAL_MARKERS = listOf(
        "not within normal limits", "not normal", "abnormal", "not intact",
        "not clear", "impaired",
    )

    private val NORMAL_MARKERS = listOf(
        "within normal limits", "wnl", "unremarkable", "no issues", "no concerns",
        "normal", "intact", "clear",
    )

    private val HEAD_TO_TOE = listOf(
        "head to toe within normal limits", "head to toe wnl", "head to toe normal",
        "head to toe assessment normal", "head to toe assessment within normal limits",
        "all systems normal", "all systems within normal limits",
        "full assessment normal", "everything within normal limits",
    )

    fun match(rawText: String): Reading? {
        val normalized = normalize(rawText)
        if (normalized.isEmpty()) return null

        if (normalized in HEAD_TO_TOE) {
            return Reading(AssessmentSystem.entries.toList(), AssessmentStatus.NORMAL)
        }

        val text = " $normalized "
        val system = firstSystem(text) ?: return null

        for (marker in ABNORMAL_MARKERS) {
            val idx = text.indexOf(" $marker")
            if (idx >= 0) {
                val finding = text.substring(idx + marker.length + 1)
                    .trim().trim(',', '.', '-', ':').takeIf { it.isNotEmpty() }
                return Reading(listOf(system), AssessmentStatus.ABNORMAL, finding)
            }
        }
        for (marker in NORMAL_MARKERS) {
            if (" $marker" in text) return Reading(listOf(system), AssessmentStatus.NORMAL)
        }
        // A system was named but nothing was said about it — not an assessment.
        return null
    }

    private fun firstSystem(paddedText: String): AssessmentSystem? {
        var best: AssessmentSystem? = null
        var bestIndex = Int.MAX_VALUE
        for ((keyword, system) in SYSTEM_KEYWORDS) {
            val idx = paddedText.indexOf(" $keyword ")
            if (idx in 0 until bestIndex) { bestIndex = idx; best = system }
        }
        return best
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
