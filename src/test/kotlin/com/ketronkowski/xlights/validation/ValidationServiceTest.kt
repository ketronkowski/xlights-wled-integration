package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledBackupService
import com.ketronkowski.xlights.wled.WledDiscovery
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ValidationServiceTest {

    private val controller = XLightsController(
        name          = "garage-wled",
        ipAddress     = "192.168.1.100",
        protocol      = "WLED",
        totalChannels = 900,
        models        = listOf(
            XLightsModel("left-icicles", "garage-wled", startChannel = 1,   channelCount = 300, isNull = false),
            XLightsModel("null-gap-1",   "garage-wled", startChannel = 301, channelCount = 15,  isNull = true),
            XLightsModel("right-icicles","garage-wled", startChannel = 316, channelCount = 300, isNull = false),
        )
    )

    private val perfectDevice = WledDevice(
        name           = "garage-wled",
        ipAddress      = "192.168.1.100",
        firmwareVersion = "0.15.0",
        totalLeds      = 300,
        bytesPerPixel  = 3,
        maxSegments    = 32,
        segments       = listOf(
            WledSegment(0, "left-icicles",  start = 0,   stop = 100, on = true),
            WledSegment(1, "null-gap-1",    start = 100, stop = 105, on = false),
            WledSegment(2, "right-icicles", start = 105, stop = 205, on = true),
        )
    )

    @Test
    fun `all OK when WLED matches xLights exactly`() {
        val report = buildReport(listOf(controller), listOf(perfectDevice))
        assertFalse(report.hasErrors)
        val cv = report.controllerValidations.first()
        assertTrue(cv.totalLedsMatch)
        assertTrue(cv.segmentValidations.all { it.status == SegmentStatus.OK })
    }

    @Test
    fun `MISSING when WLED segment name does not match model`() {
        val device = perfectDevice.copy(segments = perfectDevice.segments.drop(1)) // remove first segment
        val report = buildReport(listOf(controller), listOf(device))
        val cv = report.controllerValidations.first()
        val missing = cv.segmentValidations.filter { it.status == SegmentStatus.MISSING }
        assertEquals(1, missing.size)
        assertEquals("left-icicles", missing.first().model.name)
    }

    @Test
    fun `RANGE_MISMATCH when segment pixel range differs`() {
        val wrongSeg = perfectDevice.segments[0].copy(stop = 99) // should be 100
        val device = perfectDevice.copy(segments = listOf(wrongSeg) + perfectDevice.segments.drop(1))
        val report = buildReport(listOf(controller), listOf(device))
        val cv = report.controllerValidations.first()
        val mismatch = cv.segmentValidations.filter { it.status == SegmentStatus.RANGE_MISMATCH }
        assertEquals(1, mismatch.size)
    }

    @Test
    fun `NULL_NOT_OFF when null model segment is on`() {
        val wrongNull = perfectDevice.segments[1].copy(on = true)
        val device = perfectDevice.copy(segments = listOf(perfectDevice.segments[0], wrongNull, perfectDevice.segments[2]))
        val report = buildReport(listOf(controller), listOf(device))
        val cv = report.controllerValidations.first()
        val notOff = cv.segmentValidations.filter { it.status == SegmentStatus.NULL_NOT_OFF }
        assertEquals(1, notOff.size)
        assertEquals("null-gap-1", notOff.first().model.name)
    }

    @Test
    fun `unpaired controller when WLED device name does not match`() {
        val device = perfectDevice.copy(name = "different-name")
        val report = buildReport(listOf(controller), listOf(device))
        assertEquals(1, report.unpairedXlightsControllers.size)
        assertEquals(1, report.unpairedWledDevices.size)
    }

    // ── fix()/fixAll() back up before mutating ──────────────────────────────────

    @Test
    fun `fix backs up the device before patching segments`() {
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService)

        val report = buildReport(listOf(controller), listOf(perfectDevice.copy(segments = perfectDevice.segments.drop(1))))

        service.fix(report)

        val order = inOrder(backupService, apiClient)
        order.verify(backupService).backup(perfectDevice.copy(segments = perfectDevice.segments.drop(1)))
        order.verify(apiClient).patchState(any(), any())
    }

    @Test
    fun `fix skips a device whose backup fails, without patching it`() {
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        whenever(backupService.backup(any())).thenThrow(RuntimeException("connection refused"))
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService)

        val report = buildReport(listOf(controller), listOf(perfectDevice.copy(segments = perfectDevice.segments.drop(1))))

        service.fix(report)

        verify(apiClient, never()).patchState(any(), any())
    }

    @Test
    fun `fixAll backs up the device before rebuilding segments`() {
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService)

        val report = buildReport(listOf(controller), listOf(perfectDevice))

        service.fixAll(report)

        val order = inOrder(backupService, apiClient)
        order.verify(backupService).backup(perfectDevice)
        order.verify(apiClient, atLeastOnce()).patchState(any(), any())
    }

    @Test
    fun `fixAll skips a device whose backup fails, without patching it`() {
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        whenever(backupService.backup(any())).thenThrow(RuntimeException("connection refused"))
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService)

        val report = buildReport(listOf(controller), listOf(perfectDevice))

        service.fixAll(report)

        verify(apiClient, never()).patchState(any(), any())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Minimal inline ValidationService logic for unit tests (no Spring context needed)
    private fun buildReport(
        controllers: List<XLightsController>,
        devices: List<WledDevice>,
    ): ValidationReport {
        val pairedXl   = mutableSetOf<String>()
        val pairedWled = mutableSetOf<String>()
        val cvs        = controllers.map { ctrl ->
            val device = devices.find { it.name.equals(ctrl.name, ignoreCase = true) }
            if (device != null) { pairedXl += ctrl.name; pairedWled += device.name }
            val bpp = device?.bytesPerPixel ?: 3
            val segValidations = ctrl.models.map { model ->
                val ps = (model.startChannel - 1) / bpp
                val pe = ps + model.channelCount / bpp
                val seg = device?.segments?.find { it.name.equals(model.name, ignoreCase = true) }
                val status = when {
                    seg == null                       -> SegmentStatus.MISSING
                    seg.start != ps || seg.stop != pe -> SegmentStatus.RANGE_MISMATCH
                    model.isNull && seg.on            -> SegmentStatus.NULL_NOT_OFF
                    else                              -> SegmentStatus.OK
                }
                SegmentValidation(model, ps, pe, seg, status)
            }
            ControllerValidation(ctrl, device, ctrl.totalChannels / bpp,
                device != null && device.totalLeds == ctrl.totalChannels / bpp,
                segValidations, emptyList())
        }
        return ValidationReport(cvs,
            controllers.filter { it.name !in pairedXl },
            devices.filter { it.name !in pairedWled })
    }
}
