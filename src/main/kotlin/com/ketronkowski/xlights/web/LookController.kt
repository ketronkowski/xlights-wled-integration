package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.look.ApplyLookOutcome
import com.ketronkowski.xlights.look.ApplyLookRequest
import com.ketronkowski.xlights.look.LookCatalog
import com.ketronkowski.xlights.look.LookService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/look")
class LookController(private val lookService: LookService) {

    /** Effects, palettes and current look shared by the given controllers (comma-separated IPs). */
    @GetMapping("/catalog")
    fun catalog(
        @RequestParam ips: List<String>,
        @RequestParam(defaultValue = "false") refresh: Boolean,
    ): LookCatalog = lookService.catalog(ips, refresh)

    /** Puts the look on every listed controller at once; one outcome per controller. */
    @PostMapping("/apply")
    fun apply(@RequestBody req: ApplyLookRequest): List<ApplyLookOutcome> = lookService.apply(req)
}
