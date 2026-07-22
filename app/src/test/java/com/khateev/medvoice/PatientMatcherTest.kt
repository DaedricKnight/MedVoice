/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.PatientMatcher
import com.khateev.medvoice.domain.PatientMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatientMatcherTest {

    private val patients = listOf(
        Candidate(1, "John Doe"),
        Candidate(2, "Jane Roe"),
        Candidate(3, "Robert Miles")
    )

    @Test
    fun `matches full name in phrase`() {
        assertEquals(2L, PatientMatcher.match("open patient chart for Jane Roe.", patients)?.id)
        assertEquals(3L, PatientMatcher.match("pull up the chart for robert miles", patients)?.id)
    }

    @Test
    fun `matches unique first name`() {
        assertEquals(2L, PatientMatcher.match("open chart for jane", patients)?.id)
        assertEquals(3L, PatientMatcher.match("open chart for robert", patients)?.id)
    }

    @Test
    fun `tolerates one-letter ASR slip in surname`() {
        // whisper heard "row" instead of "roe"
        assertEquals(2L, PatientMatcher.match("open patient chart for jane row", patients)?.id)
    }

    @Test
    fun `ambiguous or missing name returns null`() {
        val twoJanes = patients + Candidate(4, "Jane Smith")
        assertNull(PatientMatcher.match("open chart for jane", twoJanes))
        assertNull(PatientMatcher.match("open patient chart", patients))
        assertNull(PatientMatcher.match("please show past diagnoses", patients))
    }

    @Test
    fun `full name wins over ambiguous token`() {
        val twoJanes = patients + Candidate(4, "Jane Smith")
        assertEquals(2L, PatientMatcher.match("open chart for jane roe", twoJanes)?.id)
    }

    // --- Wrong-patient guard ---

    private val withLookAlike = patients + Candidate(5, "Jane Doe")

    @Test
    fun `confusable detects one-token look-alike names`() {
        assertEquals(true, PatientMatcher.confusable("Jane Roe", "Jane Doe"))
        assertEquals(true, PatientMatcher.confusable("Jon Doe", "John Doe"))
    }

    @Test
    fun `confusable rejects distinct and identical names`() {
        assertEquals(false, PatientMatcher.confusable("Jane Roe", "John Doe"))
        assertEquals(false, PatientMatcher.confusable("Robert Miles", "Jane Roe"))
        // identical names are duplicates, handled by the picker, not "confusable"
        assertEquals(false, PatientMatcher.confusable("Jane Roe", "Jane Roe"))
    }

    @Test
    fun `resolve flags ambiguity when a look-alike is on the ward`() {
        val match = PatientMatcher.resolve("open patient chart for jane roe", withLookAlike)
        assertEquals(2L, match?.patient?.id)
        assertEquals(true, match?.ambiguous)
    }

    @Test
    fun `resolve is unambiguous when no look-alike exists`() {
        val match = PatientMatcher.resolve("pull up the chart for robert miles", withLookAlike)
        assertEquals(3L, match?.patient?.id)
        assertEquals(false, match?.ambiguous)
    }

    @Test
    fun `resolve returns null when the first name matches two patients`() {
        // "jane" alone can't pick between Jane Roe and Jane Doe — picker, not a guess
        assertNull(PatientMatcher.resolve("open chart for jane", withLookAlike))
    }

    @Test
    fun `stripNames removes known patient tokens only`() {
        assertEquals(
            "open patient chart for",
            PatientMatcher.stripNames("open patient chart for jane roe", patients)
        )
        assertEquals(
            "open chart for",
            PatientMatcher.stripNames("open chart for robert miles", patients)
        )
        // command words must survive
        assertEquals(
            "record blood pressure",
            PatientMatcher.stripNames("record blood pressure", patients)
        )
    }
}
