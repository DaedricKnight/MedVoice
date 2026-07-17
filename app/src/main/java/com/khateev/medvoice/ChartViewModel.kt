/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.khateev.medvoice.data.AuditEntity
import com.khateev.medvoice.data.ChartDatabase
import com.khateev.medvoice.data.ChartRepository
import com.khateev.medvoice.domain.CommandOutcome
import com.khateev.medvoice.domain.CommandProcessor
import com.khateev.medvoice.ml.MedIntentClassifier
import com.khateev.medvoice.ml.PatientMatcher
import com.khateev.medvoice.speech.WhisperEngine
import com.khateev.medvoice.speech.WhisperInputManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VitalType(val display: String, val unit: String) {
    BLOOD_PRESSURE("Blood Pressure", "mmHg"),
    HEART_RATE("Heart Rate", "bpm"),
    TEMPERATURE("Temperature", "°C"),
    OXYGEN_SATURATION("SpO₂", "%"),
    RESPIRATORY_RATE("Resp. Rate", "/min"),
    WEIGHT("Weight", "kg"),
    PAIN_LEVEL("Pain Level", "/10")
}

data class VitalValue(val value: String, val time: String, val critical: Boolean = false)

enum class LogKind { OK, WARN, ERROR, INFO }

data class LogEntry(
    val time: String,
    val text: String,
    val intent: String?,
    val confidence: Float?,
    val kind: LogKind
)

