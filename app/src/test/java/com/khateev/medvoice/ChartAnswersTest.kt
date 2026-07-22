/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice
import com.khateev.medvoice.domain.ChartAnswers
import com.khateev.medvoice.domain.model.FluidTotals
import com.khateev.medvoice.domain.model.NoteSection
import com.khateev.medvoice.domain.model.News2
import com.khateev.medvoice.domain.model.VitalValue
import com.khateev.medvoice.domain.model.VitalType

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAnswersTest {

    private fun v(value: String, critical: Boolean = false) = VitalValue(value, "12:00", critical)

    @Test
    fun criticalValues_noVitals_saysSo_neverInvents() {
        val a = ChartAnswers.criticalValues(emptyMap(), null)
        assertEquals("No vitals recorded yet.", a.spoken)
    }

    @Test
    fun criticalValues_allNormal_reportsNoneWithNews2() {
        val vitals = mapOf(
            VitalType.HEART_RATE to v("72"),
            VitalType.OXYGEN_SATURATION to v("98")
        )
        val news2 = News2.Assessment(score = 1, components = 2, redFlag = false, risk = News2.Risk.LOW)
        val a = ChartAnswers.criticalValues(vitals, news2)
        assertEquals("No critical values. NEWS2 is 1, low risk.", a.spoken)
    }

    @Test
    fun criticalValues_flagged_readsThemAndSpeaksBpAsOver() {
        val vitals = mapOf(
            VitalType.OXYGEN_SATURATION to v("88", critical = true),
            VitalType.BLOOD_PRESSURE to v("210/120", critical = true),
            VitalType.HEART_RATE to v("80", critical = false)
        )
        val news2 = News2.Assessment(score = 7, components = 3, redFlag = true, risk = News2.Risk.HIGH)
        val a = ChartAnswers.criticalValues(vitals, news2)
        assertTrue(a.spoken.startsWith("2 critical values:"))
        assertTrue(a.spoken.contains("Oxygen saturation 88"))
        assertTrue("BP must be spoken as 'over'", a.spoken.contains("210 over 120"))
        assertTrue(a.spoken.contains("NEWS2 is 7, emergency response."))
        // heart rate is not flagged — must not appear
        assertTrue(!a.spoken.contains("Heart rate 80"))
    }

    @Test
    fun vital_withPrevious_readsTrend() {
        val a = ChartAnswers.vital(VitalType.OXYGEN_SATURATION, v("89"), previous = "94")
        assertEquals("Oxygen saturation 89, down from 94.", a.spoken)
    }

    @Test
    fun vital_bloodPressure_usesSystolicTrend_andSpokenOver() {
        val a = ChartAnswers.vital(VitalType.BLOOD_PRESSURE, v("150/95"), previous = "130/85")
        assertEquals("Blood Pressure 150 over 95, up from 130 over 85.", a.spoken)
    }

    @Test
    fun vital_missing_saysNoRecord() {
        val a = ChartAnswers.vital(VitalType.WEIGHT, latest = null, previous = null)
        assertEquals("No weight on record.", a.spoken)
    }

    @Test
    fun latestVitals_readsPresentOnesInOrder() {
        val vitals = mapOf(
            VitalType.HEART_RATE to v("72"),
            VitalType.BLOOD_PRESSURE to v("120/80")
        )
        val a = ChartAnswers.latestVitals(vitals)
        // Blood pressure comes before heart rate in VitalType order
        assertEquals("Latest vitals: Blood Pressure 120 over 80, Heart Rate 72.", a.spoken)
    }

    @Test
    fun news2_absent_saysNotEnough() {
        assertEquals("Not enough vitals for a NEWS2 score yet.", ChartAnswers.news2(null).spoken)
    }

    @Test
    fun note_emptySection_saysEmpty_wholeNoteConcatenates() {
        assertEquals(
            "The plan section is empty.",
            ChartAnswers.note(NoteSection.PLAN, emptyMap()).spoken
        )
        val notes = mapOf(
            NoteSection.SUBJECTIVE to "chest pain",
            NoteSection.PLAN to "start antibiotics"
        )
        val whole = ChartAnswers.note(null, notes).spoken
        assertTrue(whole.contains("Subjective. chest pain"))
        assertTrue(whole.contains("Plan. start antibiotics"))
    }

    @Test
    fun activity_empty_saysNone_otherwiseTakesRecentFour() {
        assertEquals("No recent activity on this chart.", ChartAnswers.activity(emptyList()).spoken)
        val a = ChartAnswers.activity(listOf("a", "b", "c", "d", "e"))
        assertEquals("Recently: a; b; c; d.", a.spoken)
    }

    @Test
    fun summary_composesSbarFromRecordedDataOnly() {
        val vitals = mapOf(
            VitalType.BLOOD_PRESSURE to v("148/92"),
            VitalType.OXYGEN_SATURATION to v("94")
        )
        val news2 = News2.Assessment(score = 4, components = 4, redFlag = false, risk = News2.Risk.LOW_MEDIUM)
        val a = ChartAnswers.summary(
            patientName = "Jane Roe", ward = "Ward A, Room 3",
            news2 = news2, vitals = vitals,
            fluids = FluidTotals(intakeMl = 700, outputMl = 300, count = 3),
            plan = "start antibiotics", mostRecentActivity = "pain level 3 recorded"
        )
        assertTrue(a.spoken.startsWith("Jane Roe, Ward A, Room 3."))
        assertTrue(a.spoken.contains("NEWS2 is 4, low to medium risk."))
        assertTrue(a.spoken.contains("148 over 92"))
        assertTrue(a.spoken.contains("Fluid balance net positive 400 millilitres."))
        assertTrue(a.spoken.contains("Plan: start antibiotics."))
        assertTrue(a.spoken.contains("Most recent: pain level 3 recorded."))
    }

    @Test
    fun summary_emptyChart_saysNoData() {
        val a = ChartAnswers.summary("", "", null, emptyMap(), null, null, null)
        assertEquals("No data recorded for this patient yet.", a.spoken)
    }

    @Test
    fun fluidBalance_reportsIntakeOutputAndNet() {
        assertEquals(
            "No fluid intake or output recorded yet.",
            ChartAnswers.fluidBalance(null).spoken
        )
        val positive = ChartAnswers.fluidBalance(FluidTotals(intakeMl = 900, outputMl = 400, count = 4))
        assertEquals(
            "Fluid balance: intake 900 millilitres, output 400 millilitres, net positive 500.",
            positive.spoken
        )
        val negative = ChartAnswers.fluidBalance(FluidTotals(intakeMl = 200, outputMl = 650, count = 3))
        assertTrue(negative.spoken.contains("net negative 450"))
    }
}
