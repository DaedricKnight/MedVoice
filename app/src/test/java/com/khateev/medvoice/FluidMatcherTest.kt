/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.FluidMatcher
import com.khateev.medvoice.domain.FluidMatcher.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FluidMatcherTest {

    private fun assertReading(text: String, kind: Kind, category: String, ml: Int) {
        val r = FluidMatcher.match(text)
        assertEquals("kind for \"$text\"", kind, r?.kind)
        assertEquals("category for \"$text\"", category, r?.category)
        assertEquals("amount for \"$text\"", ml, r?.amountMl)
    }

    @Test
    fun outputCategories_areRecognizedWithAmount() {
        assertReading("urine output 300", Kind.OUTPUT, "urine", 300)
        assertReading("drain 50 mils", Kind.OUTPUT, "drain", 50)
        assertReading("vomit 100", Kind.OUTPUT, "emesis", 100)
        assertReading("output 250 ml", Kind.OUTPUT, "output", 250)
    }

    @Test
    fun intakeCategories_areRecognizedWithAmount() {
        assertReading("oral intake 200 ml", Kind.INTAKE, "oral", 200)
        assertReading("iv fluids 500", Kind.INTAKE, "IV", 500)
        assertReading("patient drank 250 ml", Kind.INTAKE, "oral", 250)
    }

    @Test
    fun specificCategoryWins_overGenericOutput() {
        // "urine" appears before "output" — log urine, not the generic output
        assertReading("urine output 400 ml", Kind.OUTPUT, "urine", 400)
    }

    @Test
    fun litresAndThousands_areScaledToMillilitres() {
        assertReading("iv fluids 1.5 liters", Kind.INTAKE, "IV", 1500)
        assertReading("urine output 1 thousand", Kind.OUTPUT, "urine", 1000)
        assertReading("oral intake 2 litres", Kind.INTAKE, "oral", 2000)
    }

    @Test
    fun withoutAnAmount_isNotAnIoLog() {
        // These are review questions / bare mentions — no number, no record
        assertNull(FluidMatcher.match("urine output"))
        assertNull(FluidMatcher.match("fluid balance"))
        assertNull(FluidMatcher.match("how much urine"))
    }

    @Test
    fun medicationOrder_isNotAnIoLog() {
        // "order IV fluids" is a prescription, not a flowsheet entry
        assertNull(FluidMatcher.match("order iv fluids"))
        assertNull(FluidMatcher.match("prescribe iv fluids 500"))
    }

    @Test
    fun phrasesWithoutFluidKeyword_areIgnored() {
        assertNull(FluidMatcher.match("record blood pressure 120 over 80"))
        assertNull(FluidMatcher.match("heart rate 72"))
    }

    @Test
    fun implausiblyLargeAmount_isRejected() {
        assertNull(FluidMatcher.match("urine output 99999"))
    }
}
