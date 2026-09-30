package com.ketronkowski.xlights.system

import com.ketronkowski.xlights.domain.ControllerValidation
import com.ketronkowski.xlights.domain.ValidationReport
import com.ketronkowski.xlights.domain.WledDevice
import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledBackupService
import com.ketronkowski.xlights.wled.WledDiscovery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class SystemResetServiceTest {

    @TempDir
    lateinit var dataDir: Path

    private val deviceA = WledDevice("Octa1", "10.0.0.1", "0.15.0", 300, 3, 32, emptyList())
    private val deviceB = WledDevice("Octa2", "10.0.0.2", "0.15.0", 300, 3, 32, emptyList())

    private fun controllerValidation(device: WledDevice?): ControllerValidation {
        val controller = XLightsController(device?.name ?: "missing", device?.ipAddress, "WLED", 900, emptyList())
        return ControllerValidation(controller, device, 300, device != null, emptyList(), emptyList())
    }

    @Test
    fun `falls back to a pure mDNS sweep when no show has been uploaded`() {
        val validationService = mock<ValidationService>()
        val discovery = mock<WledDiscovery>()
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        whenever(discovery.discoverDevices(5)).thenReturn(listOf("10.0.0.1", "10.0.0.2"))
        whenever(apiClient.fetchDevice("10.0.0.1")).thenReturn(deviceA)
        whenever(apiClient.fetchDevice("10.0.0.2")).thenReturn(deviceB)

        val service = SystemResetService(validationService, discovery, apiClient, backupService, dataDir.toString())
        val result = service.resetEverything()

        verify(validationService, never()).validate(any(), any(), any())
        verify(validationService).resetToBusDefaults(deviceA)
        verify(validationService).resetToBusDefaults(deviceB)
        assertEquals(2, result.deviceOutcomes.size)
        assertTrue(result.deviceOutcomes.all { it.success })
    }

    @Test
    fun `uses validate with a non-zero timeout when a show is uploaded, merging paired and unpaired devices`() {
        val validationService = mock<ValidationService>()
        val discovery = mock<WledDiscovery>()
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()

        val xlightsDir = dataDir.resolve("xlights")
        Files.createDirectories(xlightsDir)
        Files.writeString(xlightsDir.resolve("xlights_networks.xml"), "<xml/>")

        val report = ValidationReport(
            controllerValidations = listOf(controllerValidation(deviceA)),
            unpairedXlightsControllers = emptyList(),
            unpairedWledDevices = listOf(deviceB),
        )
        whenever(validationService.validate(eq(xlightsDir), eq(5), any())).thenReturn(report)

        val service = SystemResetService(validationService, discovery, apiClient, backupService, dataDir.toString())
        val result = service.resetEverything()

        verify(validationService).validate(eq(xlightsDir), eq(5), any())
        verify(discovery, never()).discoverDevices(any())
        assertEquals(2, result.deviceOutcomes.size)
        verify(validationService).resetToBusDefaults(deviceA)
        verify(validationService).resetToBusDefaults(deviceB)
    }

    @Test
    fun `one device failing does not block the others or the backup and config cleanup`() {
        val validationService = mock<ValidationService>()
        val discovery = mock<WledDiscovery>()
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()
        whenever(discovery.discoverDevices(5)).thenReturn(listOf("10.0.0.1", "10.0.0.2"))
        whenever(apiClient.fetchDevice("10.0.0.1")).thenReturn(deviceA)
        whenever(apiClient.fetchDevice("10.0.0.2")).thenReturn(deviceB)
        whenever(validationService.resetToBusDefaults(deviceA)).thenThrow(RuntimeException("unreachable"))
        whenever(backupService.deleteAllBackups()).thenReturn(true)

        val service = SystemResetService(validationService, discovery, apiClient, backupService, dataDir.toString())
        val result = service.resetEverything()

        val failed = result.deviceOutcomes.find { it.name == "Octa1" }!!
        assertFalse(failed.success)
        assertEquals("unreachable", failed.error)
        val succeeded = result.deviceOutcomes.find { it.name == "Octa2" }!!
        assertTrue(succeeded.success)
        assertTrue(result.backupsCleared)
        verify(backupService).deleteAllBackups()
    }

    @Test
    fun `deletes the uploaded config files when present`() {
        val validationService = mock<ValidationService>()
        val discovery = mock<WledDiscovery>()
        val apiClient = mock<WledApiClient>()
        val backupService = mock<WledBackupService>()

        val xlightsDir = dataDir.resolve("xlights")
        Files.createDirectories(xlightsDir)
        val networksFile = xlightsDir.resolve("xlights_networks.xml")
        val effectsFile = xlightsDir.resolve("xlights_rgbeffects.xml")
        val metaFile = xlightsDir.resolve("upload-meta.json")
        Files.writeString(networksFile, "<xml/>")
        Files.writeString(effectsFile, "<xml/>")
        Files.writeString(metaFile, "{}")

        whenever(validationService.validate(eq(xlightsDir), eq(5), any())).thenReturn(
            ValidationReport(emptyList(), emptyList(), emptyList())
        )

        val service = SystemResetService(validationService, discovery, apiClient, backupService, dataDir.toString())
        val result = service.resetEverything()

        assertTrue(result.configCleared)
        assertFalse(Files.exists(networksFile))
        assertFalse(Files.exists(effectsFile))
        assertFalse(Files.exists(metaFile))
    }
}
