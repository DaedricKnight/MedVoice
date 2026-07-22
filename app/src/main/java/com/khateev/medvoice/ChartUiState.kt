/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.model.FluidTotals
import com.khateev.medvoice.domain.model.News2
import com.khateev.medvoice.domain.model.NoteSection
import com.khateev.medvoice.domain.model.VitalType
import com.khateev.medvoice.domain.model.VitalValue

/**
 * Presentation state for the patient chart. Everything here is shaped for the
 * UI (formatted times, dialog flags) — the clinical model it renders lives in
 * [com.khateev.medvoice.domain.model].
 */

enum class LogKind { OK, WARN, ERROR, INFO }

data class LogEntry(
    val time: String,
    val text: String,
    val intent: String?,
    val confidence: Float?,
    val kind: LogKind
)

/**
 * A modal action awaiting a spoken yes/no. [suggestion] distinguishes a
 * "did you mean …?" guess (executed on confirm) from a critical-intent
 * confirmation (only acknowledged — the prototype never executes those).
 */
data class PendingAction(
    val intent: String,
    val description: String,
    val rawText: String,
    val confidence: Float? = null,
    val suggestion: Boolean = false,
    /** Non-null for a patient-switch confirmation: the chart to open on confirm. */
    val switchToPatientId: Long? = null
)

enum class DictationMode { COMMAND, NOTE }

data class PatientUi(val id: Long, val name: String, val mrn: String, val ward: String)

/** One measurement in the history sheet; BP carries two series (sys/dia). */
data class HistoryPoint(
    val display: String,
    val primary: Double?,
    val secondary: Double?,
    val time: String,
    val critical: Boolean = false
)

data class ChartUiState(
    val modelReady: Boolean = false,
    val listening: Boolean = false,
    val dictationMode: DictationMode = DictationMode.COMMAND,
    val partialText: String = "",
    val notes: Map<NoteSection, String> = emptyMap(),
    val activeSection: NoteSection = NoteSection.SUBJECTIVE,
    val news2: News2.Assessment? = null,
    val patients: List<PatientUi> = emptyList(),
    val currentPatient: PatientUi? = null,
    val showPatientPicker: Boolean = false,
    val showAddPatient: Boolean = false,
    val vitals: Map<VitalType, VitalValue> = emptyMap(),
    val fluids: FluidTotals? = null,
    val actions: List<LogEntry> = emptyList(),
    val pendingAction: PendingAction? = null,
    val historyType: VitalType? = null,
    val historyPoints: List<HistoryPoint> = emptyList(),
    val ttsMuted: Boolean = false,
    val accuracyMode: Boolean = false,
    val switchingModel: Boolean = false,
    val lastInferenceMs: Long = 0,
    /** On-screen read-back of the last spoken chart-review answer (null = hidden). */
    val spokenAnswer: String? = null
)
