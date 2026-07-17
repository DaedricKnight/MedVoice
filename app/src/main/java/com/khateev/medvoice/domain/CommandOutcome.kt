/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.VitalAlerts
import com.khateev.medvoice.VitalType

/**
 * Everything a spoken command can DECIDE, expressed as data. The processor
 * produces outcomes; the ViewModel translates them into side effects (DB
 * writes, TTS, UI state). This closed set of effect types is exhaustive by
 * design — the OPEN set (intents) extends via [IntentRegistry], not here.
 */
sealed interface CommandOutcome {

    /** Empty/blank input — nothing to do. */
    data object Ignored : CommandOutcome

    // --- Critical-action dialog gating ---
    data object ConfirmPending : CommandOutcome
    data object CancelPending : CommandOutcome

    /** A pending dialog is open and the phrase was neither confirm nor cancel. */
    data object AwaitConfirmation : CommandOutcome

    /** Spoken undo shortcut ("scratch that") or emr_undo_last intent. */
    data object UndoRequested : CommandOutcome

    /** Classifier returned null — engine not initialized yet. */
    data object ClassifierNotReady : CommandOutcome

    /** Out-of-domain ("none") or below the confidence threshold: re-prompt. */
    data class Rejected(
        val text: String,
        val label: String,
        val confidence: Float
    ) : CommandOutcome

    /** Critical intent recognized — must be confirmed before execution. */
    data class RequestConfirmation(
        val intent: String,
        val description: String,
        val rawText: String
    ) : CommandOutcome

    /**
     * Confidence landed in the "did you mean" band (below the execute
     * threshold but above the reject floor): offer the guess and execute it
     * only if the user accepts. Non-critical intents only.
     */
    data class SuggestIntent(
        val intent: String,
        val description: String,
        val rawText: String,
        val confidence: Float
    ) : CommandOutcome

    /** A vital parsed successfully; [alert] is non-null for alarming values. */
    data class VitalRecorded(
        val type: VitalType,
        val value: String,
        val logMessage: String,
        val spokenOk: String,
        val alert: VitalAlerts.Alert?,
        val label: String,
        val confidence: Float,
        val rawText: String
    ) : CommandOutcome

    /** Vital intent recognized but no valid value found in the phrase. */
    data class VitalValueMissing(
        val logMessage: String,
        val label: String,
        val confidence: Float,
        val rawText: String
    ) : CommandOutcome

    /** "Open chart for <name>" resolved to a concrete patient. */
    data class SwitchPatient(val patientId: Long) : CommandOutcome

    /**
     * Resolved to a patient who has a look-alike name on the ward — confirm
     * before switching charts (wrong-patient documentation guard).
     */
    data class ConfirmPatientSwitch(
        val patientId: Long,
        val patientName: String,
        val rawText: String
    ) : CommandOutcome

    /** Chart/search intent without a resolvable name — show the picker. */
    data class ShowPatientPicker(
        val label: String,
        val confidence: Float
    ) : CommandOutcome

    /** "Add a new patient" — open the form. */
    data class ShowAddPatient(
        val label: String,
        val confidence: Float,
        val rawText: String
    ) : CommandOutcome

    /** symptom_log_* without a numeric payload (fever, nausea, dizziness). */
    data class SymptomLogged(
        val symptom: String,
        val label: String,
        val confidence: Float
    ) : CommandOutcome

    /** Recognized intent with no dedicated handler — acknowledged and logged. */
    data class GenericExecuted(
        val description: String,
        val label: String,
        val confidence: Float
    ) : CommandOutcome
}

/** True when the outcome opens a modal dialog awaiting a spoken yes/no answer. */
val CommandOutcome.opensPendingDialog: Boolean
    get() = this is CommandOutcome.RequestConfirmation ||
        this is CommandOutcome.SuggestIntent ||
        this is CommandOutcome.ConfirmPatientSwitch

/** Processor result: the outcome plus classifier latency (null on shortcut paths). */
data class Processed(
    val outcome: CommandOutcome,
    val inferenceMs: Long? = null
)

/**
 * Result of batch dictation: outcomes for the processed segments plus how many
 * trailing segments were skipped because a critical command needs confirmation.
 */
data class BatchResult(
    val results: List<Processed>,
    val skipped: Int
)
