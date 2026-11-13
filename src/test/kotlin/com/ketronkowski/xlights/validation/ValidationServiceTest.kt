package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.fpp.FppApiClient
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledBackupService
import com.ketronkowski.xlights.wled.WledDiscovery
import com.ketronkowski.xlights.wled.dto.WledSegmentPatch
import com.ketronkowski.xlights.wled.dto.WledStatePatch
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
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
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService, mock<FppApiClient>())

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
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService, mock<FppApiClient>())

        val report = buildReport(listOf(controller), listOf(perfectDevice.copy(segments = perfectDevice.segments.drop(1))))

        service.fix(report)

        verify(apiClient, never()).patchState(any(), any())
    }

    @Test
    fun `fixAll backs up the device before rebuilding segments`() {
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService, mock<FppApiClient>())

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
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService, mock<FppApiClient>())

        val report = buildReport(listOf(controller), listOf(perfectDevice))

        service.fixAll(report)

        verify(apiClient, never()).patchState(any(), any())
    }

    // ── channels per pixel ──────────────────────────────────────────────────────

    @Test
    fun `pixel ranges use xLights RGB channels even when WLED reports an RGBW capable device`() {
        // A controller with one RGBW output among RGB ones reports lc=3, which maps to bytesPerPixel=4.
        val parser = mock<XLightsConfigParser>()
        val apiClient = mock<WledApiClient>()
        whenever(parser.parse(any())).thenReturn(listOf(controller))
        whenever(apiClient.fetchDevice(controller.ipAddress!!)).thenReturn(perfectDevice.copy(bytesPerPixel = 4))
        val service = ValidationService(parser, mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())

        val report = service.validate(java.nio.file.Path.of("."), 0, setOf("garage-wled"))

        val cv = report.controllerValidations.first()
        assertTrue(cv.segmentValidations.all { it.status == SegmentStatus.OK })
        assertTrue(cv.totalLedsMatch)
    }

    @Test
    fun `channelsPerPixel is 4 only when every model is RGBW`() {
        val service = ValidationService(mock(), mock(), mock(), mock(), mock<FppApiClient>())
        val rgbw = controller.copy(models = controller.models.map { it.copy(bytesPerNode = 4) })
        val mixed = controller.copy(models = controller.models.mapIndexed { i, m -> if (i == 0) m.copy(bytesPerNode = 4) else m })
        assertEquals(4, service.channelsPerPixel(rgbw))
        assertEquals(3, service.channelsPerPixel(mixed))
        assertEquals(3, service.channelsPerPixel(controller))
    }

    // ── fix() patch planning ────────────────────────────────────────────────────
    // WLED applies a "seg" entry without an "id" to the segment at that entry's
    // array index, so every patch fix() sends must carry an explicit id.

    private fun reportWithOrphans(device: WledDevice): ValidationReport {
        val report = buildReport(listOf(controller), listOf(device))
        val names = controller.models.map { it.name.lowercase() }
        val cv = report.controllerValidations.first()
        val orphans = device.segments.filter { (it.name ?: "").lowercase() !in names }
        return report.copy(controllerValidations = listOf(cv.copy(orphanSegments = orphans)))
    }

    @Test
    fun `fix recycles orphan segments for missing models, then appends with explicit ids`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())
        // Fresh device: WLED's default unnamed per-bus segments, nothing matches xLights yet.
        val device = perfectDevice.copy(segments = listOf(
            WledSegment(0, null, start = 0,   stop = 150, on = true),
            WledSegment(1, null, start = 150, stop = 300, on = true),
        ))

        val errors = service.fix(reportWithOrphans(device))

        assertTrue(errors.isEmpty())
        verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 0, name = "left-icicles",  start = 0,   stop = 100, on = true),
            WledSegmentPatch(id = 1, name = "null-gap-1",    start = 100, stop = 105, on = false),
            WledSegmentPatch(id = 2, name = "right-icicles", start = 105, stop = 205, on = true),
        ))))
        verify(apiClient, org.mockito.kotlin.times(1)).patchState(any(), any())
    }

    @Test
    fun `fix never overwrites an in-sync segment when creating a missing one`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())
        // ids 0 and 2 are in sync and "null-gap-1" is MISSING. An id-less create would
        // land at array index 0 and overwrite "left-icicles".
        val device = perfectDevice.copy(segments = listOf(perfectDevice.segments[0], perfectDevice.segments[2]))

        service.fix(reportWithOrphans(device))

        verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 3, name = "null-gap-1", start = 100, stop = 105, on = false),
        ))))
    }

    @Test
    fun `fix deletes leftover orphans last, highest id first, in a separate request`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())
        val device = perfectDevice.copy(segments = perfectDevice.segments + listOf(
            WledSegment(3, "old-a", start = 0, stop = 10, on = true),
            WledSegment(4, null,    start = 10, stop = 20, on = true),
        ))

        service.fix(reportWithOrphans(device))

        verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 4, stop = 0),
            WledSegmentPatch(id = 3, stop = 0),
        ))))
        verify(apiClient, org.mockito.kotlin.times(1)).patchState(any(), any())
    }

    @Test
    fun `fix turns a null model's segment off when correcting its range`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())
        val badNull = perfectDevice.segments[1].copy(stop = 110, on = true)
        val device = perfectDevice.copy(segments = listOf(perfectDevice.segments[0], badNull, perfectDevice.segments[2]))

        service.fix(reportWithOrphans(device))

        verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 1, start = 100, stop = 105, on = false),
        ))))
    }

    @Test
    fun `fix reports models it cannot create because of the firmware segment limit`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())
        val device = perfectDevice.copy(maxSegments = 2, segments = emptyList())

        val errors = service.fix(reportWithOrphans(device))

        verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 0, name = "left-icicles", start = 0,   stop = 100, on = true),
            WledSegmentPatch(id = 1, name = "null-gap-1",   start = 100, stop = 105, on = false),
        ))))
        assertTrue(errors.getValue("garage-wled").contains("at most 2 segments"))
    }

    @Test
    fun `fix keeps going after one device fails and reports the failure`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())
        val other = controller.copy(name = "porch-wled", ipAddress = "192.168.1.101")
        val failing = perfectDevice.copy(segments = emptyList())
        val healthy = perfectDevice.copy(name = "porch-wled", ipAddress = "192.168.1.101", segments = emptyList())
        whenever(apiClient.patchState(eq(failing.ipAddress), any())).thenThrow(RuntimeException("503 Service Unavailable"))

        val errors = service.fix(buildReport(listOf(controller, other), listOf(failing, healthy)))

        verify(apiClient).patchState(eq(healthy.ipAddress), any())
        assertEquals(mapOf("garage-wled" to "503 Service Unavailable"), errors)
    }

    // ── resetToBusDefaults() ─────────────────────────────────────────────────

    @Test
    fun `resetToBusDefaults creates one segment per bus`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())

        val device = perfectDevice.copy(
            segments = perfectDevice.segments, // has ids 0, 1, 2 — ids 1 and 2 should get deleted in step 2
            busses = listOf(WledBus(start = 0, len = 150), WledBus(start = 150, len = 150)),
        )

        service.resetToBusDefaults(device)

        val order = inOrder(apiClient)
        order.verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 0, start = 0, stop = 300, on = true)
        ))))
        order.verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 1, start = 0, stop = 0),
            WledSegmentPatch(id = 2, start = 0, stop = 0),
        ))))
        order.verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 0, start = 0, stop = 150, on = true)
        ))))
        order.verify(apiClient).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 1, start = 150, stop = 300, on = true)
        ))))
    }

    @Test
    fun `resetToBusDefaults falls back to a single full-range segment when busses are empty`() {
        val apiClient = mock<WledApiClient>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), mock<FppApiClient>())

        val device = perfectDevice.copy(segments = emptyList(), busses = emptyList())

        service.resetToBusDefaults(device)

        // Step 1 (collapse) and step 3 (single fallback segment) both patch the
        // same full-range segment here — assert the call count and payload
        // directly rather than with inOrder, since inOrder verification of two
        // identical invocations doesn't behave as a naive "verify twice" would.
        verify(apiClient, org.mockito.kotlin.times(2)).patchState(eq(device.ipAddress), eq(WledStatePatch(listOf(
            WledSegmentPatch(id = 0, start = 0, stop = 300, on = true)
        ))))
    }

    @Test
    fun `resetToBusDefaults never backs up the device first`() {
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        val service = ValidationService(mock<XLightsConfigParser>(), mock<WledDiscovery>(), apiClient, backupService, mock<FppApiClient>())

        service.resetToBusDefaults(perfectDevice)

        verify(backupService, never()).backup(any())
    }

    // ── FPP show controllers ────────────────────────────────────────────────────

    @Test
    fun `FPP controller is reported as a show controller and never fetched as WLED`() {
        val parser = mock<XLightsConfigParser>()
        val apiClient = mock<WledApiClient>()
        val fppClient = mock<FppApiClient>()
        val fpp = XLightsController("show-player", "10.0.0.5", "DDP", 0, emptyList(), vendor = "FPP")
        whenever(parser.parse(any())).thenReturn(listOf(controller, fpp))
        whenever(apiClient.fetchDevice(controller.ipAddress!!)).thenReturn(perfectDevice)
        whenever(fppClient.checkAccess("show-player", "10.0.0.5"))
            .thenReturn(ShowControllerStatus("show-player", "10.0.0.5", access = ShowControllerAccess.AUTH_REQUIRED))
        val service = ValidationService(parser, mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), fppClient)

        val report = service.validate(java.nio.file.Path.of("."), 0)

        verify(apiClient, never()).fetchDevice("10.0.0.5")
        assertEquals(listOf("garage-wled"), report.controllerValidations.map { it.xLightsController.name })
        assertEquals(ShowControllerAccess.AUTH_REQUIRED, report.showControllers.single().access)
        assertTrue(report.unpairedXlightsControllers.none { it.name == "show-player" })
    }

    @Test
    fun `controller under another vendor that answers like FPP becomes a show controller`() {
        val parser = mock<XLightsConfigParser>()
        val apiClient = mock<WledApiClient>()
        val fppClient = mock<FppApiClient>()
        val cape = XLightsController("pixel-cape", "10.0.0.7", "DDP", 0, emptyList(), vendor = "Kulp")
        whenever(parser.parse(any())).thenReturn(listOf(cape))
        whenever(apiClient.fetchDevice("10.0.0.7")).thenThrow(RuntimeException("404"))
        whenever(fppClient.looksLikeFpp("10.0.0.7")).thenReturn(true)
        whenever(fppClient.checkAccess("pixel-cape", "10.0.0.7"))
            .thenReturn(ShowControllerStatus("pixel-cape", "10.0.0.7", access = ShowControllerAccess.REACHABLE))
        val service = ValidationService(parser, mock<WledDiscovery>(), apiClient, mock<WledBackupService>(), fppClient)

        val report = service.validate(java.nio.file.Path.of("."), 0)

        assertTrue(report.controllerValidations.isEmpty())
        assertEquals("pixel-cape", report.showControllers.single().name)
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
