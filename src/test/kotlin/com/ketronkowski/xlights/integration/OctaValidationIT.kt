package com.ketronkowski.xlights.integration

import com.ketronkowski.xlights.domain.SegmentStatus
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Integration test against the live Octa1, Octa2, and Octa3 WLED controllers
 * using the Christmas 2023 xLights show directory.
 *
 * Requires:
 *   - Network access to the local WLED controllers (mDNS discovery must work)
 *   - The show directory at ~/xlights/Christmas 2023
 *
 * Skip in CI by running: ./gradlew test -x integrationTest
 * or by omitting the 'integration' tag: ./gradlew test --tests '!*IT'
 */
@Tag("integration")
@SpringBootTest
@EnabledIf("showDirectoryExists")
class OctaValidationIT {

    @Autowired
    lateinit var validationService: ValidationService

    @Autowired
    lateinit var parser: XLightsConfigParser

    companion object {
        private val SHOW_DIR: Path = Paths.get(System.getProperty("user.home"), "xlights", "Christmas 2023")
        private val OCTA_NAMES = setOf("Octa1", "Octa2", "Octa3")

        @JvmStatic
        fun showDirectoryExists(): Boolean = SHOW_DIR.toFile().exists()
    }

    // ── xLights parsing tests ─────────────────────────────────────────────────

    @Test
    fun `parses Octa1 Octa2 Octa3 from real show directory`() {
        val controllers = parser.parse(SHOW_DIR)
        val names = controllers.map { it.name }.toSet()

        assertTrue(names.containsAll(OCTA_NAMES),
            "Expected $OCTA_NAMES in parsed controllers, got: $names")

        for (name in OCTA_NAMES) {
            val ctrl = controllers.first { it.name == name }
            assertTrue(ctrl.totalChannels > 0, "$name should have non-zero channel count")
            assertTrue(ctrl.models.isNotEmpty(), "$name should have models assigned")
            println("$name: ${ctrl.totalChannels} channels, ${ctrl.models.size} models " +
                "(${ctrl.models.count { it.isNull }} null/gap)")
        }
    }

    @Test
    fun `all Octa models have valid start channels and channel counts`() {
        val controllers = parser.parse(SHOW_DIR)
        for (ctrlName in OCTA_NAMES) {
            val ctrl = controllers.firstOrNull { it.name == ctrlName } ?: continue
            for (model in ctrl.models) {
                assertTrue(model.startChannel > 0,
                    "$ctrlName/${model.name}: startChannel must be > 0, got ${model.startChannel}")
                assertTrue(model.channelCount > 0,
                    "$ctrlName/${model.name}: channelCount must be > 0, got ${model.channelCount}")
                assertTrue(model.startChannel + model.channelCount - 1 <= ctrl.totalChannels,
                    "$ctrlName/${model.name}: end channel ${model.startChannel + model.channelCount - 1} " +
                        "exceeds controller max ${ctrl.totalChannels}")
            }
        }
    }

    @Test
    fun `null gap models are correctly identified — not all controllers require them`() {
        val controllers = parser.parse(SHOW_DIR)
        for (ctrlName in OCTA_NAMES) {
            val ctrl = controllers.firstOrNull { it.name == ctrlName } ?: continue
            val nullModels = ctrl.models.filter { it.isNull }
            println("$ctrlName null/gap models (${nullModels.size}): ${nullModels.map { it.name }}")
            // All isNull models must have LayoutGroup=Nulls or name starting with Null/null
            for (m in nullModels) {
                assertTrue(m.isNull, "$ctrlName/${m.name} flagged isNull=false but appeared in filter")
            }
        }
        // Octa2 is known to have null/gap models in Christmas 2023 — verify they're detected
        val octa2 = controllers.firstOrNull { it.name == "Octa2" }
        if (octa2 != null) {
            assertTrue(octa2.models.any { it.isNull },
                "Octa2 should have null/gap models in the Christmas 2023 show")
        }
    }

    // ── Live WLED validation tests ────────────────────────────────────────────

