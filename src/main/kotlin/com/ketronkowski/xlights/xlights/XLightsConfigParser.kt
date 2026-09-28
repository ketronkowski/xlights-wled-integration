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

    // Schema (observed in xLights 2026):
    //   <Controller Name="Octa1" IP="wled-octa1.local" ...>
    //     <network MaxChannels="4860" .../>
    //   </Controller>
    private fun parseControllers(file: Path): List<XLightsController> {
        val doc = buildDoc(file)
        val controllers = mutableListOf<XLightsController>()

        val nodes: NodeList = doc.getElementsByTagName("Controller")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("Name")
            if (name.isBlank()) continue

            val ip       = el.getAttribute("IP").takeIf { it.isNotBlank() }
            val protocol = el.getAttribute("Protocol").ifBlank { el.getAttribute("Type") }
            val vendor   = el.getAttribute("Vendor")

            // MaxChannels lives on the first child <network> element
            val networkEl = el.getElementsByTagName("network").item(0) as? Element
            val channels  = networkEl?.getAttribute("MaxChannels")?.toIntOrNull()
                ?: el.getAttribute("MaxChannels").toIntOrNull()
                ?: 0

            controllers += XLightsController(
                name          = name,
                ipAddress     = ip,
                protocol      = protocol,
                totalChannels = channels,
                models        = emptyList(),
            )
            log.debug("Parsed controller: {}  vendor={}  ip={}  channels={}", name, vendor, ip, channels)
        }

        if (controllers.isEmpty()) {
            log.warn("No <Controller> elements found in xlights_networks.xml")
        }
        return controllers
    }

    // ── Effects file ─────────────────────────────────────────────────────────

    // Schema (observed in xLights 2026):
    //   <model name="Eaves - Garage"
    //          Controller="Octa1"
    //          StartChannel="!Octa1:1"
    //          NumStrings="1"
    //          NodesPerString="148"
    //          StringType="RGB Nodes"
    //          LayoutGroup="Non Null" | "Nulls" />
    private fun parseModels(file: Path): List<XLightsModel> {
        val doc = buildDoc(file)
        val models = mutableListOf<XLightsModel>()

        val nodes: NodeList = doc.getElementsByTagName("model")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name           = el.getAttribute("name")
            if (name.isBlank()) continue
            val controllerName = el.getAttribute("Controller")
            if (controllerName.isBlank()) continue

            val startChannel = resolveStartChannel(el.getAttribute("StartChannel"), name) ?: continue
            val channelCount = resolveChannelCount(el, name) ?: continue

            // LayoutGroup="Nulls" is the authoritative marker for gap/placeholder models
            val layoutGroup = el.getAttribute("LayoutGroup")
            val isNull      = layoutGroup.equals("Nulls", ignoreCase = true)
                || name.startsWith("Null", ignoreCase = true)

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

    // Formats observed:
    //   "!Octa1:1"          → 1     (controller-relative, most common in 2026 xLights)
    //   "!Octa3:3892"       → 3892
    //   "301"               → 301   (absolute integer, older format)
    //   ">PreviousModel"    → null  (chained; cannot resolve statically — skip)
    private fun resolveStartChannel(raw: String, modelName: String): Int? {
        if (raw.isBlank()) {
            log.warn("Model '{}' has no StartChannel — skipping", modelName)
            return null
        }

        // "!ControllerName:ChannelNum" — standard xLights controller-relative format
        if (raw.startsWith("!")) {
            val colon = raw.lastIndexOf(':')
            if (colon > 0) {
                return raw.substring(colon + 1).toIntOrNull()
                    ?: run {
                        log.warn("Model '{}' has malformed StartChannel '{}' — skipping", modelName, raw)
                        null
                    }
            }
        }

        // Plain integer (absolute channel, older format)
        raw.toIntOrNull()?.let { return it }

        // "N>ControllerName:Port" — legacy relative format
        val gtFormat = Regex("""^(\d+)>(.+):\d+$""")
        gtFormat.matchEntire(raw.trim())?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }

        log.warn("Model '{}' has unsupported StartChannel '{}' — skipping. " +
            "Save your xLights layout to resolve chained references.", modelName, raw)
        return null
    }

    // ── Channel count computation ────────────────────────────────────────────

    // Priority:
    //   1. NumStrings × NodesPerString (Single Line, Tree, etc.)
    //   2. Window Frame: TopNodes + BottomNodes + 2×SideNodes
    //   3. Custom model: parse CustomModelCompressed (triplets col,row,pixelNum) or CustomModel (grid)
    //   4. NodeCount / Nodes explicit count
    //   5. StringCount × parm1 (legacy)
    private fun resolveChannelCount(el: Element, modelName: String): Int? {
        val bytesPerNode = if (el.getAttribute("StringType").contains("RGBW", ignoreCase = true)) 4 else 3

        // Standard: NumStrings × NodesPerString
        val numStrings     = el.getAttribute("NumStrings").toIntOrNull()
        val nodesPerString = el.getAttribute("NodesPerString").toIntOrNull()
        if (numStrings != null && nodesPerString != null && numStrings > 0 && nodesPerString > 0) {
            return numStrings * nodesPerString * bytesPerNode
        }

        // Window Frame: TopNodes + BottomNodes + 2×SideNodes
        if (el.getAttribute("DisplayAs").equals("Window Frame", ignoreCase = true)) {
            val top    = el.getAttribute("TopNodes").toIntOrNull() ?: 0
            val bottom = el.getAttribute("BottomNodes").toIntOrNull() ?: 0
            val side   = el.getAttribute("SideNodes").toIntOrNull() ?: 0
            val total  = top + bottom + side * 2
            if (total > 0) return total * bytesPerNode
        }

        // Custom model (compressed triplets): "col,row,pixelNum;col,row,pixelNum;..."
        // The max pixelNum across all triplets = total node count.
        val compressed = el.getAttribute("CustomModelCompressed")
        if (compressed.isNotBlank()) {
            val maxPixel = compressed.splitToSequence(';')
                .mapNotNull { triplet -> triplet.substringAfterLast(',').toIntOrNull() }
                .filter { it > 0 }
                .maxOrNull()
            if (maxPixel != null) return maxPixel * bytesPerNode
        }

        // Custom model (grid): rows separated by ";", cells by ",". Max positive value = node count.
        val customModel = el.getAttribute("CustomModel")
        if (customModel.isNotBlank()) {
            val maxPixel = customModel.splitToSequence(';', ',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }
                .maxOrNull()
            if (maxPixel != null) return maxPixel * bytesPerNode
        }

        // Explicit node count
        val nodeCount = el.getAttribute("NodeCount").toIntOrNull()
            ?: el.getAttribute("Nodes").toIntOrNull()
        if (nodeCount != null && nodeCount > 0) return nodeCount * bytesPerNode

        // Legacy parm1-based models (StringCount × parm1)
        val strings = el.getAttribute("StringCount").toIntOrNull() ?: 1
        val perStr  = el.getAttribute("parm1").toIntOrNull() ?: 0
        if (perStr > 0) return strings * perStr * bytesPerNode

        log.warn("Cannot determine channel count for model '{}' — skipping", modelName)
        return null
    }

    private fun buildDoc(file: Path) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
            .also { it.documentElement.normalize() }
}
