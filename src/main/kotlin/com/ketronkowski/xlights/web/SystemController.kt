package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.system.SystemResetResult
import com.ketronkowski.xlights.system.SystemResetService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** What the UI footer shows: the app version and the commit the image was built from. */
data class AppVersion(val version: String, val sha: String)

@RestController
@RequestMapping("/api/system")
class SystemController(
    private val systemResetService: SystemResetService,
    buildProperties: ObjectProvider<BuildProperties>,
    @Value("\${APP_GIT_SHA:dev}") gitSha: String,
) {
    private val appVersion = AppVersion(
        version = buildProperties.ifAvailable?.version ?: "dev",
        sha = gitSha.ifBlank { "dev" }.take(7),
    )

    @PostMapping("/reset")
    fun reset(): SystemResetResult = systemResetService.resetEverything()

    @GetMapping("/version")
    fun version(): AppVersion = appVersion
}
