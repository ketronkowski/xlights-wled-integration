package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.system.SystemResetResult
import com.ketronkowski.xlights.system.SystemResetService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system")
class SystemController(
    private val systemResetService: SystemResetService,
) {
    @PostMapping("/reset")
    fun reset(): SystemResetResult = systemResetService.resetEverything()
}
