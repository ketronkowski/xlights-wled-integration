package com.ketronkowski.xlights.measure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.ketronkowski.xlights.domain.WledBus
import com.ketronkowski.xlights.domain.WledDevice
import com.ketronkowski.xlights.domain.WledSegment
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.dto.WledStateResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Path

class MeasureServiceTest {

    private val ip = "192.168.1.188"

    // Mirrors Octa6: three window ports, one segment per port.
    private val device = WledDevice(
        name = "Octa6", ipAddress = ip, firmwareVersion = "16.0.1",
        totalLeds = 1697, bytesPerPixel = 3, maxSegments = 32,
        segments = listOf(
            WledSegment(0, "garage", 0, 296, true),
            WledSegment(1, "lower", 296, 996, true),
            WledSegment(2, "upper", 996, 1696, true),
        ),
        busses = listOf(WledBus(0, 296), WledBus(296, 700), WledBus(996, 700), WledBus(1696, 1)),
    )

    private lateinit var apiClient: WledApiClient
    private lateinit var service: MeasureService

    @TempDir
    lateinit var dataDir: Path

    @BeforeEach
    fun setUp() {
        apiClient = mock()
        whenever(apiClient.fetchDevice(ip)).thenReturn(device)
        whenever(apiClient.fetchState(ip)).thenReturn(WledStateResponse(on = true, bri = 200))
        service = MeasureService(apiClient, ObjectMapper().registerKotlinModule(), dataDir.toString())
    }

    @Test
    fun `resolves a 1-based port to its bus and covering segment`() {
        val t = MeasureService.resolveTarget(device, 2)
        assertEquals(296, t.busStart)
        assertEquals(700, t.busLen)
        assertEquals(1, t.segmentId)
    }

    @Test
    fun `rejects a port with no single covering segment`() {
        val split = device.copy(segments = listOf(WledSegment(0, "a", 0, 100, true), WledSegment(1, "b", 100, 296, true)))
        assertThrows(IllegalArgumentException::class.java) { MeasureService.resolveTarget(split, 1) }
        assertThrows(IllegalArgumentException::class.java) { MeasureService.resolveTarget(device, 9) }
    }

    @Test
    fun `individual LEDs are offset into the segment and clipped to the port`() {
        // Segment 0 spans the whole strip; port 2 starts 296 pixels into it.
        val wide = MeasureTarget(ip, 2, busStart = 296, busLen = 700, segmentId = 0, segmentStart = 0, segmentStop = 1696)
        val leds = MeasureService.individualLeds(wide, listOf(
            PaintRange(0, 94, "20ff40"),
            PaintRange(690, 800, "FF2020"),   // runs past the end of the port
            PaintRange(5, 5, "FFFFFF"),       // empty, dropped
        ))
        assertEquals(listOf<Any>(296, 996, "000000", 296, 390, "20FF40", 986, 996, "FF2020"), leds)
    }

    @Test
    fun `rejects colors that are not 6 hex digits`() {
        val t = MeasureService.resolveTarget(device, 1)
        assertThrows(IllegalArgumentException::class.java) {
            MeasureService.individualLeds(t, listOf(PaintRange(0, 1, "red")))
        }
    }

    @Test
    fun `start overrides live data and stop restores brightness and unfreezes`() {
        service.start(ip, 3, brightness = 80)
        service.paint(ip, listOf(PaintRange(0, 91, "20FF40")), brightness = null)
        service.stop(ip)

        val bodies = argumentCaptor<Map<String, Any?>>()
        verify(apiClient, times(4)).postState(eq(ip), bodies.capture())
        val (start, paint, unfreeze, restore) = bodies.allValues

        assertEquals(mapOf("on" to true, "bri" to 80, "lor" to 2), start)
        @Suppress("UNCHECKED_CAST")
        val seg = (paint["seg"] as List<Map<String, Any?>>).single()
        assertEquals(2, seg["id"])
        assertEquals(listOf<Any>(0, 700, "000000", 0, 91, "20FF40"), seg["i"])
        assertEquals(mapOf("seg" to listOf(mapOf("id" to 2, "frz" to false))), unfreeze)
        assertEquals(mapOf("on" to true, "bri" to 200, "lor" to 0), restore)
        assertTrue(service.activeSessions().isEmpty())
    }

    @Test
    fun `paint without a session is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { service.paint(ip, emptyList(), null) }
    }

    @Test
    fun `plan falls back to the bundled default and round-trips saves`() {
        assertTrue(service.loadPlan().contains("Octa6"))
        service.savePlan("""{"controllers":[{"name":"X"}]}""")
        assertTrue(service.loadPlan().contains("\"X\""))
        assertTrue(service.resetPlan().contains("Octa6"))
        assertThrows(IllegalArgumentException::class.java) { service.savePlan("not json") }
        assertThrows(IllegalArgumentException::class.java) { service.savePlan("""{"nope":1}""") }
    }
}
