package com.ketronkowski.xlights.look

/**
 * The look applied to every selected controller. Effect and palette are names, not
 * ids: ids can differ between firmware builds, so the backend maps them per controller.
 */
data class Look(
    val on: Boolean = true,
    val bri: Int = 128,
    val fx: String = "Solid",
    val sx: Int = 128,
    val ix: Int = 128,
    val c1: Int = 128,
    val c2: Int = 128,
    val c3: Int = 16,
    val o1: Boolean = false,
    val o2: Boolean = false,
    val o3: Boolean = false,
    val pal: String = "Default",
    val col: List<List<Int>> = listOf(listOf(255, 160, 0), listOf(0, 0, 0), listOf(0, 0, 0)),
)

data class LookTarget(val name: String, val ip: String? = null)

data class ApplyLookRequest(
    val controllers: List<LookTarget> = emptyList(),
    val look: Look = Look(),
)

/** Power switch: takes effect at once, without Apply. */
data class PowerRequest(
    val controllers: List<LookTarget> = emptyList(),
    val on: Boolean = true,
)

data class ApplyLookOutcome(
    val name: String,
    val ip: String?,
    val success: Boolean,
    val error: String? = null,
)

data class CatalogEffect(
    val name: String,
    val controls: EffectControls,
    /** The effect's default palette as a name (fxdata defaults carry an id), if it has one. */
    val defaultPalette: String?,
)

data class LookCatalog(
    /** Effects every reachable controller has: Solid first, then alphabetical. 2D-only and audio-reactive effects left out. */
    val effects: List<CatalogEffect>,
    /** Palettes every reachable controller has: Default, the "* ..." specials, then alphabetical. */
    val palettes: List<String>,
    /** The first reachable controller's main segment, to pre-fill the dialog. */
    val current: Look?,
    /** IPs of controllers currently receiving show data (the look won't show until it stops). */
    val live: List<String>,
    /** ip → whether the controller's power is on right now. */
    val powered: Map<String, Boolean> = emptyMap(),
    /**
     * Controllers whose current look (effect, options, colors, palette; not power or
     * brightness) isn't the same as the first controller's, which pre-fills the dialog.
     * ip → that controller's current effect name.
     */
    val differing: Map<String, String> = emptyMap(),
    /** ip → why it could not be reached. */
    val unreachable: Map<String, String>,
)
