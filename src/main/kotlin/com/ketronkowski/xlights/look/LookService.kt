package com.ketronkowski.xlights.look

import com.ketronkowski.xlights.domain.ControllerValidation
import com.ketronkowski.xlights.domain.SegmentStatus
import com.ketronkowski.xlights.validation.ValidationService
import com.ketronkowski.xlights.wled.WledApiClient
import com.ketronkowski.xlights.wled.dto.WledInfoResponse
import com.ketronkowski.xlights.wled.dto.WledStateResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A controller's effect, palette and fxdata lists, each indexed by id. */
internal data class ControllerCatalog(
    val effects: List<String>,
    val palettes: List<String>,
    val fxData: List<String>,
)

/**
 * "Set Look": puts the same effect, colors and palette on every selected controller at once.
 * For use outside show hours: it never sends `lor`, so a running show always wins.
 */
@Service
class LookService(
    private val apiClient: WledApiClient,
    private val validationService: ValidationService,
    @Value("\${xlights.data-dir}") dataDir: String,
) {
    private val log = LoggerFactory.getLogger(LookService::class.java)
    private val xlightsDir: Path = Path.of(dataDir, "xlights")

    // Effects/palettes only change with a firmware update, so they're kept for the
    // life of the process. The dialog's Refresh clears them.
    private val catalogCache = ConcurrentHashMap<String, ControllerCatalog>()

    // ── Catalog ──────────────────────────────────────────────────────────────

    fun catalog(ips: List<String>, refresh: Boolean = false): LookCatalog {
        val targets = ips.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (refresh) targets.forEach { catalogCache.remove(it) }

        data class Fetched(val ip: String, val catalog: ControllerCatalog?, val info: WledInfoResponse?,
                           val state: WledStateResponse?, val error: String?)

        val fetched = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            targets.map { ip ->
                CompletableFuture.supplyAsync({
                    try {
                        val cat = controllerCatalog(ip)
                        Fetched(ip, cat, apiClient.fetchInfo(ip), apiClient.fetchState(ip), null)
                    } catch (e: Exception) {
                        log.warn("Set Look: cannot read catalog from {}: {}", ip, e.message)
                        Fetched(ip, null, null, null, e.message ?: e.javaClass.simpleName)
                    }
                }, pool)
            }.map { it.join() }
        }

        val reachable = fetched.filter { it.catalog != null }
        val unreachable = fetched.filter { it.catalog == null }.associate { it.ip to (it.error ?: "unreachable") }
        val first = reachable.firstOrNull()
            ?: return LookCatalog(emptyList(), emptyList(), null, emptyList(), unreachable = unreachable)

        val catalogs = reachable.map { it.catalog!! }
        val effects = commonEffects(catalogs)
        val palettes = commonPalettes(catalogs)
        val current = currentLook(first.state!!, first.catalog!!, effects.map { it.name }.toSet(), palettes.toSet())

        return LookCatalog(
            effects     = effects,
            palettes    = palettes,
            current     = current,
            live        = reachable.filter { it.info?.live == true }.map { it.ip },
            powered     = reachable.associate { it.ip to (it.state?.on ?: false) },
            unreachable = unreachable,
        )
    }

    private fun controllerCatalog(ip: String): ControllerCatalog =
        catalogCache[ip] ?: ControllerCatalog(
            effects  = apiClient.fetchEffects(ip),
            palettes = apiClient.fetchPalettes(ip),
            fxData   = apiClient.fetchFxData(ip),
        ).also { catalogCache[ip] = it }

    internal fun commonEffects(catalogs: List<ControllerCatalog>): List<CatalogEffect> {
        val first = catalogs.first()
        val shared = catalogs.drop(1).fold(first.effects.toSet()) { acc, c -> acc intersect c.effects.toSet() }
        val list = first.effects.mapIndexedNotNull { id, name ->
            if (name !in shared || name.isBlank() || name.contains("RSVD")) return@mapIndexedNotNull null
            val controls = EffectControlsParser.parse(id, first.fxData.getOrElse(id) { "" })
            if (controls.is2dOnly || controls.isAudioReactive) return@mapIndexedNotNull null
            CatalogEffect(name, controls, controls.defaults["pal"]?.let { first.palettes.getOrNull(it) })
        }.distinctBy { it.name }
        val (solid, rest) = list.partition { it.name == first.effects.firstOrNull() }
        return solid + rest.sortedBy { it.name.lowercase() }
    }

    internal fun commonPalettes(catalogs: List<ControllerCatalog>): List<String> {
        val first = catalogs.first()
        val shared = catalogs.drop(1).fold(first.palettes.toSet()) { acc, c -> acc intersect c.palettes.toSet() }
        val ordered = first.palettes.filter { it in shared && it.isNotBlank() }.distinct()
        val head = ordered.take(1)                       // "Default" (id 0)
        val specials = ordered.drop(1).filter { it.startsWith("*") }
        val named = ordered.drop(1).filterNot { it.startsWith("*") }.sortedBy { it.lowercase() }
        return head + specials + named
    }

    private fun currentLook(
        state: WledStateResponse,
        cat: ControllerCatalog,
        effectNames: Set<String>,
        paletteNames: Set<String>,
    ): Look {
        val seg = state.seg.find { it.id == state.mainseg } ?: state.seg.firstOrNull()
            ?: return Look(on = state.on, bri = state.bri)
        val fx = cat.effects.getOrNull(seg.fx)?.takeIf { it in effectNames } ?: "Solid"
        val pal = cat.palettes.getOrNull(seg.pal)?.takeIf { it in paletteNames } ?: (cat.palettes.firstOrNull() ?: "Default")
        return Look(
            on = state.on, bri = state.bri.coerceIn(1, 255),
            fx = fx, sx = seg.sx, ix = seg.ix, c1 = seg.c1, c2 = seg.c2, c3 = seg.c3,
            o1 = seg.o1, o2 = seg.o2, o3 = seg.o3,
            pal = pal,
            col = sanitizeColors(seg.col),
        )
    }

    // ── Apply ────────────────────────────────────────────────────────────────

    fun apply(req: ApplyLookRequest): List<ApplyLookOutcome> {
        if (req.controllers.isEmpty()) return emptyList()
        // Re-check sync on the server: the page may be showing a stale status.
        val report = validationService.validate(xlightsDir, 0, req.controllers.map { it.name }.toSet())

        data class Prepared(val name: String, val ip: String?, val body: Map<String, Any?>?, val error: String?)

        return Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val prepared = req.controllers.map { target ->
                CompletableFuture.supplyAsync({
                    val cv = report.controllerValidations.find { it.xLightsController.name.equals(target.name, ignoreCase = true) }
                    try {
                        cv ?: error("not a WLED controller in the uploaded xLights config")
                        notReadyReason(cv)?.let { error(it) }
                        val ip = cv.wledDevice!!.ipAddress
                        Prepared(target.name, ip, buildBody(cv, controllerCatalog(ip), req.look), null)
                    } catch (e: Exception) {
                        Prepared(target.name, cv?.wledDevice?.ipAddress ?: target.ip, null, e.message ?: e.javaClass.simpleName)
                    }
                }, pool)
            }.map { it.join() }

            // Every POST goes out at once so the controllers start the look within a few ms.
            prepared.map { p ->
                if (p.body == null) CompletableFuture.completedFuture(ApplyLookOutcome(p.name, p.ip, false, p.error))
                else CompletableFuture.supplyAsync({
                    try {
                        apiClient.postState(p.ip!!, p.body)
                        ApplyLookOutcome(p.name, p.ip, true)
                    } catch (e: Exception) {
                        log.warn("Set Look failed on '{}' ({}): {}", p.name, p.ip, e.message)
                        ApplyLookOutcome(p.name, p.ip, false, e.message ?: e.javaClass.simpleName)
                    }
                }, pool)
            }.map { it.join() }
        }
    }

    /**
     * The dialog's power switch: turns the controllers on or off right away, leaving
     * their effect and colors alone. Like Apply, it never overrides a running show.
     */
    fun power(req: PowerRequest): List<ApplyLookOutcome> {
        val body = powerBody(req.on)
        return Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            req.controllers.map { t ->
                CompletableFuture.supplyAsync({
                    try {
                        val ip = t.ip?.takeIf { it.isNotBlank() } ?: error("no address for this controller")
                        apiClient.postState(ip, body)
                        ApplyLookOutcome(t.name, ip, true)
                    } catch (e: Exception) {
                        log.warn("Set Look power {} failed on '{}' ({}): {}", req.on, t.name, t.ip, e.message)
                        ApplyLookOutcome(t.name, t.ip, false, e.message ?: e.javaClass.simpleName)
                    }
                }, pool)
            }.map { it.join() }
        }
    }

    internal fun powerBody(on: Boolean): Map<String, Any?> =
        linkedMapOf("on" to on, "tt" to 0, "udpn" to mapOf("nn" to true))

    /**
     * Why a controller can't take a look, or null when it can: it must be paired with
     * xLights, have the LED count xLights expects, and have every segment in sync.
     */
    internal fun notReadyReason(cv: ControllerValidation): String? {
        val device = cv.wledDevice ?: return "controller is not reachable"
        if (!cv.totalLedsMatch) {
            return "LED count differs (WLED has ${device.totalLeds}, xLights expects ${cv.expectedTotalLeds}); " +
                "fix it in WLED's LED Preferences"
        }
        cv.segmentLimitWarning?.let { return it }
        val segIssues = cv.segmentValidations.count { it.status != SegmentStatus.OK } + cv.orphanSegments.size
        if (segIssues > 0) return "$segIssues segment(s) out of sync with xLights; run Update Selected first"
        return null
    }

    /**
     * The one `POST /json/state` body for a controller. Segments that match a non-null
     * xLights model get the whole look; every other segment is turned off. Every segment
     * is listed with its `id`, because WLED applies an id-less array entry by position.
     * Power off still sets the effect and colors, so they're there when the lights come on.
     */
    internal fun buildBody(cv: ControllerValidation, cat: ControllerCatalog, look: Look): Map<String, Any?> {
        val noSync = mapOf("nn" to true)   // don't let UDP sync re-broadcast to unselected controllers
        val device = cv.wledDevice ?: error("controller is not reachable")
        val fx = cat.effects.indexOf(look.fx).takeIf { it >= 0 }
            ?: error("effect '${look.fx}' is not on this controller")
        val pal = cat.palettes.indexOf(look.pal).takeIf { it >= 0 }
            ?: error("palette '${look.pal}' is not on this controller")

        val lookSegmentIds = cv.segmentValidations
            .filter { !it.model.isNull }
            .mapNotNull { it.actualSegment?.id }
            .toSet()
        val colors = sanitizeColors(look.col)

        val segments = device.segments.sortedBy { it.id }.map { seg ->
            if (seg.id in lookSegmentIds) linkedMapOf(
                "id" to seg.id, "on" to true, "frz" to false,
                "fx" to fx,
                "sx" to look.sx.coerceIn(0, 255), "ix" to look.ix.coerceIn(0, 255),
                "c1" to look.c1.coerceIn(0, 255), "c2" to look.c2.coerceIn(0, 255), "c3" to look.c3.coerceIn(0, 31),
                "o1" to look.o1, "o2" to look.o2, "o3" to look.o3,
                "pal" to pal,
                "col" to colors,
            ) else linkedMapOf("id" to seg.id, "on" to false)
        }

        return linkedMapOf(
            "on" to look.on,
            "bri" to look.bri.coerceIn(1, 255),
            "tt" to 0,   // apply instantly, so controllers don't drift during a crossfade
            "tb" to 0,   // reset the effect timebase so every controller starts in phase
            "udpn" to noSync,
            "seg" to segments,
        )
    }

    internal fun sanitizeColors(col: List<List<Int>>): List<List<Int>> =
        (0 until 3).map { i ->
            val c = col.getOrNull(i) ?: emptyList()
            (0 until 3).map { ch -> (c.getOrNull(ch) ?: 0).coerceIn(0, 255) }
        }
}
