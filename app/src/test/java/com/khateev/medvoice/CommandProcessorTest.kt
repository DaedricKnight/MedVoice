/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.ChartQuery
import com.khateev.medvoice.domain.CommandOutcome
import com.khateev.medvoice.domain.CommandProcessor
import com.khateev.medvoice.domain.IntentClassifierPort
import com.khateev.medvoice.domain.IntentResult
import com.khateev.medvoice.ml.PatientMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests of the whole command pipeline with the classifier faked out:
 * every gate (pending confirmation, undo shortcut, confidence, none-class,
 * critical intent) plus registry routing, without a device or an ONNX runtime.
 */
class CommandProcessorTest {

    /** Returns a canned result; records what text the pipeline classified. */
    private class FakeClassifier(
        private val label: String? = null,
        private val confidence: Float = 0.99f
    ) : IntentClassifierPort {
        var lastText: String? = null
        override suspend fun classify(text: String): IntentResult? {
            lastText = text
            return label?.let { IntentResult(it, confidence, "none", 1f - confidence) }
        }
    }

    private val patients = listOf(
        PatientMatcher.Candidate(1, "John Doe"),
        PatientMatcher.Candidate(2, "Jane Roe")
    )

    private fun outcome(
        text: String,
        classifier: FakeClassifier,
        pending: Boolean = false
    ): CommandOutcome = runBlocking {
        CommandProcessor(classifier).process(text, patients, hasPendingAction = pending).outcome
    }

    // --- Input and dialog gates ---

    @Test
    fun blankInput_isIgnored() {
        assertEquals(CommandOutcome.Ignored, outcome("   ", FakeClassifier()))
    }

    @Test
    fun pendingAction_capturesDialog_untilConfirmedOrCancelled() {
        val classifier = FakeClassifier("emrgy_call_code_blue")
        assertEquals(CommandOutcome.ConfirmPending, outcome("yes, confirm", classifier, pending = true))
        assertEquals(CommandOutcome.CancelPending, outcome("negative", classifier, pending = true))
        // Anything else — even a valid command — must not leak through the gate
        assertEquals(CommandOutcome.AwaitConfirmation, outcome("heart rate 72", classifier, pending = true))
        assertNull("classifier must not run while confirmation is pending", classifier.lastText)
    }

    @Test
    fun repeatedConfirm_isStillJustConfirmPending() {
        val classifier = FakeClassifier()
        repeat(5) {
            assertEquals(CommandOutcome.ConfirmPending, outcome("yes confirm", classifier, pending = true))
        }
    }

    @Test
    fun scratchThat_triggersUndo_withoutClassification() {
        val classifier = FakeClassifier("vitals_log_heart_rate")
        assertEquals(CommandOutcome.UndoRequested, outcome("scratch that", classifier))
        assertNull(classifier.lastText)
    }

    @Test
    fun chartReviewQuery_answersWithoutClassification() {
        val classifier = FakeClassifier("vitals_log_heart_rate")
        val result = outcome("any critical values", classifier)
        assertTrue(result is CommandOutcome.AnswerRequest)
        assertEquals(ChartQuery.CriticalValues, (result as CommandOutcome.AnswerRequest).query)
        assertNull("a read-only query must never hit the model", classifier.lastText)
    }

    // --- Classifier gates ---

    @Test
    fun nullClassifierResult_reportsNotReady() {
        assertEquals(CommandOutcome.ClassifierNotReady, outcome("heart rate 72", FakeClassifier(label = null)))
    }

    @Test
    fun belowSuggestionFloor_isRejected() {
        val result = outcome("do the thing", FakeClassifier("vitals_log_heart_rate", confidence = 0.50f))
        assertTrue(result is CommandOutcome.Rejected)
        assertEquals(0.50f, (result as CommandOutcome.Rejected).confidence)
    }

    @Test
    fun noneClass_isRejected_evenAtFullConfidence() {
        val result = outcome("play some music", FakeClassifier("none", confidence = 1.0f))
        assertTrue(result is CommandOutcome.Rejected)
    }

    // --- "Did you mean?" suggestion band ---

    @Test
    fun moderateConfidence_offersSuggestion_insteadOfRejecting() {
        val result = outcome("hard rate seventy two", FakeClassifier("vitals_log_heart_rate", confidence = 0.72f))
        assertTrue(result is CommandOutcome.SuggestIntent)
        result as CommandOutcome.SuggestIntent
        assertEquals("vitals_log_heart_rate", result.intent)
        assertEquals("record heart rate", result.description)
        assertEquals(0.72f, result.confidence)
    }

