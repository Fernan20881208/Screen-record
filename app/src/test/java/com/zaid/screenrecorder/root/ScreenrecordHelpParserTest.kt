package com.zaid.screenrecorder.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenrecordHelpParserTest {
    @Test fun doesNotAssumeFpsOrCodecFlags() {
        val caps = ScreenrecordHelpParser.parse("Usage: screenrecord [--size WIDTHxHEIGHT] [--bit-rate RATE] file")
        assertNull(caps.frameRateFlag)
        assertNull(caps.codecFlag)
        assertEquals("--bit-rate", caps.bitRateFlag)
    }

    @Test fun detectsVendorFlags() {
        val caps = ScreenrecordHelpParser.parse("--frame-rate FPS --size WIDTHxHEIGHT --codec avc|hevc")
        assertEquals("--frame-rate", caps.frameRateFlag)
        assertEquals("--codec", caps.codecFlag)
    }
}
