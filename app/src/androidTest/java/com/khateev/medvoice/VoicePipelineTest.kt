/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khateev.medvoice.domain.CommandProcessor
import com.khateev.medvoice.ml.MedIntentClassifier
import com.khateev.medvoice.domain.VitalsExtractor
import com.khateev.medvoice.speech.SileroVad
import com.khateev.medvoice.speech.UtteranceSegmenter
import com.khateev.medvoice.speech.WhisperEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * End-to-end voice pipeline test without a human speaker.
 *
 * Test WAVs are synthesized on the host with macOS TTS (`say`, 16 kHz mono
 * PCM16) and bundled as androidTest assets. Each file runs through the exact
 * production chain — Silero VAD segmentation -> whisper.cpp transcription ->
 * number normalization/stripping -> BERT intent classification — bypassing
 * only AudioRecord itself.
 */
@RunWith(AndroidJUnit4::class)
class VoicePipelineTest {

    companion object {
        private lateinit var whisper: WhisperEngine
        private lateinit var classifier: MedIntentClassifier

        @BeforeClass
        @JvmStatic
        fun setUp(): Unit = runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            whisper = WhisperEngine(context)
            check(whisper.initialize().isSuccess) { "whisper init failed" }
            classifier = MedIntentClassifier(context)
            check(classifier.initialize().isSuccess) { "classifier init failed" }
        }

