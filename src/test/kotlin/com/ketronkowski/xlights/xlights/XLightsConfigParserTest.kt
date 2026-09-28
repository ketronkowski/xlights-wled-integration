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
        // Protocol in xLights is the network protocol (DDP), Vendor is WLED
        assertEquals("DDP", garage.protocol)
        assertEquals("wled-garage.local", garage.ipAddress)
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
    fun `detects null gap models by LayoutGroup Nulls`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        val nullModels = garage.models.filter { it.isNull }
        assertEquals(2, nullModels.size)
        // Fixture uses LayoutGroup="Nulls" as the canonical marker
        assertTrue(nullModels.all { it.name.contains("null", ignoreCase = true) })
    }

    @Test
    fun `computes channel count from NumStrings and NodesPerString`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        val icicles = garage.models.find { it.name == "left-icicles" }!!
        // NumStrings=1 × NodesPerString=100 × 3 bytes = 300
        assertEquals(300, icicles.channelCount)
    }

    @Test
    fun `parses StartChannel in xLights controller-relative format`() {
        val controllers = parser.parse(showDir)
        val garage = controllers.find { it.name == "garage-wled" }!!
        val icicles = garage.models.find { it.name == "left-icicles" }!!
        assertEquals(1, icicles.startChannel)  // !garage-wled:1 → 1

        val gap = garage.models.find { it.name == "null-gap-1" }!!
        assertEquals(301, gap.startChannel)    // !garage-wled:301 → 301
    }
}
