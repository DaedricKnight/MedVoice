/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.ml.VitalsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VitalsExtractorTest {

    @Test
    fun `normalizes spoken numbers`() {
        assertEquals("120 over 80", VitalsExtractor.normalizeNumbers("one twenty over eighty"))
        assertEquals("98.6", VitalsExtractor.normalizeNumbers("ninety eight point six"))
        assertEquals("125", VitalsExtractor.normalizeNumbers("one hundred twenty five"))
        assertEquals("temperature is 37.5", VitalsExtractor.normalizeNumbers("temperature is thirty seven point five"))
        assertEquals("pain level 0", VitalsExtractor.normalizeNumbers("pain level zero"))
    }

    @Test
    fun `sanitize keeps numbers and decimals but drops separator punctuation`() {
        // v2 model is number-native: digits, decimals, "/" and trailing "." stay
        assertEquals("log temperature 38.5.", VitalsExtractor.sanitizeForClassification("log temperature 38.5."))
        assertEquals("blood pressure 135/85", VitalsExtractor.sanitizeForClassification("blood pressure 135/85"))
        assertEquals("Make me a sandwich please.", VitalsExtractor.sanitizeForClassification("Make me a sandwich, please."))
        assertEquals("Yes confirm", VitalsExtractor.sanitizeForClassification("Yes, confirm!"))
    }

    @Test
    fun `extracts blood pressure from digits`() {
        val bp = VitalsExtractor.extractBloodPressure("record blood pressure 120 over 80")
        assertEquals(120, bp?.systolic)
        assertEquals(80, bp?.diastolic)
    }

    @Test
    fun `extracts blood pressure with slash and without separator`() {
        assertEquals(135, VitalsExtractor.extractBloodPressure("bp 135/85")?.systolic)
        assertEquals(85, VitalsExtractor.extractBloodPressure("bp 135 85")?.diastolic)
    }

    @Test
    fun `extracts blood pressure from spoken words`() {
        val bp = VitalsExtractor.extractBloodPressure("blood pressure one twenty over eighty")
        assertEquals(120, bp?.systolic)
        assertEquals(80, bp?.diastolic)
    }

    @Test
    fun `rejects implausible blood pressure`() {
        assertNull(VitalsExtractor.extractBloodPressure("blood pressure 500 over 300"))
        assertNull(VitalsExtractor.extractBloodPressure("record blood pressure"))
    }

    @Test
    fun `extracts heart rate`() {
        assertEquals(72, VitalsExtractor.extractHeartRate("log heart rate 72"))
        assertEquals(110, VitalsExtractor.extractHeartRate("heart rate one hundred ten"))
        assertNull(VitalsExtractor.extractHeartRate("log heart rate"))
    }

    @Test
    fun `extracts temperature celsius and fahrenheit`() {
        assertEquals(38.5, VitalsExtractor.extractTemperature("temperature 38.5")!!, 0.01)
        assertEquals(37.0, VitalsExtractor.extractTemperature("temp is 98.6 fahrenheit")!!, 0.1)
        assertEquals(37.5, VitalsExtractor.extractTemperature("temperature thirty seven point five")!!, 0.01)
    }

    @Test
    fun `extracts oxygen saturation and respiratory rate`() {
        assertEquals(97, VitalsExtractor.extractOxygenSaturation("oxygen saturation 97 percent"))
        assertEquals(18, VitalsExtractor.extractRespiratoryRate("respiratory rate 18"))
    }

    @Test
    fun `extracts pain level bounded to scale`() {
        assertEquals(7, VitalsExtractor.extractPainLevel("pain level 7 out of 10"))
        assertEquals(0, VitalsExtractor.extractPainLevel("pain level zero"))
    }

    @Test
    fun `extracts weight`() {
        assertEquals(82.0, VitalsExtractor.extractWeight("log weight 82 kilograms")!!, 0.01)
    }

    @Test
    fun `collapses whisper repetition loops`() {
        val collapse = com.khateev.medvoice.speech.TranscriptCleaner::collapseRepetitions
        assertEquals("Log heart rate 72", collapse("Log heart rate 72 Log heart rate 72"))
        assertEquals("Record blood pressure 120/80", collapse("Record blood pressure 120/80 Record blood pressure 120/80."))
        assertEquals("pain level 7 out of 10", collapse("pain level 7 out of 10"))
        assertEquals("call code blue", collapse("call code blue call code blue call code blue call code blue"))
    }

    @Test
    fun `strips whisper non-speech annotations`() {
        val strip = com.khateev.medvoice.speech.TranscriptCleaner::stripNonSpeech
        assertEquals("", strip("(speaking in foreign language)"))
        assertEquals("", strip("[BLANK_AUDIO]"))
        assertEquals("", strip("(music)"))
        assertEquals("log heart rate 72", strip("log heart rate 72 (coughs)"))
        assertEquals("record blood pressure 120/80", strip("record blood pressure 120/80"))
    }
}
