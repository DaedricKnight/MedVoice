/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.AssessmentMatcher
import com.khateev.medvoice.domain.model.AssessmentStatus
import com.khateev.medvoice.domain.model.AssessmentSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssessmentMatcherTest {

    private fun assertOne(text: String, system: AssessmentSystem, status: AssessmentStatus) {
        val r = AssessmentMatcher.match(text)
        assertEquals("systems for \"$text\"", listOf(system), r?.systems)
        assertEquals("status for \"$text\"", status, r?.status)
    }

    @Test
    fun normalPhrasings_areRecognizedPerSystem() {
        assertOne("skin within normal limits", AssessmentSystem.SKIN, AssessmentStatus.NORMAL)
        assertOne("neuro wnl", AssessmentSystem.NEURO, AssessmentStatus.NORMAL)
        assertOne("chest clear", AssessmentSystem.RESPIRATORY, AssessmentStatus.NORMAL)
        assertOne("skin intact", AssessmentSystem.SKIN, AssessmentStatus.NORMAL)
        assertOne("abdomen unremarkable", AssessmentSystem.GI, AssessmentStatus.NORMAL)
        assertOne("cardiovascular normal", AssessmentSystem.CARDIOVASCULAR, AssessmentStatus.NORMAL)
        assertOne("wound dressing clear", AssessmentSystem.WOUND, AssessmentStatus.NORMAL)
        assertOne("mobility no issues", AssessmentSystem.MOBILITY, AssessmentStatus.NORMAL)
    }

    @Test
    fun headToToe_marksEverySystemNormal() {
        val r = AssessmentMatcher.match("head to toe within normal limits")
        assertEquals(AssessmentSystem.entries.toList(), r?.systems)
        assertEquals(AssessmentStatus.NORMAL, r?.status)
        assertEquals(7, r?.systems?.size)

        assertEquals(7, AssessmentMatcher.match("all systems normal")?.systems?.size)
        assertEquals(7, AssessmentMatcher.match("head to toe WNL")?.systems?.size)
    }

    @Test
    fun abnormal_capturesTheFinding() {
        val r = AssessmentMatcher.match("skin abnormal redness on the sacrum")
        assertEquals(listOf(AssessmentSystem.SKIN), r?.systems)
        assertEquals(AssessmentStatus.ABNORMAL, r?.status)
        assertEquals("redness on the sacrum", r?.finding)
    }

    @Test
    fun abnormalWithoutDetail_hasNoFinding() {
        val r = AssessmentMatcher.match("neuro abnormal")
        assertEquals(AssessmentStatus.ABNORMAL, r?.status)
        assertNull(r?.finding)
    }

    @Test
    fun negatedNormal_isAbnormal_notNormal() {
        // "not within normal limits" contains "within normal limits" — order matters
        assertOne("skin not within normal limits", AssessmentSystem.SKIN, AssessmentStatus.ABNORMAL)
        assertOne("skin not intact", AssessmentSystem.SKIN, AssessmentStatus.ABNORMAL)
        assertOne("mobility impaired", AssessmentSystem.MOBILITY, AssessmentStatus.ABNORMAL)
    }

    @Test
    fun vitalsAndCommands_areNotMistakenForAssessments() {
        // A status phrase is required — these must reach the classifier instead
        assertNull(AssessmentMatcher.match("respiratory rate 18"))
        assertNull(AssessmentMatcher.match("record blood pressure 120 over 80"))
        assertNull(AssessmentMatcher.match("page the neurologist"))
        assertNull(AssessmentMatcher.match("heart rate 72"))
        // A system named with nothing said about it is not an assessment
        assertNull(AssessmentMatcher.match("skin"))
        assertNull(AssessmentMatcher.match(""))
    }
}
