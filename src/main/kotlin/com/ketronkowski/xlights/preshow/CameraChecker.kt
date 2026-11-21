package com.ketronkowski.xlights.preshow

import org.springframework.stereotype.Component

/**
 * Live-stream camera: is the iPhone feed live, and is the stream using it or a
 * fallback camera? Read from Home Assistant, which polls the stream box's status
 * endpoint. A dead phone is a warning, not a blocker: the stream falls back on its own.
 */
@Component
class CameraChecker(
    private val ha: HomeAssistantClient,
    private val props: PreShowProperties,
) : PreShowChecker {
    override val layer = "Camera"
    private val cfg get() = props.ha

    override fun check(ctx: CheckContext): List<CheckResult> {
        if (cfg.phoneFeedEntity.isBlank()) return emptyList()
        if (!ha.configured) return listOf(CheckResult.skip(layer, "iPhone feed", "iPhone", "Home Assistant is not configured"))
        val phone = try {
            ha.state(cfg.phoneFeedEntity).path("state").asText("unknown")
        } catch (e: Exception) {
            return listOf(CheckResult.warn(layer, "iPhone feed", "iPhone", "feed status not readable (${e.brief()})"))
        }
        val source = if (cfg.feedSourceEntity.isBlank()) null else try {
            ha.state(cfg.feedSourceEntity).path("state").asText("").ifBlank { null }
        } catch (_: Exception) {
            null
        }
        return listOf(feedResult(phone, source))
    }

    internal fun feedResult(phone: String, source: String?): CheckResult {
        val usingPhone = source?.equals(cfg.phoneSourceName, ignoreCase = true)
        return when (phone) {
            "on" -> when (usingPhone) {
                false -> CheckResult.warn(layer, "iPhone feed", "iPhone", "iPhone feed is live, but the stream is still on $source")
                else -> CheckResult.pass(layer, "iPhone feed", "iPhone", "live" + if (usingPhone == true) ", stream is using it" else "")
            }
            "off" -> CheckResult.warn(layer, "iPhone feed", "iPhone",
                "iPhone feed is not live" + (source?.let { ", stream is on $it" } ?: "") +
                    ". Open Blackmagic Camera and toggle Live Stream off and on (it can show On Air after a server restart).")
            else -> CheckResult.warn(layer, "iPhone feed", "iPhone", "feed status is $phone: the stream box may be unreachable")
        }
    }
}
