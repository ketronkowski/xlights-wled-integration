package com.ketronkowski.xlights.wled.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Regression test for a real bug: per-output LED bus config on real WLED
// firmware lives at hw.led.ins, not hw.com (which is WLED's serial/COM port
// config and is empty on every real device checked). This JSON is trimmed
// from an actual QuinLED-Dig-Octa /json/cfg response — if this ever starts
// failing, WledCfgResponse has drifted from the real WLED API shape again.
class WledDtosTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val realCfgJson = """
        {
          "rev": [1, 0],
          "hw": {
            "led": {
              "total": 1620,
              "ins": [
                { "start": 0,   "len": 198, "pin": [0], "type": 22 },
                { "start": 198, "len": 367, "pin": [1], "type": 22 },
                { "start": 565, "len": 357, "pin": [2], "type": 22 },
                { "start": 922, "len": 698, "pin": [3], "type": 22 }
              ]
            },
            "com": []
          },
          "ota": { "lock": true }
        }
    """.trimIndent()

    @Test
    fun `parses per-output bus config from hw-led-ins, ignoring unrelated fields`() {
        val cfg = objectMapper.readValue(realCfgJson, WledCfgResponse::class.java)

        assertEquals(
            listOf(
                WledBusConfig(start = 0, len = 198, type = 22),
                WledBusConfig(start = 198, len = 367, type = 22),
                WledBusConfig(start = 565, len = 357, type = 22),
                WledBusConfig(start = 922, len = 698, type = 22),
            ),
            cfg.hw.led.ins,
        )
    }
}
