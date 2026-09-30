package com.ketronkowski.xlights.wled.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledInfoResponse(
    val name: String = "",
    val ver: String = "",
    val leds: WledLedsInfo = WledLedsInfo(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledLedsInfo(
    val count: Int = 0,
    val lc: Int = 1,        // 1=RGB, 2=White, 3=RGBW
    val maxseg: Int = 32,   // maximum number of segments the firmware supports
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledStateResponse(
    val seg: List<WledSegmentDto> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledSegmentDto(
    val id: Int = 0,
    @param:JsonProperty("n") val name: String? = null,
    val start: Int = 0,
    val stop: Int = 0,
    val on: Boolean = true,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledCfgResponse(
    val hw: WledHwConfig = WledHwConfig(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledHwConfig(
    val led: WledLedConfig = WledLedConfig(),
)

// Per-output LED bus config actually lives at hw.led.ins — NOT hw.com, which is
// WLED's (usually-empty) serial/COM port config and unrelated to LED outputs.
// Confirmed against real QuinLED-Dig-Octa firmware /json/cfg output.
@JsonIgnoreProperties(ignoreUnknown = true)
data class WledLedConfig(
    val ins: List<WledBusConfig> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WledBusConfig(
    val start: Int = 0,
    val len: Int = 0,
    val type: Int = 30,   // 30=WS2812B (RGB), 31=SK6812 (RGBW), etc.
)

// Patch payload sent via POST /json/state
data class WledStatePatch(
    val seg: List<WledSegmentPatch>,
)

data class WledSegmentPatch(
    val id: Int? = null,
    @get:JsonProperty("n")
    @param:JsonProperty("n")
    val name: String? = null,
    val start: Int? = null,
    val stop: Int? = null,
    val on: Boolean? = null,
)
