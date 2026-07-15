/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

/**
 * Clinical range validation, distinct from the ASR confidence threshold: the
 * model heard correctly and the number parsed, but the VALUE is alarming
 * (SpO₂ 82 is valid input and a critical finding). Such values are still
 * recorded — data must not be lost — but the tile turns red, the audit log
 * gets a warning, and TTS prompts the clinician to say "undo" if it was a slip.
 *
 * Thresholds are broad adult limits for the prototype, not clinical guidance.
 */
object VitalAlerts {

    data class Alert(val message: String)

    fun assess(type: VitalType, value: String): Alert? = when (type) {
        VitalType.BLOOD_PRESSURE -> {
            val parts = value.split("/").mapNotNull { it.toDoubleOrNull() }
            val sys = parts.getOrNull(0)
            val dia = parts.getOrNull(1)
            when {
                sys == null || dia == null -> null
                sys >= 180 || dia >= 110 -> Alert("Blood pressure $value is critically high")
                sys <= 90 || dia <= 50 -> Alert("Blood pressure $value is critically low")
                else -> null
            }
        }
        VitalType.HEART_RATE -> numeric(value) { v ->
            when {
                v > 130 -> Alert("Heart rate ${trim(v)} is critically high")
                v < 40 -> Alert("Heart rate ${trim(v)} is critically low")
                else -> null
            }
        }
        VitalType.OXYGEN_SATURATION -> numeric(value) { v ->
            if (v < 90) Alert("Oxygen saturation ${trim(v)} is critically low") else null
        }
        VitalType.TEMPERATURE -> numeric(value) { v ->
            when {
                v >= 39.5 -> Alert("Temperature ${trim(v)} is critically high")
                v < 35.0 -> Alert("Temperature ${trim(v)} is critically low")
                else -> null
            }
        }
        VitalType.RESPIRATORY_RATE -> numeric(value) { v ->
            when {
                v > 30 -> Alert("Respiratory rate ${trim(v)} is critically high")
                v < 8 -> Alert("Respiratory rate ${trim(v)} is critically low")
                else -> null
            }
        }
        VitalType.PAIN_LEVEL -> numeric(value) { v ->
            if (v >= 9) Alert("Pain level ${trim(v)} is severe") else null
        }
        VitalType.WEIGHT -> null
    }

    private inline fun numeric(value: String, check: (Double) -> Alert?): Alert? =
        value.toDoubleOrNull()?.let(check)

    private fun trim(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}
