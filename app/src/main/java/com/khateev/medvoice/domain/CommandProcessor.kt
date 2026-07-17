/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.ml.PatientMatcher
import com.khateev.medvoice.ml.VitalsExtractor
import com.khateev.medvoice.ml.VoiceShortcuts

/**
 * The complete spoken-command pipeline as a pure, JVM-testable unit:
 *
 *   normalize numbers -> confirmation-dialog gate -> undo shortcut ->
 *   sanitize + strip patient names -> classify -> confidence/none gate ->
 *   critical-intent gate -> route via [IntentRegistry]
 *
 * No Android, no side effects: input is text + context, output is a
 * [CommandOutcome] that the caller renders (DB, TTS, UI state).
 */
class CommandProcessor(
    private val classifier: IntentClassifierPort,
    private val registry: IntentRegistry = IntentRegistry.default(),
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val criticalIntents: Set<String> = DEFAULT_CRITICAL_INTENTS
) {

    /**
     * Batch dictation: one utterance may carry several commands separated by
     * whisper's punctuation ("BP 120 over 80, pulse 72, sats 97"). Each
     * segment runs through the full [process] pipeline in order. Processing
     * stops after a [CommandOutcome.RequestConfirmation] — everything behind a
     * critical command must wait until it is confirmed or cancelled — and the
     * skipped tail is reported via [BatchResult.skipped].
     *
     * While a confirmation is pending the utterance is NOT split: it is a
     * yes/no answer, not a command list.
     */
    suspend fun processAll(
        rawText: String,
        patients: List<PatientMatcher.Candidate>,
        hasPendingAction: Boolean
    ): BatchResult {
        if (hasPendingAction) {
            return BatchResult(listOf(process(rawText, patients, hasPendingAction = true)), skipped = 0)
        }
        val segments = UtteranceSplitter.split(rawText)
        if (segments.size <= 1) {
            return BatchResult(listOf(process(rawText, patients, hasPendingAction = false)), skipped = 0)
        }
        val results = mutableListOf<Processed>()
        for ((index, segment) in segments.withIndex()) {
            val processed = process(segment, patients, hasPendingAction = false)
            results += processed
            if (processed.outcome is CommandOutcome.RequestConfirmation) {
                return BatchResult(results, skipped = segments.lastIndex - index)
            }
        }
        return BatchResult(results, skipped = 0)
    }

    suspend fun process(
        rawText: String,
        patients: List<PatientMatcher.Candidate>,
        hasPendingAction: Boolean
    ): Processed {
        // Spoken numbers ("thirty seven point five") are OOD for the intent
        // model; digits are native. Normalize before anything else.
        val text = VitalsExtractor.normalizeNumbers(rawText.trim())
        if (text.isEmpty()) return Processed(CommandOutcome.Ignored)

        // A pending critical action captures the dialog: only confirm/cancel pass.
        if (hasPendingAction) {
            return Processed(
                when {
                    VoiceShortcuts.isConfirm(text) -> CommandOutcome.ConfirmPending
                    VoiceShortcuts.isCancel(text) -> CommandOutcome.CancelPending
                    else -> CommandOutcome.AwaitConfirmation
                }
            )
        }

        if (VoiceShortcuts.isUndo(text)) return Processed(CommandOutcome.UndoRequested)

        // Patient names are dynamic and unknown at training time — strip them
        // before classification, match them from the full text afterwards.
        val classifyText = PatientMatcher.stripNames(
            VitalsExtractor.sanitizeForClassification(text), patients
        )
        val start = System.currentTimeMillis()
        val result = classifier.classify(classifyText)
        val elapsed = System.currentTimeMillis() - start

        if (result == null) return Processed(CommandOutcome.ClassifierNotReady, elapsed)

        // "none" is the explicit out-of-domain class (small talk, background
        // speech) — same outcome as low confidence: re-prompt.
        if (result.label == "none" || result.confidence < confidenceThreshold) {
            return Processed(CommandOutcome.Rejected(text, result.label, result.confidence), elapsed)
        }

        if (result.label in criticalIntents) {
            return Processed(
                CommandOutcome.RequestConfirmation(
                    intent = result.label,
                    description = IntentDescriptions.describe(result.label),
                    rawText = text
                ),
                elapsed
            )
        }

        return Processed(registry.handle(IntentRequest(text, result, patients)), elapsed)
    }

    companion object {
        /**
         * Below this softmax confidence the command is rejected and the user is
         * asked to repeat. On out-of-domain phrases the model's wrong answers
         * cluster at 40-60% confidence while correct ones are at 99-100%.
         */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.85f

        /** Intents with clinical consequences: never executed without confirmation. */
        val DEFAULT_CRITICAL_INTENTS = setOf(
            "med_prescribe_medication", "med_stop_medication", "med_adjust_dosage",
            "med_order_iv_fluids", "med_refill_prescription",
            "staff_discharge_patient", "staff_transfer_patient_icu", "staff_transfer_patient_ward",
            "emr_sign_document", "ward_unlock_doors", "emrgy_call_code_blue"
        )
    }
}
