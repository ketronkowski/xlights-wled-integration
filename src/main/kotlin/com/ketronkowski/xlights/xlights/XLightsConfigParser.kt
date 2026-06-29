package com.ketronkowski.xlights.xlights

import com.ketronkowski.xlights.domain.XLightsController
import com.ketronkowski.xlights.domain.XLightsModel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists

@Component
class XLightsConfigParser {

    private val log = LoggerFactory.getLogger(XLightsConfigParser::class.java)

    fun parse(showDir: Path): List<XLightsController> {
        val networksFile = showDir.resolve("xlights_networks.xml")
        val effectsFile  = showDir.resolve("xlights_rgbeffects.xml")
        require(networksFile.exists()) { "xlights_networks.xml not found in $showDir" }
        require(effectsFile.exists())  { "xlights_rgbeffects.xml not found in $showDir" }

        val controllers = parseControllers(networksFile)
        val models      = parseModels(effectsFile)

        return controllers.map { controller ->
            val assigned = models
                .filter { it.controllerName.equals(controller.name, ignoreCase = true) }
                .sortedBy { it.startChannel }
            controller.copy(models = assigned)
        }
    }

    // ── Networks file ────────────────────────────────────────────────────────

    private fun parseControllers(file: Path): List<XLightsController> {
        val doc = buildDoc(file)
        val controllers = mutableListOf<XLightsController>()

        // xLights stores controllers as <Controller> elements under <Networks>.
        // Attribute names: Name, IP, Protocol (or Type), MaxChannels (or Channels).
        // Verify these match your xLights version if parsing returns empty results.
        val nodes: NodeList = doc.getElementsByTagName("Controller")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("Name")
            if (name.isBlank()) continue

            val ip       = el.getAttribute("IP").takeIf { it.isNotBlank() }
            val protocol = el.getAttribute("Protocol").ifBlank { el.getAttribute("Type") }
            val channels = el.getAttribute("MaxChannels").toIntOrNull()
                ?: el.getAttribute("Channels").toIntOrNull()
                ?: 0

            controllers += XLightsController(
                name          = name,
                ipAddress     = ip,
                protocol      = protocol,
                totalChannels = channels,
                models        = emptyList(),
            )
            log.debug("Parsed controller: {}  protocol={}  ip={}  channels={}", name, protocol, ip, channels)
        }

        if (controllers.isEmpty()) {
            log.warn("No <Controller> elements found in xlights_networks.xml — check your xLights version's XML schema")
        }
        return controllers
    }

    // ── Effects file ─────────────────────────────────────────────────────────

    private fun parseModels(file: Path): List<XLightsModel> {
        val doc = buildDoc(file)
        val models = mutableListOf<XLightsModel>()

        // xLights stores models as <model> elements (lowercase) inside <models>.
        // Key attributes: name, Controller, StartChannel, and size params.
        // StartChannel may be a plain integer or "N>ControllerName:Port".
        val nodes: NodeList = doc.getElementsByTagName("model")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue

            val name           = el.getAttribute("name")
            if (name.isBlank()) continue
            val controllerName = el.getAttribute("Controller")
            if (controllerName.isBlank()) continue

            val startChannel = resolveStartChannel(el.getAttribute("StartChannel"), name) ?: continue
            val channelCount = resolveChannelCount(el, name) ?: continue
            val isNull       = name.contains("null", ignoreCase = true)

            models += XLightsModel(
                name           = name,
                controllerName = controllerName,
                startChannel   = startChannel,
                channelCount   = channelCount,
                isNull         = isNull,
            )
            log.debug("Parsed model: {}  ctrl={}  start={}  ch={}  null={}", name, controllerName, startChannel, channelCount, isNull)
        }
        return models
    }

    // ── StartChannel parsing ─────────────────────────────────────────────────

    // Handles:
    //   "301"             → 301  (absolute integer — most common after xLights saves layout)
    //   "1>CtrlName:2"   → 1    (channel offset within controller port)
    //   ">PreviousModel" → null  (chained reference — unsupported, save layout first)
    private fun resolveStartChannel(raw: String, modelName: String): Int? {
        if (raw.isBlank()) {
            log.warn("Model '{}' has no StartChannel — skipping", modelName)
            return null
        }
        raw.toIntOrNull()?.let { return it }

        val gtFormat = Regex("""^(\d+)>(.+):\d+$""")
        gtFormat.matchEntire(raw.trim())?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }

        log.warn("Model '{}' has unsupported StartChannel '{}' — skipping. " +
            "Save your xLights layout to resolve chained references to absolute values.", modelName, raw)
        return null
    }

    // ── Channel count computation ────────────────────────────────────────────

    // xLights computes channel count from model geometry; no single "ChannelCount" attribute exists
    // in all model types. Priority order checked below — adjust if your model type differs.
    //   StringType="RGB Nodes"  → 3 bytes/node
    //   StringType="RGBW Nodes" → 4 bytes/node
    private fun resolveChannelCount(el: Element, modelName: String): Int? {
        // Some model types store it explicitly
        el.getAttribute("ChannelCount").toIntOrNull()?.takeIf { it > 0 }?.let { return it }

        val bytesPerNode = if (el.getAttribute("StringType").contains("RGBW", ignoreCase = true)) 4 else 3

        // Custom models use NodeCount
        val nodeCount = el.getAttribute("NodeCount").toIntOrNull()
            ?: el.getAttribute("Nodes").toIntOrNull()
        if (nodeCount != null && nodeCount > 0) return nodeCount * bytesPerNode

        // Standard models: StringCount × parm1 (nodes/string)
        val strings  = el.getAttribute("StringCount").toIntOrNull() ?: 1
        val perStr   = el.getAttribute("parm1").toIntOrNull() ?: 0
        if (perStr > 0) return strings * perStr * bytesPerNode

        log.warn("Cannot determine channel count for model '{}' — skipping", modelName)
        return null
    }

    private fun buildDoc(file: Path) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
            .also { it.documentElement.normalize() }
}