data class PendingAction(val intent: String, val description: String, val rawText: String)

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
    val noteText: String = "",
    val patients: List<PatientUi> = emptyList(),
    val currentPatient: PatientUi? = null,
    val showPatientPicker: Boolean = false,
    val showAddPatient: Boolean = false,
    val vitals: Map<VitalType, VitalValue> = emptyMap(),
    val actions: List<LogEntry> = emptyList(),
    val pendingAction: PendingAction? = null,
    val historyType: VitalType? = null,
    val historyPoints: List<HistoryPoint> = emptyList(),
    val ttsMuted: Boolean = false,
    val accuracyMode: Boolean = false,
    val switchingModel: Boolean = false,
    val lastInferenceMs: Long = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChartViewModel(application: Application) : AndroidViewModel(application) {

    private val classifier = MedIntentClassifier(application)
    private val commandProcessor = CommandProcessor(classifier)
    private val repository = ChartRepository(ChartDatabase.get(application))
    private val prefs = application.getSharedPreferences("medvoice", Context.MODE_PRIVATE)

    /** -1 until the database is seeded and the last-used patient is restored. */
    private val currentPatientId = MutableStateFlow(-1L)

    private val _uiState = MutableStateFlow(ChartUiState())
    val uiState: StateFlow<ChartUiState> = _uiState

    /** Lives in the ViewModel so the whisper context survives rotation. */
    private val voiceInput = WhisperInputManager(
        context = application,
        onResult = { text -> onDictationResult(text) },
        onStateChange = { listening -> setListening(listening) },
        onStatus = { status -> setPartialText(status) },
        onError = { message -> reportError(message) }
    )

    /** Offline system TTS; the mic is paused while it speaks (echo guard). */
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    companion object {
        private const val PREF_LAST_PATIENT = "last_patient_id"
    }

    init {
        viewModelScope.launch {
            val result = classifier.initialize()
            _uiState.update { it.copy(modelReady = result.isSuccess) }
            if (result.isFailure) {
                addLog("Model failed to load: ${result.exceptionOrNull()?.message}", null, null, LogKind.ERROR)
            } else {
                addLog("On-device model loaded (${classifier.commandCount} intents, INT8, offline)", null, null, LogKind.INFO)
            }
        }
        voiceInput.initialize { result ->
            if (result.isSuccess) {
                reportInfo("Whisper ASR loaded (base.en INT5, offline)")
            } else {
                reportError("Whisper failed to load: ${result.exceptionOrNull()?.message}")
            }
        }

        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = voiceInput.setPaused(true)
                    override fun onDone(utteranceId: String?) = unpauseMicSoon()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = voiceInput.setPaused(false)
                })
                ttsReady = true
            }
        }

        viewModelScope.launch {
            val firstId = repository.seedIfEmpty()
            val saved = prefs.getLong(PREF_LAST_PATIENT, -1L)
            currentPatientId.value = if (saved != -1L) saved else firstId

            launch {
                repository.patients().collect { list ->
                    val patients = list.map { PatientUi(it.id, it.name, it.mrn, it.ward) }
                    _uiState.update { state ->
                        state.copy(
                            patients = patients,
                            currentPatient = patients.find { it.id == currentPatientId.value }
                                ?: state.currentPatient
                        )
                    }
                }
            }
            launch {
                currentPatientId.filter { it != -1L }.flatMapLatest { pid ->
                    repository.latestVitals(pid)
                }.collect { rows ->
                    val vitals = rows.mapNotNull { row ->
                        runCatching { VitalType.valueOf(row.type) }.getOrNull()?.let { type ->
                            type to VitalValue(
                                value = row.value,
                                time = formatTime(row.timestamp),
                                critical = VitalAlerts.assess(type, row.value) != null
                            )
                        }
                    }.toMap()
                    _uiState.update { it.copy(vitals = vitals) }
                }
            }
            launch {
                repository.recentAudit().collect { rows ->
                    val entries = rows.map { row ->
                        LogEntry(
                            time = formatClock(row.timestamp),
                            text = row.message,
                            intent = row.intent,
                            confidence = row.confidence,
                            kind = runCatching { LogKind.valueOf(row.kind) }.getOrDefault(LogKind.INFO)
                        )
                    }
                    _uiState.update { it.copy(actions = entries) }
                }
            }
            launch {
                currentPatientId.filter { it != -1L }.flatMapLatest { pid ->
                    repository.note(pid)
                }.collect { note ->
                    _uiState.update { it.copy(noteText = note?.text ?: "") }
                }
            }
            launch {
                currentPatientId.filter { it != -1L }.collect { pid ->
                    prefs.edit().putLong(PREF_LAST_PATIENT, pid).apply()
                    _uiState.update { state ->
                        state.copy(currentPatient = state.patients.find { it.id == pid }
                            ?: state.currentPatient)
                    }
                }
            }
        }
    }

    // --- Text-to-speech feedback ---

    private fun speak(text: String) {
        if (!ttsReady || _uiState.value.ttsMuted) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "medvoice-${System.nanoTime()}")
    }

    /** Small tail delay so the mic doesn't catch speaker reverb. */
    private fun unpauseMicSoon() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            if (tts?.isSpeaking != true) voiceInput.setPaused(false)
        }
    }

    /** Fast (base.en, ~1 s) vs Accuracy (small.en, 2-4x slower, better on accents). */
    fun toggleAccuracyMode() {
        if (_uiState.value.switchingModel) return
        val target = !_uiState.value.accuracyMode
        _uiState.update { it.copy(switchingModel = true) }
        addLog(
            "Switching ASR to ${if (target) "accuracy model (small.en)" else "fast model (base.en)"}…",
            null, null, LogKind.INFO
        )
        voiceInput.setAccuracyMode(target) { result ->
            _uiState.update { it.copy(switchingModel = false) }
            if (result.isSuccess) {
                _uiState.update { it.copy(accuracyMode = target) }
                reportInfo("ASR model ready: ${if (target) "small.en (accuracy)" else "base.en (fast)"}")
            } else {
                reportError("Model switch failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun toggleTts() {
        val muted = !_uiState.value.ttsMuted
        _uiState.update { it.copy(ttsMuted = muted) }
        if (muted) tts?.stop()
        addLog(if (muted) "Voice feedback muted" else "Voice feedback on", null, null, LogKind.INFO)
    }

    // --- Vital history ---

    fun openHistory(type: VitalType) {
        val pid = currentPatientId.value
        if (pid == -1L) return
        viewModelScope.launch {
            val points = repository.vitalHistory(pid, type.name).map { row ->
                val parts = row.value.split("/")
                HistoryPoint(
                    display = row.value,
                    primary = parts.getOrNull(0)?.toDoubleOrNull(),
                    secondary = if (parts.size > 1) parts[1].toDoubleOrNull() else null,
                    time = formatTime(row.timestamp),
                    critical = VitalAlerts.assess(type, row.value) != null
                )
            }
            _uiState.update { it.copy(historyType = type, historyPoints = points) }
        }
    }

    fun closeHistory() = _uiState.update { it.copy(historyType = null, historyPoints = emptyList()) }

    // --- Undo ---

    private fun undoLastVital() {
        val pid = currentPatientId.value
        if (pid == -1L) return
        viewModelScope.launch {
            val last = repository.lastVital(pid)
            if (last == null) {
                addLog("Nothing to undo", "undo", null, LogKind.WARN)
                speak("Nothing to undo")
            } else {
                repository.deleteVital(last.id)
                val type = runCatching { VitalType.valueOf(last.type) }.getOrNull()
                val message = "Removed ${type?.display ?: last.type}: ${last.value}"
                addLog(message, "undo", null, LogKind.OK)
                speak(message)
            }
        }
    }

    // --- Patient switching ---

    fun openPatientPicker() = _uiState.update { it.copy(showPatientPicker = true) }

    fun dismissPatientPicker() = _uiState.update { it.copy(showPatientPicker = false) }

    fun selectPatient(id: Long) {
        val patient = _uiState.value.patients.find { it.id == id } ?: return
        currentPatientId.value = id
        _uiState.update { it.copy(showPatientPicker = false) }
        addLog("Opened chart: ${patient.name} (${patient.mrn})", null, null, LogKind.INFO)
        speak("Opened chart for ${patient.name}")
    }

    fun openAddPatient() = _uiState.update { it.copy(showAddPatient = true, showPatientPicker = false) }

    fun dismissAddPatient() = _uiState.update { it.copy(showAddPatient = false) }

    fun addPatient(name: String, mrn: String, ward: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            reportError("Patient name is required")
            return
        }
        _uiState.update { it.copy(showAddPatient = false) }
        viewModelScope.launch {
            val id = repository.addPatient(
                name = trimmedName,
                mrn = mrn.trim().ifEmpty { "—" },
                ward = ward.trim().ifEmpty { "—" }
            )
            addLog("Patient added: $trimmedName", null, null, LogKind.OK)
            currentPatientId.value = id
        }
    }

    // --- Dictation ---

    val isListening: Boolean get() = _uiState.value.listening

    /** Called by the activity after the RECORD_AUDIO permission is confirmed. */
    fun startDictation(mode: DictationMode) {
        voiceInput.stopListening()
        _uiState.update { it.copy(dictationMode = mode) }
        val prompt = if (mode == DictationMode.NOTE) {
            WhisperEngine.NOTE_PROMPT
        } else {
            // Bias whisper toward the actual patient names for "open chart for <name>"
            val names = _uiState.value.patients.joinToString(", ") { it.name }
            if (names.isEmpty()) WhisperEngine.MEDICAL_PROMPT
            else WhisperEngine.MEDICAL_PROMPT + " Patients: $names."
        }
        voiceInput.startListening(prompt)
    }

    fun stopDictation() {
        voiceInput.stopListening()
        setPartialText("")
    }

    private fun onDictationResult(text: String) {
        setPartialText("")
        when (_uiState.value.dictationMode) {
            DictationMode.COMMAND -> onCommand(text)
            DictationMode.NOTE -> appendToNote(text)
        }
    }

    private fun appendToNote(text: String) {
        val pid = currentPatientId.value
        if (pid == -1L) return
        val current = _uiState.value.noteText
        val sep = if (current.isEmpty()) "" else " "
        viewModelScope.launch { repository.saveNote(pid, current + sep + text) }
        val words = text.trim().split(Regex("\\s+")).size
        addLog("Appended to clinical note (+$words words)", "note_dictation", null, LogKind.OK)
    }

    fun clearNote() {
        val pid = currentPatientId.value
        if (pid == -1L || _uiState.value.noteText.isEmpty()) return
        viewModelScope.launch { repository.saveNote(pid, "") }
        addLog("Clinical note cleared", null, null, LogKind.INFO)
    }

    fun setListening(listening: Boolean) = _uiState.update { it.copy(listening = listening, partialText = if (listening) it.partialText else "") }

    fun setPartialText(text: String) = _uiState.update { it.copy(partialText = text) }

    fun reportError(message: String) = addLog(message, null, null, LogKind.WARN)

    fun reportInfo(message: String) = addLog(message, null, null, LogKind.INFO)

    // --- Commands ---

    /**
     * All decisions are made by the pure [CommandProcessor]; this method only
     * renders the resulting [CommandOutcome] into side effects (DB, TTS, state).
     */
    fun onCommand(rawText: String) {
        viewModelScope.launch {
            val candidates = _uiState.value.patients.map { PatientMatcher.Candidate(it.id, it.name) }
            val processed = commandProcessor.process(
                rawText = rawText,
                patients = candidates,
                hasPendingAction = _uiState.value.pendingAction != null
            )
            processed.inferenceMs?.let { ms ->
                _uiState.update { it.copy(lastInferenceMs = ms, partialText = "") }
            }
            render(processed.outcome)
        }
    }

    private fun render(outcome: CommandOutcome) {
        when (outcome) {
            CommandOutcome.Ignored -> Unit

            CommandOutcome.ConfirmPending -> confirmPendingAction()
            CommandOutcome.CancelPending -> dismissPendingAction()
            CommandOutcome.AwaitConfirmation -> {
                addLog("Waiting for confirmation — say \"yes confirm\" or \"negative\"", null, null, LogKind.WARN)
                speak("Say yes confirm, or negative")
            }

            CommandOutcome.UndoRequested -> undoLastVital()

            CommandOutcome.ClassifierNotReady ->
                addLog("Model is not ready", null, null, LogKind.ERROR)

            is CommandOutcome.Rejected -> {
                addLog(
                    "Not sure what you meant by \"${outcome.text}\" — please rephrase",
                    outcome.label, outcome.confidence, LogKind.WARN, outcome.text
                )
                speak("Please rephrase")
            }

            is CommandOutcome.RequestConfirmation -> {
                _uiState.update {
                    it.copy(pendingAction = PendingAction(outcome.intent, outcome.description, outcome.rawText))
                }
                // "yes confirm"/"negative" survive accents far better than bare words
                speak("${outcome.description}. Say yes confirm, or negative")
            }

            is CommandOutcome.VitalRecorded -> {
                setVital(outcome.type, outcome.value)
                addLog(outcome.logMessage, outcome.label, outcome.confidence, LogKind.OK, outcome.rawText)
                if (outcome.alert != null) {
                    addLog("⚠ ${outcome.alert.message}", outcome.label, null, LogKind.WARN, outcome.rawText)
                    // "scratch that" is the accent-robust undo phrase (96% vs 7% for "undo")
                    speak("Warning. ${outcome.alert.message}. Say scratch that if this is a mistake")
                } else {
                    speak(outcome.spokenOk)
                }
            }

            is CommandOutcome.VitalValueMissing -> {
                addLog(outcome.logMessage, outcome.label, outcome.confidence, LogKind.WARN, outcome.rawText)
                speak("No valid value, repeat with the number")
            }

            is CommandOutcome.SwitchPatient -> selectPatient(outcome.patientId)

            is CommandOutcome.ShowPatientPicker -> {
                openPatientPicker()
                addLog("Patient list opened", outcome.label, outcome.confidence, LogKind.OK)
            }

            is CommandOutcome.ShowAddPatient -> {
                openAddPatient()
                addLog("New patient form opened", outcome.label, outcome.confidence, LogKind.OK, outcome.rawText)
                speak("Adding a new patient")
            }

            is CommandOutcome.SymptomLogged ->
                addLog("Symptom logged: ${outcome.symptom}", outcome.label, outcome.confidence, LogKind.OK)

            is CommandOutcome.GenericExecuted ->
                addLog(outcome.description, outcome.label, outcome.confidence, LogKind.OK)
        }
    }

    fun confirmPendingAction() {
        val pending = _uiState.value.pendingAction ?: return
        _uiState.update { it.copy(pendingAction = null) }
        addLog("Confirmed: ${pending.description}", pending.intent, null, LogKind.OK, pending.rawText)
        speak("Confirmed")
    }

    fun dismissPendingAction() {
        val pending = _uiState.value.pendingAction ?: return
        _uiState.update { it.copy(pendingAction = null) }
        addLog("Cancelled: ${pending.description}", pending.intent, null, LogKind.INFO, pending.rawText)
        speak("Cancelled")
    }

    private fun setVital(type: VitalType, value: String) {
        val pid = currentPatientId.value
        if (pid == -1L) return
        viewModelScope.launch { repository.recordVital(pid, type.name, value) }
    }

    /** Writes to the persistent audit table; the UI log is a Flow over it. */
    private fun addLog(
        text: String,
        intent: String?,
        confidence: Float?,
        kind: LogKind,
        rawText: String? = null
    ) {
        viewModelScope.launch {
            repository.audit(
                AuditEntity(
                    timestamp = System.currentTimeMillis(),
                    patientId = currentPatientId.value.takeIf { it != -1L },
                    rawText = rawText,
                    intent = intent,
                    confidence = confidence,
                    message = text,
                    kind = kind.name
                )
            )
        }
    }

    private fun formatClock(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(millis))

    override fun onCleared() {
        tts?.shutdown()
        voiceInput.close()
        classifier.close()
    }
}
