package com.ketronkowski.xlights.preshow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class CameraCheckerTest {

    private val checker = CameraChecker(mock(), PreShowProperties(ha = PreShowProperties.HomeAssistant(phoneFeedEntity = "binary_sensor.x")))

    @Test
    fun `live phone that the stream uses passes`() {
        val r = checker.feedResult("on", "phone")
        assertEquals(Outcome.PASS, r.outcome)
        assertEquals("live, stream is using it", r.message)
    }

    @Test
    fun `dead phone warns with the Blackmagic hint and names the fallback`() {
        val r = checker.feedResult("off", "g6")
        assertEquals(Outcome.WARN, r.outcome)
        assertTrue(r.message.contains("stream is on g6"))
        assertTrue(r.message.contains("toggle Live Stream off and on"))
    }

    @Test
    fun `live phone while the stream is still on a fallback warns, unknown status warns`() {
        assertEquals(Outcome.WARN, checker.feedResult("on", "seed-01").outcome)
        assertTrue(checker.feedResult("unavailable", null).message.contains("unreachable"))
    }
}
