package com.ketronkowski.xlights.wled

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.WledBus
import com.ketronkowski.xlights.domain.WledDevice
import com.ketronkowski.xlights.domain.WledSegment
import com.ketronkowski.xlights.wled.dto.WledCfgResponse
import com.ketronkowski.xlights.wled.dto.WledInfoResponse
import com.ketronkowski.xlights.wled.dto.WledStateResponse
import com.ketronkowski.xlights.wled.dto.WledStatePatch
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class WledApiClient(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(WledApiClient::class.java)

    fun fetchDevice(ip: String): WledDevice {
        val info  = get(ip, "/json/info",  WledInfoResponse::class.java)
        val state = get(ip, "/json/state", WledStateResponse::class.java)
        val cfg   = get(ip, "/json/cfg",   WledCfgResponse::class.java)

        val bytesPerPixel = resolveBytesPerPixel(info.leds.lc, cfg)
        val segments = state.seg.map { s ->
            WledSegment(id = s.id, name = s.name, start = s.start, stop = s.stop, on = s.on)
        }
        val busses = cfg.hw.led.ins.map { WledBus(start = it.start, len = it.len) }

        log.debug("Fetched WLED device '{}' @ {}  LEDs={}  bpp={}  segments={}",
            info.name, ip, info.leds.count, bytesPerPixel, segments.size)

        return WledDevice(
            name            = info.name,
            ipAddress       = ip,
            firmwareVersion = info.ver,
            totalLeds       = info.leds.count,
            bytesPerPixel   = bytesPerPixel,
            maxSegments     = info.leds.maxseg,
            segments        = segments,
            busses          = busses,
        )
    }

    fun patchState(ip: String, patch: WledStatePatch) {
        val body = objectMapper.writeValueAsString(patch)
        log.debug("POST /json/state @ {}  body={}", ip, body)
        restClient.post()
            .uri("http://$ip/json/state")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .retrieve()
            .toBodilessEntity()
    }

    // ── Raw config/preset backup & restore ──────────────────────────────────
    // cfg.json (network/hardware config) and presets.json (presets + playlists)
    // are served/accepted as plain static files, not via the JSON API.

    fun downloadRaw(ip: String, filename: String): String =
        restClient.get()
            .uri("http://$ip/$filename")
            .retrieve()
            .body<String>()
            ?: error("Empty response from $ip/$filename")

    fun uploadRaw(ip: String, filename: String, content: String) {
        val multipart = MultipartBodyBuilder().apply {
            part("data", content.toByteArray(Charsets.UTF_8))
                .filename(filename)
                .contentType(MediaType.APPLICATION_JSON)
        }.build()

        log.debug("POST /edit @ {}  filename={}  bytes={}", ip, filename, content.length)
        restClient.post()
            .uri("http://$ip/edit")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(multipart)
            .retrieve()
            .toBodilessEntity()
    }

    fun reboot(ip: String) {
        log.debug("POST /reset @ {}", ip)
        restClient.post()
            .uri("http://$ip/reset")
            .retrieve()
            .toBodilessEntity()
    }

    private fun <T : Any> get(ip: String, path: String, clazz: Class<T>): T =
        restClient.get()
            .uri("http://$ip$path")
            .retrieve()
            .body(clazz)
            ?: error("Empty response from $ip$path")

    // lc (light capability): 1=RGB(3bpp), 2=White(1bpp), 3=RGBW(4bpp), 7=CCT+RGBW(4bpp)
    // Falls back to inspecting cfg bus types if lc is ambiguous.
    private fun resolveBytesPerPixel(lc: Int, cfg: WledCfgResponse): Int {
        if (lc == 3 || lc == 7) return 4
        if (lc == 2) return 1
        // lc == 1 or unrecognized → RGB; cross-check against cfg bus type
        val busType = cfg.hw.led.ins.firstOrNull()?.type ?: return 3
        // WLED bus types >= 64 are RGBW variants; types < 64 are RGB
        return if (busType >= 64) 4 else 3
    }
}
