package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.domain.WledBackupRecord
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.WledBackupService
import com.ketronkowski.xlights.xlights.XLightsConfigParser
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

@RestController
@RequestMapping("/api/backups")
class BackupController(
    private val backupService: WledBackupService,
    private val apiClient: WledApiClient,
    private val parser: XLightsConfigParser,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val xlightsDir: Path = Path.of(dataDir, "xlights")

    @GetMapping
    fun list(@RequestParam(required = false) controller: String?): List<WledBackupRecord> =
        backupService.listBackups(controller)

    // ip is optional: when omitted, it's resolved from the controller's configured
    // address in the uploaded xLights config. Passing it explicitly also lets the
    // frontend back up a device that isn't (or is no longer) paired to an xLights
    // controller, as long as it already knows the device's address.
    @PostMapping("/{controller}")
    fun backupNow(
        @PathVariable controller: String,
        @RequestParam(required = false) ip: String?,
    ): WledBackupRecord {
        val device = apiClient.fetchDevice(ip ?: resolveIp(controller))
        return backupService.backup(device)
    }

    @PostMapping("/{controller}/restore/{timestamp}")
    fun restore(
        @PathVariable controller: String,
        @PathVariable timestamp: Long,
        @RequestParam ip: String,
    ) {
        backupService.restore(controller, timestamp, ip)
    }

    private fun resolveIp(controllerName: String): String {
        val ctrl = parser.parse(xlightsDir).find { it.name.equals(controllerName, ignoreCase = true) }
            ?: error("No xLights controller named '$controllerName'")
        return ctrl.ipAddress ?: error("Controller '$controllerName' has no configured IP address")
    }
}
