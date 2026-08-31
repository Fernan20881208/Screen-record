package com.zaid.screenrecorder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsPolicyTest {
    @Test fun fallsBackInRequiredOrder() {
        assertEquals(90, FpsPolicy.fallback(120, setOf(30, 60, 90)))
        assertEquals(60, FpsPolicy.fallback(90, setOf(30, 60, 120)))
        assertEquals(30, FpsPolicy.fallback(120, setOf(30)))
    }

    @Test fun neverInventsUnsupportedFrameRate() {
        assertNull(FpsPolicy.fallback(120, emptySet()))
    }
}
