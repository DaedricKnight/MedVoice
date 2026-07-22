/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.UtteranceSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class UtteranceSplitterTest {

    @Test
    fun nurseRound_splitsOnCommas_keepingDecimalsAndBpIntact() {
        assertEquals(
            listOf("BP 120 over 80", "pulse 72", "temp is 36.8", "sats 97"),
            UtteranceSplitter.split("BP 120 over 80, pulse 72, temp is 36.8, sats 97")
        )
    }

    @Test
    fun sentencePeriods_split_butDecimalsDoNot() {
        assertEquals(
            listOf("sats 97", "heart rate 72"),
            UtteranceSplitter.split("sats 97. heart rate 72")
        )
        assertEquals(
            listOf("temperature 36.8"),
            UtteranceSplitter.split("temperature 36.8")
        )
    }

    @Test
    fun singleCommandWithTrailingPeriod_staysWhole() {
        assertEquals(
            listOf("record blood pressure 140 over 90"),
            UtteranceSplitter.split("record blood pressure 140 over 90.")
        )
    }

    @Test
    fun andIsNotASeparator() {
        assertEquals(
            listOf("chest pain and shortness of breath"),
            UtteranceSplitter.split("chest pain and shortness of breath")
        )
    }

    @Test
    fun bpSlashValue_neverSplits() {
        assertEquals(
            listOf("blood pressure 120/80", "pulse 72"),
            UtteranceSplitter.split("blood pressure 120/80, pulse 72")
        )
    }

    @Test
    fun emptyAndBlankSegments_areDropped() {
        assertEquals(
            listOf("pulse 72"),
            UtteranceSplitter.split(" , pulse 72, ")
        )
    }
}
