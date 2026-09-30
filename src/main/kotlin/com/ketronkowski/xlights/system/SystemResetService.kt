package com.ketronkowski.xlights.system

import com.ketronkowski.xlights.domain.WledDevice
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledBackupService
import com.ketronkowski.xlights.wled.WledDiscovery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class DeviceResetOutcome(
    val name: String,
    val ipAddress: String,
    val success: Boolean,
    val error: String? = null,
)

data class SystemResetResult(
    val deviceOutcomes: List<DeviceResetOutcome>,
    val backupsCleared: Boolean,
    val configCleared: Boolean,
)

@Service
class SystemResetService(
    private val validationService: ValidationService,
    private val discovery: WledDiscovery,
    private val apiClient: WledApiClient,
    private val backupService: WledBackupService,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(SystemResetService::class.java)
    private val xlightsDir: Path = Path.of(dataDir, "xlights")
    private val networksFile: Path = xlightsDir.resolve("xlights_networks.xml")
    private val effectsFile: Path = xlightsDir.resolve("xlights_rgbeffects.xml")
    private val metaFile: Path = xlightsDir.resolve("upload-meta.json")

    companion object {
        private const val MDNS_TIMEOUT_SECONDS = 5 // matches WledDiscovery's own default
    }

    fun resetEverything(): SystemResetResult {
        val devices = discoverAllVisibleDevices()
        log.warn("Clear All Controllers: resetting {} reachable device(s) to bus defaults", devices.size)

        val outcomes = devices.map { device ->
            try {
                validationService.resetToBusDefaults(device)
                DeviceResetOutcome(device.name, device.ipAddress, success = true)
            } catch (e: Exception) {
                log.error("Failed to reset '{}' ({}): {}", device.name, device.ipAddress, e.message)
                DeviceResetOutcome(device.name, device.ipAddress, success = false, error = e.message)
            }
        }

        val backupsCleared = runCatching { backupService.deleteAllBackups() }
            .onFailure { log.error("Failed to delete backups: {}", it.message) }
            .getOrDefault(false)

        val configCleared = runCatching { deleteUploadedConfig() }
            .onFailure { log.error("Failed to delete uploaded config: {}", it.message) }
            .getOrDefault(false)

        return SystemResetResult(outcomes, backupsCleared, configCleared)
    }

    // "Everything currently visible": paired + unpaired devices via validate()
    // when a show is uploaded, or a pure mDNS sweep when it isn't (nothing to
    // pair against yet, but reachable devices should still be reset).
    private fun discoverAllVisibleDevices(): List<WledDevice> {
        if (!networksFile.exists()) {
            return discovery.discoverDevices(MDNS_TIMEOUT_SECONDS).mapNotNull { ip ->
                runCatching { apiClient.fetchDevice(ip) }
                    .onFailure { log.warn("Unreachable during pure mDNS sweep: {} — {}", ip, it.message) }
                    .getOrNull()
            }
        }

        val report = validationService.validate(xlightsDir, timeoutSeconds = MDNS_TIMEOUT_SECONDS)
        val paired = report.controllerValidations.mapNotNull { it.wledDevice }
        val unpaired = report.unpairedWledDevices
        return (paired + unpaired).distinctBy { it.name.lowercase() }
    }

    private fun deleteUploadedConfig(): Boolean {
        if (!xlightsDir.exists()) return false
        var deletedAnything = false
        listOf(networksFile, effectsFile, metaFile).forEach {
            if (Files.deleteIfExists(it)) deletedAnything = true
        }
        return deletedAnything
    }
}
