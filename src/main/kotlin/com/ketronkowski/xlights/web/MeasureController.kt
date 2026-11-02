package com.ketronkowski.xlights.web

import com.ketronkowski.xlights.measure.MeasureService
import com.ketronkowski.xlights.measure.MeasureTarget
import com.ketronkowski.xlights.measure.PaintRange
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClientException

data class MeasureStartRequest(val ip: String, val port: Int, val brightness: Int = 80)
data class MeasurePaintRequest(val ip: String, val ranges: List<PaintRange>, val brightness: Int? = null)
data class MeasureStopRequest(val ip: String)
data class MeasureError(val error: String)

@RestController
@RequestMapping("/api/measure")
class MeasureController(
    private val measureService: MeasureService,
) {
    @PostMapping("/start")
    fun start(@RequestBody req: MeasureStartRequest): MeasureTarget =
        measureService.start(req.ip, req.port, req.brightness)

    @PostMapping("/paint")
    fun paint(@RequestBody req: MeasurePaintRequest) =
        measureService.paint(req.ip, req.ranges, req.brightness)

    @PostMapping("/stop")
    fun stop(@RequestBody req: MeasureStopRequest) = measureService.stop(req.ip)

    @GetMapping("/sessions")
    fun sessions(): List<MeasureTarget> = measureService.activeSessions()

    @GetMapping("/plan", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun plan(): String = measureService.loadPlan()

    @PutMapping("/plan", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun savePlan(@RequestBody json: String) = measureService.savePlan(json)

    @DeleteMapping("/plan", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun resetPlan(): String = measureService.resetPlan()

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(MeasureError(e.message ?: "Bad request"))

    @ExceptionHandler(RestClientException::class)
    fun deviceUnreachable(e: RestClientException) =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(MeasureError("Controller did not respond: ${e.message}"))
}
