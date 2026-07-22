/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice
import com.khateev.medvoice.domain.model.News2
import com.khateev.medvoice.domain.model.VitalValue
import com.khateev.medvoice.domain.model.VitalType

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class News2Test {

    private fun vitals(vararg pairs: Pair<VitalType, String>): Map<VitalType, VitalValue> =
        pairs.associate { (type, value) -> type to VitalValue(value, "12:00") }

    @Test
    fun healthyVitals_scoreZero_lowRisk() {
        val a = News2.assess(
            vitals(
                VitalType.RESPIRATORY_RATE to "16",
                VitalType.OXYGEN_SATURATION to "98",
                VitalType.TEMPERATURE to "36.8",
                VitalType.BLOOD_PRESSURE to "120/80",
                VitalType.HEART_RATE to "72"
            )
        )!!
        assertEquals(0, a.score)
        assertEquals(5, a.components)
        assertEquals(News2.Risk.LOW, a.risk)
        assertEquals(false, a.redFlag)
    }

    @Test
    fun deterioratingPatient_mediumRisk() {
        // RR 22 -> 2, SpO2 93 -> 2, temp 38.5 -> 1: aggregate 5 = urgent review
        val a = News2.assess(
            vitals(
                VitalType.RESPIRATORY_RATE to "22",
                VitalType.OXYGEN_SATURATION to "93",
                VitalType.TEMPERATURE to "38.5"
            )
        )!!
        assertEquals(5, a.score)
        assertEquals(News2.Risk.MEDIUM, a.risk)
    }

    @Test
    fun crashingPatient_highRisk() {
        // RR 28 -> 3, SpO2 84 -> 3, BP 85 systolic -> 3, HR 135 -> 3
        val a = News2.assess(
            vitals(
                VitalType.RESPIRATORY_RATE to "28",
                VitalType.OXYGEN_SATURATION to "84",
                VitalType.BLOOD_PRESSURE to "85/60",
                VitalType.HEART_RATE to "135"
            )
        )!!
        assertEquals(12, a.score)
        assertEquals(News2.Risk.HIGH, a.risk)
        assertTrue(a.redFlag)
    }

    @Test
    fun singleRedScore_isLowMediumEvenWithLowTotal() {
        // HR 38 -> 3, everything else normal: total 3 but red flag
        val a = News2.assess(
            vitals(
                VitalType.HEART_RATE to "38",
                VitalType.RESPIRATORY_RATE to "14",
                VitalType.OXYGEN_SATURATION to "97"
            )
        )!!
        assertEquals(3, a.score)
        assertTrue(a.redFlag)
        assertEquals(News2.Risk.LOW_MEDIUM, a.risk)
    }

    @Test
    fun bpUsesSystolicOnly() {
        // Systolic 95 -> 2 regardless of diastolic
        val a = News2.assess(vitals(VitalType.BLOOD_PRESSURE to "95/70"))!!
        assertEquals(2, a.score)
        assertEquals(1, a.components)
    }

    @Test
    fun boundaryValues_matchTheOfficialTable() {
        assertEquals(3, News2.assess(vitals(VitalType.RESPIRATORY_RATE to "8"))!!.score)
        assertEquals(1, News2.assess(vitals(VitalType.RESPIRATORY_RATE to "11"))!!.score)
        assertEquals(0, News2.assess(vitals(VitalType.RESPIRATORY_RATE to "20"))!!.score)
        assertEquals(2, News2.assess(vitals(VitalType.RESPIRATORY_RATE to "21"))!!.score)
        assertEquals(3, News2.assess(vitals(VitalType.OXYGEN_SATURATION to "91"))!!.score)
        assertEquals(2, News2.assess(vitals(VitalType.OXYGEN_SATURATION to "92"))!!.score)
        assertEquals(0, News2.assess(vitals(VitalType.OXYGEN_SATURATION to "96"))!!.score)
        assertEquals(3, News2.assess(vitals(VitalType.TEMPERATURE to "35.0"))!!.score)
        assertEquals(1, News2.assess(vitals(VitalType.TEMPERATURE to "38.5"))!!.score)
        assertEquals(2, News2.assess(vitals(VitalType.TEMPERATURE to "39.1"))!!.score)
        assertEquals(3, News2.assess(vitals(VitalType.BLOOD_PRESSURE to "220/110"))!!.score)
        assertEquals(0, News2.assess(vitals(VitalType.BLOOD_PRESSURE to "219/110"))!!.score)
        assertEquals(1, News2.assess(vitals(VitalType.HEART_RATE to "91"))!!.score)
        assertEquals(2, News2.assess(vitals(VitalType.HEART_RATE to "111"))!!.score)
    }

    @Test
    fun painLevelAlone_doesNotProduceAScore() {
        assertNull(News2.assess(vitals(VitalType.PAIN_LEVEL to "7")))
    }

    @Test
    fun emptyChart_returnsNull() {
        assertNull(News2.assess(emptyMap()))
    }
}
