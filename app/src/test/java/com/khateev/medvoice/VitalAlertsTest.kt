/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalAlertsTest {

    @Test
    fun `normal values raise no alert`() {
        assertNull(VitalAlerts.assess(VitalType.OXYGEN_SATURATION, "97"))
        assertNull(VitalAlerts.assess(VitalType.HEART_RATE, "72"))
        assertNull(VitalAlerts.assess(VitalType.BLOOD_PRESSURE, "120/80"))
        assertNull(VitalAlerts.assess(VitalType.TEMPERATURE, "36.6"))
        assertNull(VitalAlerts.assess(VitalType.RESPIRATORY_RATE, "16"))
        assertNull(VitalAlerts.assess(VitalType.PAIN_LEVEL, "5"))
    }

    @Test
    fun `critical values raise alerts`() {
        assertTrue(VitalAlerts.assess(VitalType.OXYGEN_SATURATION, "82")!!.message.contains("low"))
        assertTrue(VitalAlerts.assess(VitalType.HEART_RATE, "150")!!.message.contains("high"))
        assertTrue(VitalAlerts.assess(VitalType.HEART_RATE, "35")!!.message.contains("low"))
        assertTrue(VitalAlerts.assess(VitalType.BLOOD_PRESSURE, "190/100")!!.message.contains("high"))
        assertTrue(VitalAlerts.assess(VitalType.BLOOD_PRESSURE, "85/45")!!.message.contains("low"))
        assertTrue(VitalAlerts.assess(VitalType.TEMPERATURE, "40")!!.message.contains("high"))
        assertNotNull(VitalAlerts.assess(VitalType.RESPIRATORY_RATE, "6"))
        assertNotNull(VitalAlerts.assess(VitalType.PAIN_LEVEL, "9"))
    }

    @Test
    fun `weight and unparseable values never alert`() {
        assertNull(VitalAlerts.assess(VitalType.WEIGHT, "300"))
        assertNull(VitalAlerts.assess(VitalType.BLOOD_PRESSURE, "garbage"))
    }
}
