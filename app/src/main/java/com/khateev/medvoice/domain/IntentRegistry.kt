/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.VitalAlerts
import com.khateev.medvoice.VitalType
import com.khateev.medvoice.ml.PatientMatcher
import com.khateev.medvoice.ml.VitalsExtractor

/** Everything a handler needs to decide an outcome. */
data class IntentRequest(
    val text: String,
    val result: IntentResult,
    val patients: List<PatientMatcher.Candidate>
)

fun interface IntentHandler {
    fun handle(request: IntentRequest): CommandOutcome
}

/**
 * Open-for-extension intent routing: adding a command means registering a
 * handler, not editing a when-block. Resolution order: exact label ->
 * prefix rule -> generic fallback.
 */
class IntentRegistry(
    private val exact: Map<String, IntentHandler>,
    private val prefixes: List<Pair<String, IntentHandler>>,
    private val fallback: IntentHandler
) {
    fun handle(request: IntentRequest): CommandOutcome {
        val label = request.result.label
        val handler = exact[label]
            ?: prefixes.firstOrNull { label.startsWith(it.first) }?.second
            ?: fallback
        return handler.handle(request)
    }

    companion object {
        fun default(): IntentRegistry = IntentRegistry(
            exact = buildMap {
                put("emr_add_patient") { r ->
                    CommandOutcome.ShowAddPatient(r.result.label, r.result.confidence, r.text)
                }
                put("emr_undo_last") { CommandOutcome.UndoRequested }
                val openChart = IntentHandler { r ->
                    val matched = PatientMatcher.match(r.text, r.patients)
                    if (matched != null) CommandOutcome.SwitchPatient(matched.id)
                    else CommandOutcome.ShowPatientPicker(r.result.label, r.result.confidence)
                }
                put("emr_open_patient_chart", openChart)
                put("emr_search_patient_records", openChart)

                put("vitals_log_blood_pressure") { r ->
                    val bp = VitalsExtractor.extractBloodPressure(r.text)
                    if (bp != null) vitalOutcome(
                        r, VitalType.BLOOD_PRESSURE, "${bp.systolic}/${bp.diastolic}",
                        spoken = "Blood pressure ${bp.systolic} over ${bp.diastolic} recorded"
                    ) else CommandOutcome.VitalValueMissing(
                        "Blood pressure command recognized, but no valid value — say e.g. \"blood pressure 120 over 80\"",
                        r.result.label, r.result.confidence, r.text
                    )
                }
                put("vitals_log_heart_rate", numericVital(VitalType.HEART_RATE) {
                    VitalsExtractor.extractHeartRate(it)?.toString()
                })
                put("vitals_log_temperature", numericVital(VitalType.TEMPERATURE) {
                    VitalsExtractor.extractTemperature(it)?.let(::formatNumber)
                })
                put("vitals_log_oxygen_saturation", numericVital(VitalType.OXYGEN_SATURATION) {
                    VitalsExtractor.extractOxygenSaturation(it)?.toString()
                })
                put("vitals_log_respiratory_rate", numericVital(VitalType.RESPIRATORY_RATE) {
                    VitalsExtractor.extractRespiratoryRate(it)?.toString()
                })
                put("vitals_log_weight", numericVital(VitalType.WEIGHT) {
                    VitalsExtractor.extractWeight(it)?.let(::formatNumber)
                })
                put("symptom_log_pain_level") { r ->
                    val pain = VitalsExtractor.extractPainLevel(r.text)
                    if (pain != null) vitalOutcome(
                        r, VitalType.PAIN_LEVEL, pain.toString(),
                        logMessage = "Pain level recorded: $pain/10",
                        spoken = "Pain level $pain recorded"
                    ) else CommandOutcome.VitalValueMissing(
                        "Pain level command recognized, but no value 0-10 found",
                        r.result.label, r.result.confidence, r.text
                    )
                }
            },
            prefixes = listOf(
                "symptom_log_" to IntentHandler { r ->
                    val symptom = r.result.label.removePrefix("symptom_log_").replace('_', ' ')
                    CommandOutcome.SymptomLogged(symptom, r.result.label, r.result.confidence)
                }
            ),
            fallback = IntentHandler { r ->
                CommandOutcome.GenericExecuted(
                    IntentDescriptions.describe(r.result.label),
                    r.result.label, r.result.confidence
                )
            }
        )

        private fun numericVital(type: VitalType, extract: (String) -> String?) =
            IntentHandler { r ->
                val value = extract(r.text)
                if (value != null) vitalOutcome(r, type, value)
                else CommandOutcome.VitalValueMissing(
                    "${type.display} command recognized, but no valid value in \"${r.text}\" — repeat with the number",
                    r.result.label, r.result.confidence, r.text
                )
            }

        private fun vitalOutcome(
            r: IntentRequest,
            type: VitalType,
            value: String,
            logMessage: String = "${type.display} recorded: $value ${type.unit}",
            spoken: String = "${IntentDescriptions.ttsName(type)} $value recorded"
        ) = CommandOutcome.VitalRecorded(
            type = type,
            value = value,
            logMessage = logMessage,
            spokenOk = spoken,
            alert = VitalAlerts.assess(type, value),
            label = r.result.label,
            confidence = r.result.confidence,
            rawText = r.text
        )

        private fun formatNumber(value: Double): String =
            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }
}
