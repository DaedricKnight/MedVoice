/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain.model

/**
 * NEWS2 (National Early Warning Score 2, Royal College of Physicians) computed
 * from the vitals already on the chart: respiratory rate, SpO₂ (Scale 1),
 * temperature, systolic blood pressure and heart rate.
 *
 * Two of the seven official inputs are not captured by voice dictation —
 * consciousness level (ACVPU) and supplemental oxygen — so the score is a
 * lower bound and the tile reports how many components it is based on.
 */
object News2 {

    enum class Risk { LOW, LOW_MEDIUM, MEDIUM, HIGH }

    data class Assessment(
        val score: Int,
        /** How many of the 5 voice-captured components were available. */
        val components: Int,
        /** True when any single component scored 3 (a "red score" in NEWS2). */
        val redFlag: Boolean,
        val risk: Risk
    )

    /** Null when none of the five inputs are on the chart yet. */
    fun assess(vitals: Map<VitalType, VitalValue>): Assessment? {
        val parts = listOfNotNull(
            vitals[VitalType.RESPIRATORY_RATE]?.value?.toDoubleOrNull()?.let(::respiratoryRate),
            vitals[VitalType.OXYGEN_SATURATION]?.value?.toDoubleOrNull()?.let(::oxygenSaturation),
            vitals[VitalType.TEMPERATURE]?.value?.toDoubleOrNull()?.let(::temperature),
            systolicOf(vitals[VitalType.BLOOD_PRESSURE]?.value)?.let(::systolicBp),
            vitals[VitalType.HEART_RATE]?.value?.toDoubleOrNull()?.let(::heartRate)
        )
        if (parts.isEmpty()) return null

        val score = parts.sum()
        val redFlag = parts.any { it == 3 }
        val risk = when {
            score >= 7 -> Risk.HIGH
            score >= 5 -> Risk.MEDIUM
            redFlag -> Risk.LOW_MEDIUM
            else -> Risk.LOW
        }
        return Assessment(score = score, components = parts.size, redFlag = redFlag, risk = risk)
    }

    private fun systolicOf(bp: String?): Double? =
        bp?.substringBefore('/')?.trim()?.toDoubleOrNull()

    private fun respiratoryRate(v: Double): Int = when {
        v <= 8 -> 3
        v <= 11 -> 1
        v <= 20 -> 0
        v <= 24 -> 2
        else -> 3
    }

    private fun oxygenSaturation(v: Double): Int = when {
        v <= 91 -> 3
        v <= 93 -> 2
        v <= 95 -> 1
        else -> 0
    }

    private fun temperature(v: Double): Int = when {
        v <= 35.0 -> 3
        v <= 36.0 -> 1
        v <= 38.0 -> 0
        v <= 39.0 -> 1
        else -> 2
    }

    private fun systolicBp(v: Double): Int = when {
        v <= 90 -> 3
        v <= 100 -> 2
        v <= 110 -> 1
        v <= 219 -> 0
        else -> 3
    }

    private fun heartRate(v: Double): Int = when {
        v <= 40 -> 3
        v <= 50 -> 1
        v <= 90 -> 0
        v <= 110 -> 1
        v <= 130 -> 2
        else -> 3
    }
}
