package com.ketronkowski.xlights.measure

import com.fasterxml.jackson.databind.ObjectMapper
import com.ketronkowski.xlights.domain.WledDevice
import com.ketronkowski.xlights.wled.WledApiClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText

/** A run of pixels to light, relative to the start of the port (stop is exclusive). */
data class PaintRange(val start: Int, val stop: Int, val color: String)

/** Where a port's pixels live on the device: its hardware bus and the segment that covers it. */
data class MeasureTarget(
    val ip: String,
    val port: Int,          // 1-based output number, as labelled on the board
    val busStart: Int,
    val busLen: Int,
    val segmentId: Int,
    val segmentStart: Int,
    val segmentStop: Int,
)

/**
 * Drives the "boundary finder": lights a port's pixels in measuring colors so you can see,
 * from the yard, exactly where each window's frame begins and ends.
 *
 * Everything it does on the device is temporary state, never config:
 *  - `lor: 2` makes WLED ignore live DDP/E1.31 data until rebooted or reset (so FPP or
 *    xLights can't paint over the measuring colors),
 *  - `seg.i` sets individual pixels, which freezes that segment,
 *  - the global brightness is turned down.
 * [stop] puts all three back. A reboot also clears them.
 */
@Service
class MeasureService(
    private val apiClient: WledApiClient,
    private val objectMapper: ObjectMapper,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(MeasureService::class.java)
    private val planFile: Path = Path.of(dataDir, "measure", "plan.json")

    private data class Session(val target: MeasureTarget, val originalOn: Boolean, val originalBri: Int)

    private val sessions = ConcurrentHashMap<String, Session>()

    fun start(ip: String, port: Int, brightness: Int): MeasureTarget {
        val device = apiClient.fetchDevice(ip)
        val target = resolveTarget(device, port)
        // Keep the brightness from before the first start, so restarting on another port
        // doesn't make the measuring brightness the "original".
        val previous = sessions[ip]
        val original = previous?.let { it.originalOn to it.originalBri }
            ?: apiClient.fetchState(ip).let { it.on to it.bri }
        if (previous != null && previous.target.segmentId != target.segmentId) {
            unfreeze(ip, previous.target.segmentId)
        }
        sessions[ip] = Session(target, original.first, original.second)
        apiClient.postState(ip, mapOf("on" to true, "bri" to clampBri(brightness), "lor" to 2))
        log.info("Measuring session started on {} port {} (segment {})", ip, port, target.segmentId)
        return target
    }

    fun paint(ip: String, ranges: List<PaintRange>, brightness: Int?) {
        val session = sessions[ip] ?: throw IllegalArgumentException("No measuring session on $ip; start one first")
        val body = buildMap<String, Any?> {
            put("on", true)
            if (brightness != null) put("bri", clampBri(brightness))
            put("seg", listOf(mapOf("id" to session.target.segmentId, "i" to individualLeds(session.target, ranges))))
        }
        apiClient.postState(ip, body)
    }

    fun stop(ip: String) {
        val session = sessions.remove(ip) ?: return
        unfreeze(ip, session.target.segmentId)
        apiClient.postState(ip, mapOf("on" to session.originalOn, "bri" to session.originalBri, "lor" to 0))
        log.info("Measuring session on {} stopped and restored", ip)
    }

    fun activeSessions(): List<MeasureTarget> = sessions.values.map { it.target }

    // ── Plan + results persistence ──────────────────────────────────────────

    fun loadPlan(): String =
        if (planFile.exists()) planFile.readText() else defaultPlan()

    fun savePlan(json: String) {
        require(json.length <= MAX_PLAN_BYTES) { "Plan is larger than $MAX_PLAN_BYTES bytes" }
        val node = try {
            objectMapper.readTree(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Plan is not valid JSON: ${e.message}")
        }
        require(node != null && node.isObject && node.has("controllers")) { "Plan must be a JSON object with a 'controllers' list" }
        Files.createDirectories(planFile.parent)
        Files.writeString(planFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node))
    }

    fun resetPlan(): String {
        Files.deleteIfExists(planFile)
        return defaultPlan()
    }

    private fun defaultPlan(): String =
        javaClass.getResourceAsStream("/measure/default-plan.json")!!.bufferedReader().readText()

    private fun unfreeze(ip: String, segmentId: Int) {
        apiClient.postState(ip, mapOf("seg" to listOf(mapOf("id" to segmentId, "frz" to false))))
    }

    companion object {
        const val MAX_PLAN_BYTES = 256 * 1024
        private val HEX = Regex("^[0-9A-Fa-f]{6}$")

        private fun clampBri(b: Int) = b.coerceIn(1, 255)

        /** Finds the hardware bus for a 1-based port, and the single segment that covers all of it. */
        fun resolveTarget(device: WledDevice, port: Int): MeasureTarget {
            val bus = device.busses.getOrNull(port - 1)
                ?: throw IllegalArgumentException("${device.name} has ${device.busses.size} outputs; there is no port $port")
            val busStop = bus.start + bus.len
            val seg = device.segments.firstOrNull { it.start <= bus.start && it.stop >= busStop }
                ?: throw IllegalArgumentException(
                    "No single WLED segment on ${device.name} covers port $port (LEDs ${bus.start}-${busStop - 1}). " +
                        "Make one segment span the whole port, then try again.")
            return MeasureTarget(device.ipAddress, port, bus.start, bus.len, seg.id, seg.start, seg.stop)
        }

        /**
         * Builds WLED's `seg.i` array: first the whole port black, then each range, as
         * `[start, stop, "RRGGBB"]` triples relative to the segment start.
         */
        fun individualLeds(target: MeasureTarget, ranges: List<PaintRange>): List<Any> {
            val offset = target.busStart - target.segmentStart
            val out = mutableListOf<Any>(offset, offset + target.busLen, "000000")
            for (r in ranges) {
                require(HEX.matches(r.color)) { "Color '${r.color}' must be 6 hex digits" }
                val start = r.start.coerceAtLeast(0)
                val stop = r.stop.coerceAtMost(target.busLen)
                if (stop <= start) continue
                out += offset + start
                out += offset + stop
                out += r.color.uppercase()
            }
            return out
        }
    }
}
