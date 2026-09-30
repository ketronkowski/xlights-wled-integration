package com.ketronkowski.xlights.domain

data class WledDevice(
    val name: String,
    val ipAddress: String,
    val firmwareVersion: String,
    val totalLeds: Int,
    val bytesPerPixel: Int,   // derived from LED type: 3=RGB, 4=RGBW
    val maxSegments: Int,
    val segments: List<WledSegment>,
    val busses: List<WledBus> = emptyList(),   // one entry per hardware LED output/bus, from /json/cfg's hw.led.ins
)