    @Test
    fun suggestionFloor_isInclusive_andExecuteThresholdExclusive() {
        assertTrue(outcome("x", FakeClassifier("vitals_log_weight", 0.60f)) is CommandOutcome.SuggestIntent)
        assertTrue(outcome("x", FakeClassifier("vitals_log_weight", 0.84f)) is CommandOutcome.SuggestIntent)
        // 0.85 crosses the execute gate — it acts, not suggests
        assertTrue(outcome("weight 80", FakeClassifier("vitals_log_weight", 0.85f)) is CommandOutcome.VitalRecorded)
    }

    @Test
    fun criticalIntentInSuggestionBand_isRejected_notSuggested() {
        // A fuzzy guess must never offer to run a dangerous action.
        val result = outcome("discharge", FakeClassifier("staff_discharge_patient", confidence = 0.72f))
        assertTrue(result is CommandOutcome.Rejected)
    }

    @Test
    fun confirmedSuggestion_runsTheGuessThroughRegistry() = runBlocking {
        val outcome = CommandProcessor(FakeClassifier()).resolveConfirmed(
            "vitals_log_heart_rate", "heart rate 72", 0.72f, patients
        )
        assertTrue(outcome is CommandOutcome.VitalRecorded)
        assertEquals("72", (outcome as CommandOutcome.VitalRecorded).value)
        Unit
    }

    @Test
    fun criticalIntent_requiresConfirmation_insteadOfExecuting() {
        val result = outcome("call code blue", FakeClassifier("emrgy_call_code_blue"))
        assertTrue(result is CommandOutcome.RequestConfirmation)
        result as CommandOutcome.RequestConfirmation
        assertEquals("emrgy_call_code_blue", result.intent)
        assertEquals("CALL CODE BLUE", result.description)
    }

    // --- Vitals routing ---

    @Test
    fun bloodPressure_normalValue_recordsWithoutAlert() {
        val result = outcome("blood pressure 120 over 80", FakeClassifier("vitals_log_blood_pressure"))
        assertTrue(result is CommandOutcome.VitalRecorded)
        result as CommandOutcome.VitalRecorded
        assertEquals(VitalType.BLOOD_PRESSURE, result.type)
        assertEquals("120/80", result.value)
        assertNull("120/80 must not raise a clinical alert", result.alert)
    }

    @Test
    fun spokenNumbers_areNormalizedBeforeExtraction() {
        val result = outcome(
            "blood pressure one twenty over eighty",
            FakeClassifier("vitals_log_blood_pressure")
        )
        assertEquals("120/80", (result as CommandOutcome.VitalRecorded).value)
    }

    @Test
    fun oxygenSaturation_criticalValue_carriesAlert() {
        val result = outcome("oxygen saturation 82 percent", FakeClassifier("vitals_log_oxygen_saturation"))
        assertTrue(result is CommandOutcome.VitalRecorded)
        result as CommandOutcome.VitalRecorded
        assertEquals("82", result.value)
        assertNotNull("SpO2 82 must raise a clinical alert", result.alert)
    }

    @Test
    fun vitalCommandWithoutValue_asksToRepeat() {
        val result = outcome("log the heart rate", FakeClassifier("vitals_log_heart_rate"))
        assertTrue(result is CommandOutcome.VitalValueMissing)
    }

    // --- Patients ---

    @Test
    fun openChartWithKnownName_switchesPatient() {
        val classifier = FakeClassifier("emr_open_patient_chart")
        val result = outcome("open the file for jane roe", classifier)
        assertEquals(CommandOutcome.SwitchPatient(2), result)
        // The dynamic name must be stripped before hitting the model
        assertEquals(false, classifier.lastText!!.contains("jane", ignoreCase = true))
    }

    @Test
    fun openChartWithoutName_showsPicker() {
        val result = outcome("open the patient file", FakeClassifier("emr_open_patient_chart"))
        assertTrue(result is CommandOutcome.ShowPatientPicker)
    }

    @Test
    fun openChartWithLookAlikeOnWard_asksToConfirmInsteadOfSwitching() = runBlocking {
        val roster = listOf(
            PatientMatcher.Candidate(2, "Jane Roe"),
            PatientMatcher.Candidate(5, "Jane Doe")
        )
        val result = CommandProcessor(FakeClassifier("emr_open_patient_chart"))
            .process("open the file for jane roe", roster, hasPendingAction = false).outcome
        assertTrue(result is CommandOutcome.ConfirmPatientSwitch)
        assertEquals(2L, (result as CommandOutcome.ConfirmPatientSwitch).patientId)
        Unit
    }

