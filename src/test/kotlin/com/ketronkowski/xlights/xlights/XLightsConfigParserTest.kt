package com.ketronkowski.xlights.xlights

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class XLightsConfigParserTest {

    private val parser = XLightsConfigParser()
    private val showDir = Paths.get(
        requireNotNull(javaClass.classLoader.getResource("xlights")) { "Test fixture directory not found" }.toURI()
    )

    @Test
    fun `parses two controllers from networks xml`() {
        val controllers = parser.parse(showDir)
        assertEquals(2, controllers.size)
        val garage = controllers.find { it.name == "garage-wled" }!!
        assertEquals("WLED", garage.protocol)
        assertEquals("192.168.1.100", garage.ipAddress)
        assertEquals(900, garage.totalChannels)
    }

    @Test
    fun `assigns models to correct controllers`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        assertEquals(5, garage.models.size)

        val roofline = controllers.find { it.name == "roofline-wled" }!!
        assertEquals(2, roofline.models.size)
    }

    @Test
    fun `models sorted by start channel`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        val starts = garage.models.map { it.startChannel }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `detects null gap models by name`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        val nullModels = garage.models.filter { it.isNull }
        assertEquals(2, nullModels.size)
        assertTrue(nullModels.all { it.name.contains("null", ignoreCase = true) })
    }

    @Test
    fun `computes channel count from parm1`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        val icicles = garage.models.find { it.name == "left-icicles" }!!
        // 1 string × 100 nodes × 3 bytes = 300
        assertEquals(300, icicles.channelCount)
    }
}