        /** Minimal WAV reader for the known test fixtures (16 kHz mono PCM16). */
        private fun loadWavPcm(assetPath: String): FloatArray {
            val testContext = InstrumentationRegistry.getInstrumentation().context
            val bytes = testContext.assets.open(assetPath).readBytes()
            val dataOffset = findDataChunk(bytes)
            val sampleCount = (bytes.size - dataOffset) / 2
            val pcm = FloatArray(sampleCount)
            var i = dataOffset
            for (s in 0 until sampleCount) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                pcm[s] = ((hi shl 8) or lo) / 32768f
                i += 2
            }
            return pcm
        }

        private fun findDataChunk(bytes: ByteArray): Int {
            var pos = 12 // skip RIFF header
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = DataInputStream(ByteArrayInputStream(bytes, pos + 4, 4)).let {
                    val b = ByteArray(4); it.readFully(b)
                    ((b[3].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
                            ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
                }
                if (id == "data") return pos + 8
                pos += 8 + size + (size and 1)
            }
            error("no data chunk in WAV")
        }
    }

    private fun transcribeAndClassify(wav: String): Pair<String, com.khateev.medvoice.domain.IntentResult> =
        runBlocking {
            val text = whisper.transcribe(loadWavPcm("tts/$wav"))
            assertTrue("empty transcript for $wav", text.isNotBlank())
            val normalized = VitalsExtractor.normalizeNumbers(text)
            val result = classifier.classify(VitalsExtractor.sanitizeForClassification(normalized))
            assertNotNull("no classification for $wav (text=\"$text\")", result)
            normalized to result!!
        }

    private fun assertIntent(wav: String, expectedIntent: String): String {
        val (text, result) = transcribeAndClassify(wav)
        assertEquals("wav=$wav text=\"$text\"", expectedIntent, result.label)
        assertTrue(
            "low confidence ${result.confidence} for $wav text=\"$text\"",
            result.confidence >= CommandProcessor.DEFAULT_CONFIDENCE_THRESHOLD
        )
        return text
    }

    @Test
    fun bloodPressure_isRecognizedWithValue() {
        val text = assertIntent("bp_samantha.wav", "vitals_log_blood_pressure")
        val bp = VitalsExtractor.extractBloodPressure(text)
        assertNotNull("no BP value in \"$text\"", bp)
        assertEquals(120, bp!!.systolic)
        assertEquals(80, bp.diastolic)
    }

    @Test
    fun heartRate_isRecognizedWithValue() {
        val text = assertIntent("hr_samantha.wav", "vitals_log_heart_rate")
        assertEquals(72, VitalsExtractor.extractHeartRate(text))
    }

    @Test
    fun temperature_isRecognizedWithValue() {
        val text = assertIntent("temp_samantha.wav", "vitals_log_temperature")
        assertEquals(38.5, VitalsExtractor.extractTemperature(text)!!, 0.01)
    }

    @Test
    fun oxygenSaturation_isRecognizedWithValue() {
        val text = assertIntent("spo2_samantha.wav", "vitals_log_oxygen_saturation")
        assertEquals(97, VitalsExtractor.extractOxygenSaturation(text))
    }

    @Test
    fun painLevel_isRecognizedWithValue() {
        val text = assertIntent("pain_samantha.wav", "symptom_log_pain_level")
        assertEquals(7, VitalsExtractor.extractPainLevel(text))
    }

    @Test
    fun weight_isRecognizedWithValue() {
        val text = assertIntent("weight_samantha.wav", "vitals_log_weight")
        assertEquals(82.0, VitalsExtractor.extractWeight(text)!!, 0.01)
    }

    @Test
    fun respiratoryRate_isRecognizedWithValue() {
        val text = assertIntent("rr_samantha.wav", "vitals_log_respiratory_rate")
        assertEquals(18, VitalsExtractor.extractRespiratoryRate(text))
    }

    @Test
    fun criticalCommands_areRecognized_secondVoice() {
        assertIntent("discharge_daniel.wav", "staff_discharge_patient")
        assertIntent("codeblue_daniel.wav", "emrgy_call_code_blue")
    }

    @Test
    fun v2Intents_switchAddUndo_areRecognized() {
        assertIntent("switchpatient_daniel.wav", "emr_open_patient_chart")
        assertIntent("addpatient_samantha.wav", "emr_add_patient")
        assertIntent("undolast_daniel.wav", "emr_undo_last")
    }

    @Test
    fun outOfDomainPhrase_isRejectedByNoneClassOrThreshold() {
        val (text, result) = transcribeAndClassify("ood_daniel.wav")
        assertTrue(
            "\"$text\" -> ${result.label} at ${result.confidence} would trigger a false action",
            result.label == "none" || result.confidence < CommandProcessor.DEFAULT_CONFIDENCE_THRESHOLD
        )
    }

    @Test
    fun openChartByName_selectsPatientThroughFullPipeline() {
        val patients = listOf(
            com.khateev.medvoice.domain.PatientMatcher.Candidate(1, "John Doe"),
            com.khateev.medvoice.domain.PatientMatcher.Candidate(2, "Jane Roe"),
            com.khateev.medvoice.domain.PatientMatcher.Candidate(3, "Robert Miles")
        )
        val prompt = WhisperEngine.MEDICAL_PROMPT + " Patients: John Doe, Jane Roe, Robert Miles."
        val text = runBlocking {
            whisper.transcribe(loadWavPcm("tts/openchart_samantha.wav"), prompt)
        }
        val normalized = VitalsExtractor.normalizeNumbers(text)
        val classifyText = com.khateev.medvoice.domain.PatientMatcher.stripNames(
            VitalsExtractor.sanitizeForClassification(normalized), patients
        )
        val result = runBlocking { classifier.classify(classifyText) }
        assertEquals("text=\"$text\"", "emr_open_patient_chart", result?.label)
        assertTrue(
            "low confidence ${result?.confidence} for \"$classifyText\"",
            result!!.confidence >= CommandProcessor.DEFAULT_CONFIDENCE_THRESHOLD
        )
        val matched = com.khateev.medvoice.domain.PatientMatcher.match(normalized, patients)
        assertEquals("text=\"$text\"", 2L, matched?.id)
    }

    @Test
    fun noteDictation_transcribesFreeTextWithNotePrompt() {
        val text = runBlocking {
            whisper.transcribe(loadWavPcm("tts/note_samantha.wav"), WhisperEngine.NOTE_PROMPT)
        }
        val normalized = text.lowercase()
        for (keyword in listOf("chest pain", "left arm", "shortness of breath", "two hours")) {
            assertTrue("\"$text\" is missing \"$keyword\"", normalized.contains(keyword))
        }
    }

    @Test
    fun vadSegmenter_extractsUtteranceFromSilencePaddedAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speech = loadWavPcm("tts/bp_samantha.wav")
        val silence = FloatArray(WhisperEngine.SAMPLE_RATE) // 1 s
        val stream = silence + speech + silence

        val utterances = mutableListOf<FloatArray>()
        val vad = SileroVad(context)
        val segmenter = UtteranceSegmenter(vad, onUtterance = { utterances.add(it) })

        var i = 0
        val chunk = FloatArray(SileroVad.CHUNK)
        while (i + SileroVad.CHUNK <= stream.size) {
            stream.copyInto(chunk, 0, i, i + SileroVad.CHUNK)
            segmenter.accept(chunk)
            i += SileroVad.CHUNK
        }
        segmenter.finish()
        vad.close()

        assertEquals("expected exactly one utterance", 1, utterances.size)
        val utterance = utterances[0]
        // Must contain the speech plus pre-roll/end-silence margins, not the whole stream
        assertTrue("utterance too short: ${utterance.size}", utterance.size >= speech.size)
        assertTrue("utterance did not end on silence", utterance.size < stream.size - WhisperEngine.SAMPLE_RATE / 2)

        // And the segmented audio must still transcribe to the right command
        val text = runBlocking { whisper.transcribe(utterance) }
        val result = runBlocking {
            classifier.classify(VitalsExtractor.sanitizeForClassification(VitalsExtractor.normalizeNumbers(text)))
        }
        assertEquals("segmented text=\"$text\"", "vitals_log_blood_pressure", result?.label)
    }
}