    @Test
    fun addPatient_opensForm() {
        val result = outcome("add a new patient", FakeClassifier("emr_add_patient"))
        assertTrue(result is CommandOutcome.ShowAddPatient)
    }

    // --- Batch dictation ---

    /** Routes by keyword so one utterance can hit different intents per segment. */
    private class RoutingClassifier(private val route: (String) -> String) : IntentClassifierPort {
        override suspend fun classify(text: String): IntentResult? {
            val label = route(text)
            return IntentResult(label, 0.99f, "none", 0.01f)
        }
    }

    private val vitalsRouter = RoutingClassifier { text ->
        when {
            "blood pressure" in text -> "vitals_log_blood_pressure"
            "heart rate" in text -> "vitals_log_heart_rate"
            "oxygen" in text || "sats" in text -> "vitals_log_oxygen_saturation"
            "code blue" in text -> "emrgy_call_code_blue"
            else -> "none"
        }
    }

    @Test
    fun batchUtterance_recordsEachVitalSeparately() = runBlocking {
        val batch = CommandProcessor(vitalsRouter).processAll(
            "blood pressure 120 over 80, heart rate 72, oxygen saturation 97",
            patients, hasPendingAction = false
        )
        assertEquals(0, batch.skipped)
        val recorded = batch.results.map { it.outcome }.filterIsInstance<CommandOutcome.VitalRecorded>()
        assertEquals(3, recorded.size)
        assertEquals(listOf("120/80", "72", "97"), recorded.map { it.value })
        Unit
    }

    @Test
    fun criticalCommandInBatch_stopsAndReportsSkippedTail() = runBlocking {
        val batch = CommandProcessor(vitalsRouter).processAll(
            "call code blue, heart rate 72, oxygen saturation 97",
            patients, hasPendingAction = false
        )
        assertEquals(1, batch.results.size)
        assertTrue(batch.results[0].outcome is CommandOutcome.RequestConfirmation)
        assertEquals(2, batch.skipped)
        Unit
    }

    @Test
    fun suggestionMidBatch_stopsAndReportsSkippedTail() = runBlocking {
        // First segment records; second is a moderate-confidence guess that
        // opens a "did you mean?" dialog, so the third must wait.
        val classifier = object : IntentClassifierPort {
            override suspend fun classify(text: String): IntentResult =
                if ("heart" in text) IntentResult("vitals_log_heart_rate", 0.99f, "none", 0.01f)
                else IntentResult("vitals_log_weight", 0.72f, "none", 0.28f)
        }
        val batch = CommandProcessor(classifier).processAll(
            "heart rate 72, way too heavy, oxygen saturation 97",
            patients, hasPendingAction = false
        )
        assertEquals(2, batch.results.size)
        assertTrue(batch.results[0].outcome is CommandOutcome.VitalRecorded)
        assertTrue(batch.results[1].outcome is CommandOutcome.SuggestIntent)
        assertEquals(1, batch.skipped)
        Unit
    }

    @Test
    fun pendingConfirmation_disablesSplitting() = runBlocking {
        // "yes, confirm" contains a comma but must be matched whole as the answer
        val batch = CommandProcessor(vitalsRouter).processAll(
            "yes, confirm", patients, hasPendingAction = true
        )
        assertEquals(1, batch.results.size)
        assertEquals(CommandOutcome.ConfirmPending, batch.results[0].outcome)
        Unit
    }

    @Test
    fun singleCommand_isNotAffectedByBatchPath() = runBlocking {
        val batch = CommandProcessor(vitalsRouter).processAll(
            "heart rate 72", patients, hasPendingAction = false
        )
        assertEquals(1, batch.results.size)
        assertEquals("72", (batch.results[0].outcome as CommandOutcome.VitalRecorded).value)
        Unit
    }

    // --- Registry fallbacks ---

    @Test
    fun symptomPrefix_logsSymptomName() {
        val result = outcome("patient reports fever", FakeClassifier("symptom_log_fever"))
        assertEquals("fever", (result as CommandOutcome.SymptomLogged).symptom)
    }

    @Test
    fun unmappedIntent_fallsBackToGenericExecution() {
        val result = outcome("order a chest x-ray", FakeClassifier("lab_order_chest_xray"))
        assertTrue(result is CommandOutcome.GenericExecuted)
        assertEquals("lab_order_chest_xray", (result as CommandOutcome.GenericExecuted).label)
    }
}
