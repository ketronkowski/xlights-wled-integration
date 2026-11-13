package com.ketronkowski.xlights.look

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.wled.WledApiClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LookServiceTest {

    private val controller = XLightsController(
        name = "Octa5", ipAddress = "192.168.6.25", protocol = "DDP", totalChannels = 615,
        models = listOf(
            XLightsModel("Arch",     "Octa5", startChannel = 1,   channelCount = 300, isNull = false),
            XLightsModel("Null-gap", "Octa5", startChannel = 301, channelCount = 15,  isNull = true),
            XLightsModel("Tree",     "Octa5", startChannel = 316, channelCount = 300, isNull = false),
        ),
    )
    private val device = WledDevice(
        name = "Octa5", ipAddress = "192.168.6.25", firmwareVersion = "0.15.0",
        totalLeds = 205, bytesPerPixel = 3, maxSegments = 32,
        segments = listOf(
            WledSegment(0, "Arch",     0,   100, on = true),
            WledSegment(1, "Null-gap", 100, 105, on = false),
            WledSegment(2, "Tree",     105, 205, on = true),
        ),
    )
    private val inSync = ControllerValidation(
        xLightsController = controller, wledDevice = device, expectedTotalLeds = 205, totalLedsMatch = true,
        segmentValidations = controller.models.mapIndexed { i, m ->
            SegmentValidation(m, device.segments[i].start, device.segments[i].stop, device.segments[i], SegmentStatus.OK)
        },
        orphanSegments = emptyList(),
    )
    private val catalog = ControllerCatalog(
        effects  = listOf("Solid", "Blink", "Chase 3", "RSVD", "Black Hole"),
        palettes = listOf("Default", "* Random Cycle", "* Color 1", "Party", "Ocean"),
        fxData   = listOf("", "!,Duty cycle;!,!;!;01", "!,Size;!,!,!;!", "", "!,!;;;2"),
    )

    private val apiClient = mock<WledApiClient>()
    private val validationService = mock<ValidationService>()
    private val service = LookService(apiClient, validationService, "/tmp/unused")

    private val look = Look(
        on = true, bri = 180, fx = "Chase 3", sx = 10, ix = 20, c1 = 30, c2 = 40, c3 = 99,
        o1 = true, pal = "Party", col = listOf(listOf(255, 0, 0), listOf(0, 0, 300)),
    )

    @Test
    fun `look goes to non-null model segments, null segment is turned off, every segment has an id`() {
        val body = service.buildBody(inSync, catalog, look)

        assertEquals(true, body["on"])
        assertEquals(180, body["bri"])
        assertEquals(0, body["tt"])
        assertEquals(0, body["tb"])
        assertEquals(mapOf("nn" to true), body["udpn"])
        assertFalse(body.containsKey("lor"), "must never override a running show")

        @Suppress("UNCHECKED_CAST")
        val segs = body["seg"] as List<Map<String, Any?>>
        assertEquals(listOf(0, 1, 2), segs.map { it["id"] })
        assertEquals(mapOf("id" to 1, "on" to false), segs[1])
        for (s in listOf(segs[0], segs[2])) {
            assertEquals(true, s["on"])
            assertEquals(2, s["fx"])           // "Chase 3" → this controller's id
            assertEquals(3, s["pal"])          // "Party"
            assertEquals(10, s["sx"])
            assertEquals(31, s["c3"])          // clamped to WLED's 0..31
            assertEquals(true, s["o1"])
            assertEquals(listOf(listOf(255, 0, 0), listOf(0, 0, 255), listOf(0, 0, 0)), s["col"])
        }
    }

    @Test
    fun `segment with no matching model is turned off`() {
        val extra = WledSegment(3, "Leftover", 0, 10, on = true)
        val cv = inSync.copy(wledDevice = device.copy(segments = device.segments + extra))
        @Suppress("UNCHECKED_CAST")
        val segs = service.buildBody(cv, catalog, look)["seg"] as List<Map<String, Any?>>
        assertEquals(mapOf("id" to 3, "on" to false), segs[3])
    }

    @Test
    fun `off sends only power off with no transition and no sync broadcast`() {
        val body = service.buildBody(inSync, null, look.copy(on = false))
        assertEquals(mapOf("on" to false, "tt" to 0, "udpn" to mapOf("nn" to true)), body)
    }

    @Test
    fun `unknown effect name is an error, not a guess`() {
        val e = assertThrows(IllegalStateException::class.java) {
            service.buildBody(inSync, catalog, look.copy(fx = "Not There"))
        }
        assertTrue(e.message!!.contains("Not There"))
    }

    @Test
    fun `not ready when LED count differs, segments drift, or there are orphans`() {
        assertNull(service.notReadyReason(inSync))
        assertTrue(service.notReadyReason(inSync.copy(totalLedsMatch = false))!!.contains("LED count"))
        val drift = inSync.copy(segmentValidations = inSync.segmentValidations.mapIndexed { i, sv ->
            if (i == 0) sv.copy(status = SegmentStatus.RANGE_MISMATCH) else sv })
        assertTrue(service.notReadyReason(drift)!!.contains("Update Selected"))
        val orphan = inSync.copy(orphanSegments = listOf(WledSegment(3, "x", 0, 1, true)))
        assertNotNull(service.notReadyReason(orphan))
        assertNotNull(service.notReadyReason(inSync.copy(wledDevice = null)))
    }

    @Test
    fun `common effects drop RSVD and 2D-only, Solid first then alphabetical`() {
        val other = catalog.copy(effects = listOf("Solid", "Chase 3", "Blink"))
        val names = service.commonEffects(listOf(catalog, other)).map { it.name }
        assertEquals(listOf("Solid", "Blink", "Chase 3"), names)
    }

    @Test
    fun `common palettes keep Default, then specials, then names alphabetically`() {
        assertEquals(listOf("Default", "* Random Cycle", "* Color 1", "Ocean", "Party"),
            service.commonPalettes(listOf(catalog)))
    }

    @Test
    fun `apply rejects a controller that drifted out of sync and posts to the rest`() {
        val drifted = inSync.copy(
            xLightsController = controller.copy(name = "Octa6"),
            wledDevice = device.copy(name = "Octa6", ipAddress = "192.168.6.26"),
            totalLedsMatch = false,
        )
        whenever(validationService.validate(any(), any(), any())).thenReturn(
            ValidationReport(listOf(inSync, drifted), emptyList(), emptyList()))
        whenever(apiClient.fetchEffects("192.168.6.25")).thenReturn(catalog.effects)
        whenever(apiClient.fetchPalettes("192.168.6.25")).thenReturn(catalog.palettes)
        whenever(apiClient.fetchFxData("192.168.6.25")).thenReturn(catalog.fxData)

        val out = service.apply(ApplyLookRequest(listOf(LookTarget("Octa5"), LookTarget("Octa6")), look))

        assertTrue(out[0].success)
        assertFalse(out[1].success)
        assertTrue(out[1].error!!.contains("LED count"))
        verify(apiClient).postState(eq("192.168.6.25"), any())
        verify(apiClient, never()).postState(eq("192.168.6.26"), any())
    }
}
