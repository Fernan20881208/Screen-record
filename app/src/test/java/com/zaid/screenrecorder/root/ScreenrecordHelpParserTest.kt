package com.zaid.screenrecorder.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenrecordHelpParserTest {
    @Test fun doesNotAssumeFpsFlag() {
        val caps = ScreenrecordHelpParser.parse("Usage: screenrecord [--size WIDTHxHEIGHT] [--bit-rate RATE] file")
        assertNull(caps.frameRateFlag)
    }

    @Test fun detectsVendorFrameRateFlag() {
        val caps = ScreenrecordHelpParser.parse("--frame-rate FPS --size WIDTHxHEIGHT")
        assertEquals("--frame-rate", caps.frameRateFlag)
    }
}