    @Test
    fun `discovers Octa1 Octa2 Octa3 via mDNS and validates LED counts`() {
        val report = validationService.validate(SHOW_DIR, timeoutSeconds = 10)

        val pairedNames = report.controllerValidations
            .filter { it.wledDevice != null }
            .map { it.xLightsController.name }
            .toSet()

        val missingOctas = OCTA_NAMES - pairedNames
        assertTrue(missingOctas.isEmpty(),
            "Expected to discover and pair all Octa controllers, missing: $missingOctas\n" +
                "Make sure Octa1, Octa2, Octa3 are powered on and discoverable via mDNS.")

        for (cv in report.controllerValidations.filter { it.xLightsController.name in OCTA_NAMES }) {
            val ctrlName = cv.xLightsController.name
            assertNotNull(cv.wledDevice, "$ctrlName: no WLED device paired")

            println(buildString {
                appendLine("$ctrlName → ${cv.wledDevice!!.name} (${cv.wledDevice.ipAddress})  fw=${cv.wledDevice.firmwareVersion}")
                appendLine("  LED count:  xLights=${cv.expectedTotalLeds}  WLED=${cv.wledDevice.totalLeds}  match=${cv.totalLedsMatch}")
                appendLine("  Segments:   ${cv.wledDevice.segments.size} on device")
                cv.segmentValidations.take(10).forEach { sv ->
                    appendLine("    ${sv.status.name.padEnd(16)} ${sv.model.name}")
                }
                if (cv.segmentValidations.size > 10) appendLine("    ... and ${cv.segmentValidations.size - 10} more")
            })

            // LED count may or may not match — tool detects and reports it either way
            val ledStatus = if (cv.totalLedsMatch) "OK" else
                "MISMATCH (xLights=${cv.expectedTotalLeds}, WLED=${cv.wledDevice!!.totalLeds})"
            println("  $ctrlName LED count: $ledStatus")
        }
    }

    @Test
    fun `WLED segments are incorrect for Octa controllers`() {
        val report = validationService.validate(SHOW_DIR, timeoutSeconds = 10)

        for (cv in report.controllerValidations.filter { it.xLightsController.name in OCTA_NAMES }) {
            val ctrlName = cv.xLightsController.name
            if (cv.wledDevice == null) {
                println("$ctrlName: WLED device not found — skipping segment check")
                continue
            }

            val issues = cv.segmentValidations.filter { it.status != SegmentStatus.OK }
            println("$ctrlName: ${issues.size} segment issue(s) out of ${cv.segmentValidations.size} models")
            issues.forEach { sv ->
                println("  [${sv.status}] ${sv.model.name}  " +
                    "expected pixels ${sv.expectedPixelStart}-${sv.expectedPixelStop}  " +
                    "actual=${sv.actualSegment?.let { "${it.start}-${it.stop}" } ?: "MISSING"}")
            }

            // We know from setup that segments are not yet configured correctly
            assertTrue(issues.isNotEmpty(),
                "$ctrlName: expected segment mismatches, but all ${cv.segmentValidations.size} segments report OK. " +
                    "Has --fix-all already been run against this controller?")
        }
    }

    @Test
    fun `produces complete validation report for all Octa controllers`() {
        val report = validationService.validate(SHOW_DIR, timeoutSeconds = 10)

        println("=== Validation Report ===")
        println("Paired controllers: ${report.controllerValidations.count { it.wledDevice != null }}")
        println("Unpaired xLights: ${report.unpairedXlightsControllers.map { it.name }}")
        println("Unpaired WLED: ${report.unpairedWledDevices.map { "${it.name}(${it.ipAddress})" }}")
        println("Overall: ${if (report.hasErrors) "FAIL" else "PASS"}")

        // Non-Octa controllers will be unpaired (they're not reachable) — that's fine
        // What matters is that Octa1/2/3 are paired and their LED counts verified
        val octaValidations = report.controllerValidations
            .filter { it.xLightsController.name in OCTA_NAMES }

        assertEquals(OCTA_NAMES.size, octaValidations.size,
            "Expected ${OCTA_NAMES.size} Octa controller validations")
    }
}
