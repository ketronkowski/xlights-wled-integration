package com.ketronkowski.xlights.wled

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.WledBackupRecord
import com.ketronkowski.xlights.domain.WledDevice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Service
class WledBackupService(
    private val apiClient: WledApiClient,
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(WledBackupService::class.java)
    private val backupsDir: Path = Path.of(dataDir, "backups")

    fun backup(device: WledDevice): WledBackupRecord {
        // cfg.json is the critical file — if we can't fetch it, abort the whole backup
        // rather than record a partial/misleading success.
        val cfg = apiClient.downloadRaw(device.ipAddress, "cfg.json")
        val presets = runCatching { apiClient.downloadRaw(device.ipAddress, "presets.json") }
            .onFailure { log.warn("No presets.json available on '{}' ({}): {}", device.name, device.ipAddress, it.message) }
            .getOrNull()

        val timestamp = Instant.now()
        val dir = backupsDir.resolve(device.name).resolve(timestamp.toEpochMilli().toString())
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("cfg.json"), cfg)
        presets?.let { Files.writeString(dir.resolve("presets.json"), it) }

        val record = WledBackupRecord(
            controllerName = device.name,
            ipAddress      = device.ipAddress,
            timestamp      = timestamp,
            hasCfg         = true,
            hasPresets     = presets != null,
        )
        Files.writeString(dir.resolve("meta.json"), objectMapper.writeValueAsString(record))
        log.info("Backed up '{}' ({}) — cfg.json{}", device.name, device.ipAddress, if (presets != null) " + presets.json" else "")
        return record
    }

    fun listBackups(controllerName: String? = null): List<WledBackupRecord> {
        if (!backupsDir.exists()) return emptyList()

        val controllerDirs = if (controllerName != null) {
            listOfNotNull(backupsDir.resolve(controllerName).takeIf { it.exists() })
        } else {
            backupsDir.listDirectoryEntries().filter { it.isDirectory() }
        }

        return controllerDirs
            .flatMap { it.listDirectoryEntries() }
            .filter { it.isDirectory() && it.resolve("meta.json").exists() }
            .map { objectMapper.readValue(it.resolve("meta.json").readText(), WledBackupRecord::class.java) }
            .sortedByDescending { it.timestamp }
    }

    fun restore(controllerName: String, timestamp: Long, ip: String) {
        val dir = backupsDir.resolve(controllerName).resolve(timestamp.toString())
        require(dir.exists()) { "No backup found for '$controllerName' at timestamp $timestamp" }

        val cfgFile = dir.resolve("cfg.json")
        require(cfgFile.exists()) { "Backup for '$controllerName' at $timestamp has no cfg.json" }
        apiClient.uploadRaw(ip, "cfg.json", cfgFile.readText())

        val presetsFile = dir.resolve("presets.json")
        if (presetsFile.exists()) {
            apiClient.uploadRaw(ip, "presets.json", presetsFile.readText())
        }

        log.info("Restored backup {} to '{}' ({}) — rebooting device", timestamp, controllerName, ip)
        apiClient.reboot(ip)
    }
}
