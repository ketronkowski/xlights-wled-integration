package com.ketronkowski.xlights.validation

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledBackupService
import com.ketronkowski.xlights.wled.WledDiscovery
import com.ketronkowski.xlights.wled.dto.WledSegmentPatch
import com.ketronkowski.xlights.wled.dto.WledStatePatch
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Service
class ValidationService(
    private val parser: XLightsConfigParser,
    private val discovery: WledDiscovery,
    private val apiClient: WledApiClient,
    private val backupService: WledBackupService,
) {
    private val log = LoggerFactory.getLogger(ValidationService::class.java)

    fun validate(
        showDir: Path,
        timeoutSeconds: Int,
        controllerFilter: Set<String> = emptySet(),
    ): ValidationReport {
        val allControllers = parser.parse(showDir)
        val controllers    = if (controllerFilter.isEmpty()) allControllers
            else allControllers.filter { c -> controllerFilter.any { it.equals(c.name, ignoreCase = true) } }
        // Skip mDNS when a filter is given — we already have the exact hostnames we need.
        val runMdns = controllerFilter.isEmpty()
        val wledDevices = fetchDevices(controllers, if (runMdns) timeoutSeconds else 0)
        return buildReport(controllers, wledDevices)
    }

    // ── Fix modes ────────────────────────────────────────────────────────────

    fun fix(report: ValidationReport) {
        for (cv in report.controllerValidations) {
            val device = cv.wledDevice ?: continue

            if (!backupBeforeMutating(device)) continue

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

            if (!backupBeforeMutating(device)) continue

            val effective  = collapseGroups(controller.models)
            val modelCount = effective.size
            if (modelCount > device.maxSegments) {
                log.warn("'{}' has {} effective segments but WLED supports only {} — " +
                    "only the first {} will be synced",
                    device.name, modelCount, device.maxSegments, device.maxSegments)
            }

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

            // Step 3: create segments in xLights model order (up to firmware limit)
            val bpp = device.bytesPerPixel
            effective.take(device.maxSegments).forEachIndexed { index, model ->
                val pixelStart = (model.startChannel - 1) / bpp
                val pixelStop  = minOf(pixelStart + model.channelCount / bpp, device.totalLeds)
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

    // Resets a device's segments to WLED's own hardware default: one segment per
    // physical bus, sized exactly to that bus's configured pixel range. Falls back
    // to a single segment spanning the full LED count if the device's bus list is
    // empty/unavailable (e.g. very old firmware, or /json/cfg didn't return hw.led.ins).
    // No backup is taken first — the caller is expected to be discarding all
    // backups anyway as part of the same operation, so backing up first would be
    // pointless.
    fun resetToBusDefaults(device: WledDevice) {
        val targets = if (device.busses.isNotEmpty())
            device.busses.map { it.start to (it.start + it.len) }
        else
            listOf(0 to device.totalLeds)

        log.info("Resetting '{}' ({}) to {} bus-default segment(s)...", device.name, device.ipAddress, targets.size)

        // Step 1: collapse to a single segment covering all LEDs
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

        // Step 3: create one segment per bus (or the single fallback segment)
        targets.take(device.maxSegments).forEachIndexed { index, (start, stop) ->
            apiClient.patchState(device.ipAddress, WledStatePatch(listOf(
                WledSegmentPatch(id = index, start = start, stop = stop, on = true)
            )))
        }
    }

    // Returns true if the device was successfully backed up (safe to mutate).
    // On backup failure, logs and returns false so the caller skips this device
    // rather than patching it without a recovery point.
    private fun backupBeforeMutating(device: WledDevice): Boolean =
        try {
            backupService.backup(device)
            true
        } catch (e: Exception) {
            log.error("Skipping '{}' ({}) — pre-mutation backup failed: {}", device.name, device.ipAddress, e.message)
            false
        }

    // ── Internal ─────────────────────────────────────────────────────────────

    // Fetch devices in parallel: use xLights-configured hostnames first (most reliable),
    // then fold in mDNS discovery for any additional devices not already found.
    private fun fetchDevices(controllers: List<XLightsController>, timeoutSeconds: Int): List<WledDevice> {
        val pool = Executors.newCachedThreadPool()
        try {
            // Primary: each controller's configured hostname/IP — all in parallel
            val futures = controllers
                .filter { it.ipAddress != null }
                .map { ctrl ->
                    val host = ctrl.ipAddress!!
                    ctrl to CompletableFuture.supplyAsync({
                        try {
                            val device = apiClient.fetchDevice(host)
                            log.info("Reached '{}' directly at {}", device.name, host)
                            device
                        } catch (e: Exception) {
                            log.warn("Cannot reach WLED controller '{}' at {}: {}", ctrl.name, host, e.message)
                            null
                        }
                    }, pool)
                }

            val devices = futures.mapNotNull { (_, f) -> f.get() }.toMutableList()
            val reachedNames = devices.map { it.name.lowercase() }.toHashSet()

            // Secondary: mDNS discovery for devices not already fetched (skipped when timeout=0)
            if (timeoutSeconds > 0) {
                try {
                    val discoveredIps = discovery.discoverDevices(timeoutSeconds)
                    val extraFutures  = discoveredIps.map { ip ->
                        CompletableFuture.supplyAsync({
                            try {
                                apiClient.fetchDevice(ip)
                            } catch (e: Exception) {
                                log.warn("Failed to fetch WLED device at {}: {}", ip, e.message)
                                null
                            }
                        }, pool)
                    }
                    extraFutures.mapNotNull { it.get() }
                        .filter { it.name.lowercase() !in reachedNames }
                        .forEach { device ->
                            devices += device
                            log.info("Discovered additional WLED device '{}' at {} via mDNS", device.name, device.ipAddress)
                        }
                } catch (e: Exception) {
                    log.warn("mDNS discovery failed (continuing with directly-reached devices): {}", e.message)
                }
            }

            return devices
        } finally {
            pool.shutdown()
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
            val device = wledDevices.find { matchesControllerName(it.name, controller.name) }
            if (device != null) {
                pairedXlights += controller.name
                pairedWled    += device.name
            }

            val bpp          = device?.bytesPerPixel ?: 3
            val expectedLeds = controller.totalChannels / bpp
            val effective    = collapseGroups(controller.models)
            val segValidations = effective.map { model ->
                validateModel(model, device, bpp)
            }
            val orphans = device?.segments?.filter { seg ->
                effective.none { m -> m.name.equals(seg.name ?: "", ignoreCase = true) }
            } ?: emptyList()

            val segLimitWarn = if (device != null && effective.size > device.maxSegments)
                "xLights has ${effective.size} effective segments but WLED firmware supports only " +
                    "${device.maxSegments} — segments ${device.maxSegments + 1}–${effective.size} cannot be synced"
            else null

            validations += ControllerValidation(
                xLightsController   = controller,
                wledDevice          = device,
                expectedTotalLeds   = expectedLeds,
                totalLedsMatch      = device != null && device.totalLeds == expectedLeds,
                segmentValidations  = segValidations,
                orphanSegments      = orphans,
                segmentLimitWarning = segLimitWarn,
            )
        }

        return ValidationReport(
            controllerValidations        = validations,
            unpairedXlightsControllers   = xLightsControllers.filter { it.name !in pairedXlights },
            unpairedWledDevices          = wledDevices.filter { it.name !in pairedWled },
        )
    }

    private fun validateModel(model: XLightsModel, device: WledDevice?, bpp: Int): SegmentValidation {
        val pixelStart   = (model.startChannel - 1) / bpp
        val pixelStop    = pixelStart + model.channelCount / bpp
        val clampedStop  = device?.let { minOf(pixelStop, it.totalLeds) } ?: pixelStop

        val segment = device?.segments?.find { it.name.equals(model.name, ignoreCase = true) }

        val status = when {
            segment == null                                                 -> SegmentStatus.MISSING
            segment.start != pixelStart || segment.stop != clampedStop     -> SegmentStatus.RANGE_MISMATCH
            model.isNull && segment.on                                      -> SegmentStatus.NULL_NOT_OFF
            else                                                             -> SegmentStatus.OK
        }

        return SegmentValidation(
            model              = model,
            expectedPixelStart = pixelStart,
            expectedPixelStop  = pixelStop,
            actualSegment      = segment,
            status             = status,
        )
    }

    // Pair WLED device name to xLights controller name:
    //  1. Exact match (case-insensitive): "Octa1" == "Octa1"
    //  2. Strip common "wled-" prefix: "wled-octa1" matches "Octa1"
    private fun matchesControllerName(wledName: String, controllerName: String): Boolean {
        if (wledName.equals(controllerName, ignoreCase = true)) return true
        val stripped = wledName.removePrefix("wled-")
        return stripped.equals(controllerName, ignoreCase = true)
    }

    // Merge consecutive models whose names share a common base after stripping a trailing "-N" suffix
    // (e.g., PeaceStake-1 … PeaceStake-35 → one "PeaceStake" model spanning the full range).
    // Models without a numbered suffix, or whose base differs from their neighbor, are left alone.
    private fun collapseGroups(models: List<XLightsModel>): List<XLightsModel> {
        val numberedSuffix = Regex("""-\d+$""")
        val result = mutableListOf<XLightsModel>()
        var i = 0
        while (i < models.size) {
            val model = models[i]
            if (!model.name.matches(Regex(""".*-\d+$"""))) {
                result += model
                i++
                continue
            }
            val baseName = model.name.replace(numberedSuffix, "")
            var j = i + 1
            while (j < models.size) {
                val next = models[j]
                if (!next.name.matches(Regex(""".*-\d+$"""))) break
                if (next.name.replace(numberedSuffix, "") != baseName) break
                j++
            }
            val group = models.subList(i, j)
            result += if (group.size == 1) model
            else model.copy(
                name         = baseName,
                channelCount = group.sumOf { it.channelCount },
                isNull       = group.all { it.isNull },
            )
            i = j
        }
        return result
    }
}
