/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cleanup of whisper artefacts, tested next to the engine that produces them. */
class TranscriptCleanerTest {

    @Test
    fun `collapses whisper repetition loops`() {
        val collapse = TranscriptCleaner::collapseRepetitions
        assertEquals("Log heart rate 72", collapse("Log heart rate 72 Log heart rate 72"))
        assertEquals("Record blood pressure 120/80", collapse("Record blood pressure 120/80 Record blood pressure 120/80."))
        assertEquals("pain level 7 out of 10", collapse("pain level 7 out of 10"))
        assertEquals("call code blue", collapse("call code blue call code blue call code blue call code blue"))
    }

    @Test
    fun `strips whisper non-speech annotations`() {
        val strip = TranscriptCleaner::stripNonSpeech
        assertEquals("", strip("(speaking in foreign language)"))
        assertEquals("", strip("[BLANK_AUDIO]"))
        assertEquals("", strip("(music)"))
        assertEquals("log heart rate 72", strip("log heart rate 72 (coughs)"))
        assertEquals("record blood pressure 120/80", strip("record blood pressure 120/80"))
    }
}
