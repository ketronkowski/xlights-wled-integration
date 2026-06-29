package com.ketronkowski.xlights.cli

import com.ketronkowski.xlights.domain.*
import com.ketronkowski.xlights.validation.ValidationService
import org.springframework.stereotype.Component
import picocli.CommandLine.*
import java.nio.file.Path

@Command(
    name        = "validate",
    description = ["Validate xLights controller configuration against discovered WLED devices"],
    mixinStandardHelpOptions = true,
)
@Component
class ValidateCommand(private val validationService: ValidationService) : Runnable {

    @Option(names = ["--xlights-dir"], required = true, description = ["Path to xLights show directory"])
    lateinit var xlightsDir: Path

    @Option(names = ["--fix"], description = ["Patch individual mismatches on WLED devices"])
    var fix: Boolean = false

    @Option(names = ["--fix-all"], description = ["Clear and rebuild all WLED segments from xLights (authoritative sync)"])
    var fixAll: Boolean = false

    @Option(names = ["--timeout"], description = ["mDNS discovery timeout in seconds (default: 5)"])
    var timeout: Int = 5

    override fun run() {
        require(!fix || !fixAll) { "--fix and --fix-all are mutually exclusive" }

        val report = validationService.validate(xlightsDir, timeout)
        printReport(report)

        if (fixAll) {
            println()
            println("${BOLD}Rebuilding all segments (--fix-all)...${RESET}")
            validationService.fixAll(report)
            println("Done. Re-validating...")
            val after = validationService.validate(xlightsDir, timeout)
            printReport(after)
        } else if (fix) {
            println()
            println("${BOLD}Applying fixes (--fix)...${RESET}")
            validationService.fix(report)
            println("Done. Re-validating...")
            val after = validationService.validate(xlightsDir, timeout)
            printReport(after)
        }
    }

    // ── Report rendering ──────────────────────────────────────────────────────

    private fun printReport(report: ValidationReport) {
        println()

        if (report.unpairedXlightsControllers.isNotEmpty()) {
            println("${YELLOW}Unpaired xLights controllers (no matching WLED device found):${RESET}")
            report.unpairedXlightsControllers.forEach { println("  ${YELLOW}⚠ ${it.name}${RESET}") }
            println()
        }

        if (report.unpairedWledDevices.isNotEmpty()) {
            println("${YELLOW}Unpaired WLED devices (no matching xLights controller):${RESET}")
            report.unpairedWledDevices.forEach { println("  ${YELLOW}⚠ ${it.name} (${it.ipAddress})${RESET}") }
            println()
        }

        for (cv in report.controllerValidations) {
            val deviceLabel = cv.wledDevice?.let { "${it.name} (${it.ipAddress})" } ?: "${RED}NOT FOUND${RESET}"
            println("${BOLD}Controller: ${cv.xLightsController.name}  →  WLED: $deviceLabel${RESET}")

            if (cv.wledDevice == null) {
                println("  ${RED}No paired WLED device — skipping segment validation${RESET}")
                println()
                continue
            }

            val ledStatus = if (cv.totalLedsMatch) OK_TAG else FAIL_TAG
            println("  Total LEDs: xLights=${cv.expectedTotalLeds}  WLED=${cv.wledDevice.totalLeds}  $ledStatus")
            println()

            if (cv.segmentValidations.isNotEmpty()) {
                val nameW = maxOf(cv.segmentValidations.maxOf { it.model.name.length }, 20)
                val hdr   = "  %-${nameW}s  %-14s  %-14s  %-5s  %s".format(
                    "Model", "xLights Range", "WLED Range", "On", "Status")
                println(hdr)
                println("  " + "─".repeat(hdr.length - 2))

                for (sv in cv.segmentValidations) {
                    val expected   = "${sv.expectedPixelStart}-${sv.expectedPixelStop}"
                    val actual     = sv.actualSegment?.let { "${it.start}-${it.stop}" } ?: "—"
                    val onFlag     = when {
                        sv.model.isNull             -> "off"
                        sv.actualSegment != null    -> if (sv.actualSegment.on) "on" else "off"
                        else                        -> "—"
                    }
                    val statusTag  = statusTag(sv.status)
                    println("  %-${nameW}s  %-14s  %-14s  %-5s  %s".format(
                        sv.model.name, expected, actual, onFlag, statusTag))
                }
            }

            if (cv.orphanSegments.isNotEmpty()) {
                println()
                println("  ${YELLOW}Orphan WLED segments (no matching xLights model):${RESET}")
                cv.orphanSegments.forEach { seg ->
                    println("  ${YELLOW}  ⚠ [${seg.id}] '${seg.name ?: "(unnamed)"}' pixels ${seg.start}-${seg.stop}${RESET}")
                }
            }
            println()
        }

        val summary = if (report.hasErrors) "${RED}VALIDATION FAILED${RESET}" else "${GREEN}ALL OK${RESET}"
        println("$BOLD$summary$RESET")
    }

    private fun statusTag(status: SegmentStatus): String = when (status) {
        SegmentStatus.OK             -> OK_TAG
        SegmentStatus.MISSING        -> "${RED}[MISSING]${RESET}"
        SegmentStatus.RANGE_MISMATCH -> "${RED}[RANGE MISMATCH]${RESET}"
        SegmentStatus.NULL_NOT_OFF   -> "${YELLOW}[NULL NOT OFF]${RESET}"
    }

    // ANSI codes — picocli respects NO_COLOR / terminal detection automatically
    // but we wire them manually here for simplicity.
    companion object {
        private val ANSI = System.getenv("NO_COLOR") == null && System.console() != null
        private val BOLD    = if (ANSI) "[1m"  else ""
        private val RESET   = if (ANSI) "[0m"  else ""
        private val GREEN   = if (ANSI) "[32m" else ""
        private val RED     = if (ANSI) "[31m" else ""
        private val YELLOW  = if (ANSI) "[33m" else ""
        private val OK_TAG  = "${GREEN}[OK]${RESET}"
        private val FAIL_TAG = "${RED}[FAIL]${RESET}"
    }
}
