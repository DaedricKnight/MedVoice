/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.ml.PatientMatcher
import com.khateev.medvoice.ml.PatientMatcher.Candidate
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
