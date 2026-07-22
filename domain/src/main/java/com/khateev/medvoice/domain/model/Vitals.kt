/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain.model

/** The vitals and scored symptoms the chart records. */
enum class VitalType(val display: String, val unit: String) {
    BLOOD_PRESSURE("Blood Pressure", "mmHg"),
    HEART_RATE("Heart Rate", "bpm"),
    TEMPERATURE("Temperature", "°C"),
    OXYGEN_SATURATION("SpO₂", "%"),
    RESPIRATORY_RATE("Resp. Rate", "/min"),
    WEIGHT("Weight", "kg"),
    PAIN_LEVEL("Pain Level", "/10")
}

/** A recorded vital: the value as dictated, when it was taken, and whether it alarms. */
data class VitalValue(val value: String, val time: String, val critical: Boolean = false)

/** SOAP clinical note structure; [spoken] is the word used in voice navigation. */
enum class NoteSection(val display: String, val spoken: String) {
    SUBJECTIVE("Subjective", "subjective"),
    OBJECTIVE("Objective", "objective"),
    ASSESSMENT("Assessment", "assessment"),
    PLAN("Plan", "plan")
}

/** Running fluid balance (I/O) totals for a patient. */
data class FluidTotals(val intakeMl: Int, val outputMl: Int, val count: Int) {
    val balanceMl: Int get() = intakeMl - outputMl
}
