/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.domain.model.VitalType

object IntentDescriptions {

    /** Human-readable summary per intent; generic fallback derived from the label. */
    fun describe(label: String): String = when (label) {
        "emr_open_patient_chart" -> "Patient chart opened"
        "emr_create_clinical_note" -> "New clinical note created"
        "emr_save_clinical_note" -> "Clinical note saved"
        "emr_append_to_note" -> "Appended to clinical note"
        "emr_add_allergy_warning" -> "Allergy warning added to chart"
        "med_prescribe_medication" -> "Prescribe medication"
        "med_stop_medication" -> "Stop medication"
        "med_adjust_dosage" -> "Adjust medication dosage"
        "med_order_iv_fluids" -> "Order IV fluids"
        "med_refill_prescription" -> "Refill prescription"
        "staff_discharge_patient" -> "Discharge patient"
        "staff_transfer_patient_icu" -> "Transfer patient to ICU"
        "staff_transfer_patient_ward" -> "Transfer patient to ward"
        "emr_sign_document" -> "Sign document"
        "ward_unlock_doors" -> "Unlock ward doors"
        "emrgy_call_code_blue" -> "CALL CODE BLUE"
        else -> label
            .replace(Regex("^(emr|emrgy|vitals|symptom|med|lab|calc|ward|or|staff|guide|gen)_"), "")
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
            .let { "Executed: $it" }
    }

    /**
     * Natural imperative phrasing for a "Did you mean: …?" prompt — reads
     * better than [describe]'s "Executed: Log heart rate". Falls back to the
     * de-prefixed description for intents without an explicit phrase.
     */
    fun suggestion(label: String): String = when (label) {
        "vitals_log_blood_pressure" -> "record blood pressure"
        "vitals_log_heart_rate" -> "record heart rate"
        "vitals_log_temperature" -> "record temperature"
        "vitals_log_oxygen_saturation" -> "record oxygen saturation"
        "vitals_log_respiratory_rate" -> "record respiratory rate"
        "vitals_log_weight" -> "record weight"
        "symptom_log_pain_level" -> "record pain level"
        "emr_open_patient_chart" -> "open the patient chart"
        "emr_add_patient" -> "add a new patient"
        "emr_undo_last" -> "undo the last entry"
        else -> describe(label).removePrefix("Executed: ").replaceFirstChar { it.lowercase() }
    }

    /** Speech-friendly vital names ("SpO₂" is unreadable for TTS). */
    fun ttsName(type: VitalType): String = when (type) {
        VitalType.OXYGEN_SATURATION -> "Oxygen saturation"
        VitalType.RESPIRATORY_RATE -> "Respiratory rate"
        else -> type.display
    }
}
