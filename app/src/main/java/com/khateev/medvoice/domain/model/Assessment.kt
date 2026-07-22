/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain.model

/**
 * Body systems of the routine head-to-toe nursing assessment. Most shift
 * entries simply confirm "within normal limits" — that repetition is what
 * makes assessments one of the biggest flowsheet time sinks.
 */
enum class AssessmentSystem(val display: String, val short: String) {
    NEURO("Neuro", "N"),
    RESPIRATORY("Respiratory", "R"),
    CARDIOVASCULAR("Cardiovascular", "C"),
    GI("GI", "G"),
    SKIN("Skin", "S"),
    WOUND("Wound", "W"),
    MOBILITY("Mobility", "M")
}

enum class AssessmentStatus { NORMAL, ABNORMAL }

/** The latest assessment recorded for one system. */
data class AssessmentResult(
    val status: AssessmentStatus,
    val finding: String?,
    val time: String
)
