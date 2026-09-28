package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.domain.ValidationReport
import com.ketronkowski.xlights.validation.ValidationService
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

data class ValidateRequest(val controllers: List<String> = emptyList())

@RestController
@RequestMapping("/api")
class ValidationController(
    private val validationService: ValidationService,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val xlightsDir: Path = Path.of(dataDir, "xlights")

    @PostMapping("/validate")
    fun validate(@RequestBody(required = false) req: ValidateRequest?): ValidationReport =
        validationService.validate(xlightsDir, timeoutSeconds = 0, controllerFilter = filterOf(req))

    @PostMapping("/fix")
    fun fix(@RequestBody(required = false) req: ValidateRequest?): ValidationReport {
        val filter = filterOf(req)
        val before = validationService.validate(xlightsDir, 0, filter)
        validationService.fix(before)
        return validationService.validate(xlightsDir, 0, filter)
    }

    @PostMapping("/fix-all")
    fun fixAll(@RequestBody(required = false) req: ValidateRequest?): ValidationReport {
        val filter = filterOf(req)
        val before = validationService.validate(xlightsDir, 0, filter)
        validationService.fixAll(before)
        return validationService.validate(xlightsDir, 0, filter)
    }

    private fun filterOf(req: ValidateRequest?): Set<String> = (req?.controllers ?: emptyList()).toSet()
}
