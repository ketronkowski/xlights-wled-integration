package com.ketronkowski.xlights.wled

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.WledDevice
import com.ketronkowski.xlights.domain.WledSegment
import com.ketronkowski.xlights.wled.dto.WledCfgResponse
import com.ketronkowski.xlights.wled.dto.WledInfoResponse
import com.ketronkowski.xlights.wled.dto.WledStateResponse
import com.ketronkowski.xlights.wled.dto.WledStatePatch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class WledApiClient(
    private val webClient: WebClient,
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

        log.debug("Fetched WLED device '{}' @ {}  LEDs={}  bpp={}  segments={}",
            info.name, ip, info.leds.count, bytesPerPixel, segments.size)

        return WledDevice(
            name           = info.name,
            ipAddress      = ip,
            firmwareVersion = info.ver,
            totalLeds      = info.leds.count,
            bytesPerPixel  = bytesPerPixel,
            segments       = segments,
        )
    }

    fun patchState(ip: String, patch: WledStatePatch) {
        val body = objectMapper.writeValueAsString(patch)
        log.debug("POST /json/state @ {}  body={}", ip, body)
        webClient.post()
            .uri("http://$ip/json/state")
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .retrieve()
            .bodyToMono<String>()
            .block()
    }

    private fun <T : Any> get(ip: String, path: String, clazz: Class<T>): T =
        webClient.get()
            .uri("http://$ip$path")
            .retrieve()
            .bodyToMono(clazz)
            .block()
            ?: error("Empty response from $ip$path")

    // lc (light capability): 1=RGB(3bpp), 2=White(1bpp), 3=RGBW(4bpp), 7=CCT+RGBW(4bpp)
    // Falls back to inspecting cfg bus types if lc is ambiguous.
    private fun resolveBytesPerPixel(lc: Int, cfg: WledCfgResponse): Int {
        if (lc == 3 || lc == 7) return 4
        if (lc == 2) return 1
        // lc == 1 or unrecognized → RGB; cross-check against cfg bus type
        val busType = cfg.hw.com.firstOrNull()?.type ?: return 3
        // WLED bus types >= 64 are RGBW variants; types < 64 are RGB
        return if (busType >= 64) 4 else 3
    }
}
