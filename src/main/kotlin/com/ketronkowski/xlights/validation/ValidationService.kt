package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledDiscovery
import com.ketronkowski.xlights.wled.dto.WledSegmentPatch
import com.ketronkowski.xlights.wled.dto.WledStatePatch
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class ValidationService(
    private val parser: XLightsConfigParser,
    private val discovery: WledDiscovery,
    private val apiClient: WledApiClient,
) {
    private val log = LoggerFactory.getLogger(ValidationService::class.java)

    fun validate(showDir: Path, timeoutSeconds: Int): ValidationReport {
        val xLightsControllers = parser.parse(showDir)
        val wledDevices        = discoverAndFetch(timeoutSeconds)
        return buildReport(xLightsControllers, wledDevices)
    }

    // ── Fix modes ────────────────────────────────────────────────────────────

    fun fix(report: ValidationReport) {
        for (cv in report.controllerValidations) {
            val device = cv.wledDevice ?: continue
            val patches = mutableListOf<WledSegmentPatch>()

            for (sv in cv.segmentValidations) {
                when (sv.status) {
                    SegmentStatus.OK -> Unit

                    SegmentStatus.MISSING -> patches += WledSegmentPatch(
                        name  = sv.model.name,
                        start = sv.expectedPixelStart,
                        stop  = sv.expectedPixelStop,
                        on    = !sv.model.isNull,
                    )

                    SegmentStatus.RANGE_MISMATCH -> patches += WledSegmentPatch(
                        id    = sv.actualSegment!!.id,
                        start = sv.expectedPixelStart,
                        stop  = sv.expectedPixelStop,
                    )

                    SegmentStatus.NULL_NOT_OFF -> patches += WledSegmentPatch(
                        id = sv.actualSegment!!.id,
                        on = false,
                    )
                }
            }

            if (patches.isNotEmpty()) {
                log.info("Patching {} segment(s) on '{}'", patches.size, device.ipAddress)
                apiClient.patchState(device.ipAddress, WledStatePatch(patches))
            }
        }
    }

    fun fixAll(report: ValidationReport) {
        for (cv in report.controllerValidations) {
            val device     = cv.wledDevice ?: continue
            val controller = cv.xLightsController
            if (controller.models.isEmpty()) continue

            log.info("Rebuilding all segments on '{}' ({}) from xLights...", device.name, device.ipAddress)

            // Step 1: collapse WLED to a single segment covering all LEDs
            apiClient.patchState(device.ipAddress, WledStatePatch(listOf(
                WledSegmentPatch(id = 0, start = 0, stop = device.totalLeds, on = true)
            )))

            // Step 2: delete any existing segments beyond index 0
            val extraSegmentIds = device.segments.map { it.id }.filter { it > 0 }
            if (extraSegmentIds.isNotEmpty()) {
                apiClient.patchState(device.ipAddress, WledStatePatch(
                    extraSegmentIds.map { WledSegmentPatch(id = it, start = 0, stop = 0) }
                ))
            }

            // Step 3: create segments in xLights model order
            val bpp = device.bytesPerPixel
            controller.models.forEachIndexed { index, model ->
                val pixelStart = (model.startChannel - 1) / bpp
                val pixelStop  = pixelStart + model.channelCount / bpp
                val patch = WledSegmentPatch(
                    id    = index,
                    name  = model.name,
                    start = pixelStart,
                    stop  = pixelStop,
                    on    = !model.isNull,
                )
                log.info("  [{}] {}  pixels {}-{}  on={}", index, model.name, pixelStart, pixelStop, !model.isNull)
                apiClient.patchState(device.ipAddress, WledStatePatch(listOf(patch)))
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun discoverAndFetch(timeoutSeconds: Int): List<WledDevice> {
        val ips = discovery.discoverDevices(timeoutSeconds)
        return ips.mapNotNull { ip ->
            try {
                apiClient.fetchDevice(ip)
            } catch (e: Exception) {
                log.error("Failed to fetch WLED device at {}: {}", ip, e.message)
                null
            }
        }
    }

    private fun buildReport(
        xLightsControllers: List<XLightsController>,
        wledDevices: List<WledDevice>,
    ): ValidationReport {
        val pairedXlights  = mutableSetOf<String>()
        val pairedWled     = mutableSetOf<String>()
        val validations    = mutableListOf<ControllerValidation>()

        for (controller in xLightsControllers) {
            val device = wledDevices.find { it.name.equals(controller.name, ignoreCase = true) }
            if (device != null) {
                pairedXlights += controller.name
                pairedWled    += device.name
            }

            val bpp          = device?.bytesPerPixel ?: 3
            val expectedLeds = controller.totalChannels / bpp
            val segValidations = controller.models.map { model ->
                validateModel(model, device, bpp)
            }
            val orphans = device?.segments?.filter { seg ->
                controller.models.none { m -> m.name.equals(seg.name ?: "", ignoreCase = true) }
            } ?: emptyList()

            validations += ControllerValidation(
                xLightsController   = controller,
                wledDevice          = device,
                expectedTotalLeds   = expectedLeds,
                totalLedsMatch      = device != null && device.totalLeds == expectedLeds,
                segmentValidations  = segValidations,
                orphanSegments      = orphans,
            )
        }

        return ValidationReport(
            controllerValidations        = validations,
            unpairedXlightsControllers   = xLightsControllers.filter { it.name !in pairedXlights },
            unpairedWledDevices          = wledDevices.filter { it.name !in pairedWled },
        )
    }

    private fun validateModel(model: XLightsModel, device: WledDevice?, bpp: Int): SegmentValidation {
        val pixelStart = (model.startChannel - 1) / bpp
        val pixelStop  = pixelStart + model.channelCount / bpp

        val segment = device?.segments?.find { it.name.equals(model.name, ignoreCase = true) }

        val status = when {
            segment == null                                           -> SegmentStatus.MISSING
            segment.start != pixelStart || segment.stop != pixelStop -> SegmentStatus.RANGE_MISMATCH
            model.isNull && segment.on                               -> SegmentStatus.NULL_NOT_OFF
            else                                                      -> SegmentStatus.OK
        }

        return SegmentValidation(
            model             = model,
            expectedPixelStart = pixelStart,
            expectedPixelStop  = pixelStop,
            actualSegment      = segment,
            status             = status,
        )
    }
}
