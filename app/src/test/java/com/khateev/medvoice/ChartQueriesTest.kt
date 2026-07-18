/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.ChartQueries
import com.khateev.medvoice.domain.ChartQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartQueriesTest {

    @Test
    fun globalQueries_match() {
        assertEquals(ChartQuery.CriticalValues, ChartQueries.match("any critical values"))
        assertEquals(ChartQuery.CriticalValues, ChartQueries.match("Any critical values?"))
        assertEquals(ChartQuery.LatestVitals, ChartQueries.match("latest vitals"))
        assertEquals(ChartQuery.Activity, ChartQueries.match("what's been done"))
        assertEquals(ChartQuery.Summary, ChartQueries.match("catch me up"))
        assertEquals(ChartQuery.Summary, ChartQueries.match("SBAR"))
    }

    @Test
    fun news2Query_matches_bothWordAndNormalizedDigit() {
        assertEquals(ChartQuery.News2, ChartQueries.match("news score"))
        // "news two" is normalized to "news 2" upstream by VitalsExtractor
        assertEquals(ChartQuery.News2, ChartQueries.match("news 2 score"))
    }

    @Test
    fun vitalQueries_matchSynonymsAndTemplates() {
        assertEquals(ChartQuery.Vital(VitalType.BLOOD_PRESSURE), ChartQueries.match("what's the blood pressure"))
        assertEquals(ChartQuery.Vital(VitalType.BLOOD_PRESSURE), ChartQueries.match("latest bp"))
        assertEquals(ChartQuery.Vital(VitalType.HEART_RATE), ChartQueries.match("how's the pulse"))
        assertEquals(ChartQuery.Vital(VitalType.OXYGEN_SATURATION), ChartQueries.match("sats trend"))
        assertEquals(ChartQuery.Vital(VitalType.OXYGEN_SATURATION), ChartQueries.match("read me the oxygen saturation"))
        assertEquals(ChartQuery.Vital(VitalType.TEMPERATURE), ChartQueries.match("current temp"))
        assertEquals(ChartQuery.Vital(VitalType.PAIN_LEVEL), ChartQueries.match("what is the pain score"))
    }

    @Test
    fun noteQueries_matchSectionsAndWholeNote() {
        assertEquals(ChartQuery.Note(NoteSection.PLAN), ChartQueries.match("read the plan"))
        assertEquals(ChartQuery.Note(NoteSection.ASSESSMENT), ChartQueries.match("read the assessment"))
        assertEquals(ChartQuery.Note(null), ChartQueries.match("read the note"))
    }

    @Test
    fun commandsAndUnknownPhrases_doNotMatch() {
        // Real commands must fall through to the classifier, not be read-only queries
        assertNull(ChartQueries.match("record blood pressure 120 over 80"))
        assertNull(ChartQueries.match("prescribe a medication"))
        assertNull(ChartQueries.match("open chart for jane roe"))
        assertNull(ChartQueries.match("the patient plans to walk today"))
        assertNull(ChartQueries.match(""))
    }
}
