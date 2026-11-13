package com.ketronkowski.xlights.look

/** One slider or checkbox an effect uses, with the label WLED shows for it. */
data class EffectControl(val key: String, val label: String)

/**
 * Which controls an effect uses, parsed from its `/json/fxdata` entry.
 *
 * @property colors one entry per color slot (always 3): the slot's label, or null when the
 *   effect hides that slot.
 * @property paletteLabel the palette list's label when the effect uses a palette, else null.
 * @property defaults values WLED pre-fills when the effect is picked (`sx=24,pal=50`).
 */
data class EffectControls(
    val sliders: List<EffectControl>,
    val checkboxes: List<EffectControl>,
    val colors: List<String?>,
    val usesPalette: Boolean,
    val paletteLabel: String?,
    val flags: String,
    val defaults: Map<String, Int>,
) {
    /** 2D-only effects need a matrix; WLED hides them on strips and so do we. */
    val is2dOnly: Boolean get() = flags.contains('2') && !flags.contains('1')
}

/**
 * Port of WLED's own `setEffectParameters()` / `populateEffects()` (wled00/data/index.js),
 * so the dialog shows exactly the controls WLED's UI would.
 *
 * `/json/fxdata` entries have the effect name and '@' already removed by the firmware
 * (`respondModeData` in json.cpp): `sliders;colors;palette;flags;defaults`, e.g.
 * `"!,Fade rate,,,,Smear;!,!;!;1;sx=24,pal=50"`.
 */
object EffectControlsParser {
    val SLIDER_KEYS = listOf("sx", "ix", "c1", "c2", "c3")
    val CHECKBOX_KEYS = listOf("o1", "o2", "o3")
    private val DEFAULT_SLIDER_LABELS = listOf("Effect speed", "Effect intensity", "Custom 1", "Custom 2", "Custom 3")
    private val DEFAULT_CHECKBOX_LABELS = listOf("Check 1", "Check 2", "Check 3")
    private val DEFAULT_COLOR_LABELS = listOf("Fx", "Bg", "Cs")

    // WLED's UI swaps Solid's entry for this: one color, no sliders, no palette.
    private const val SOLID_DATA = ";!;"

    fun parse(effectId: Int, raw: String): EffectControls {
        val data = if (effectId == 0) SOLID_DATA else raw
        val controlDefined = data.isNotEmpty()
        val parts = if (data.isEmpty()) emptyList() else data.split(";")
        fun field(i: Int): List<String> =
            if (parts.size <= i || parts[i].isEmpty()) emptyList() else parts[i].split(",")

        val sl = field(0)
        val co = field(1)
        val pa = field(2)

        // Sliders: no control data → speed + intensity (all 5 for ids >= 128, like WLED).
        val sliders = SLIDER_KEYS.indices.mapNotNull { i ->
            val entry = sl.getOrNull(i)
            val shown = (!controlDefined && i < (if (effectId < 128) 2 else SLIDER_KEYS.size)) ||
                (entry != null && entry.isNotEmpty())
            if (!shown) null
            else EffectControl(SLIDER_KEYS[i], if (entry == null || entry == "!" || entry.isEmpty()) DEFAULT_SLIDER_LABELS[i] else entry)
        }

        val checkboxes = if (sl.size > 5) CHECKBOX_KEYS.indices.mapNotNull { i ->
            val entry = sl.getOrNull(5 + i)
            if (entry.isNullOrEmpty()) null
            else EffectControl(CHECKBOX_KEYS[i], if (entry == "!") DEFAULT_CHECKBOX_LABELS[i] else entry)
        } else emptyList()

        val colors = (0 until 3).map { i ->
            val entry = co.getOrNull(i)
            when {
                entry != null && entry.isNotEmpty() -> if (entry == "!") DEFAULT_COLOR_LABELS[i] else entry
                !controlDefined -> "${i + 1}"
                else -> null
            }
        }

        // Palette: used when there's no control data, or the palette field is a
        // non-numeric marker ("!" or a custom label, optionally with "=default").
        var paletteLabel: String? = null
        val paFirst = pa.firstOrNull()
        val usesPalette = !controlDefined || (paFirst != null && paFirst.isNotEmpty() && paFirst.toDoubleOrNull() == null)
        if (usesPalette) {
            val label = paFirst?.substringBefore('=')
            paletteLabel = if (label.isNullOrEmpty() || label == "!") "Color palette" else label
        }

        val flags = when {
            effectId == 0 -> ""
            parts.size < 4 || parts[3].isEmpty() -> "1"
            else -> parts[3]
        }

        val defaults = linkedMapOf<String, Int>()
        parts.getOrNull(4)?.split(",")?.forEach { kv ->
            val k = kv.substringBefore('=', "").trim()
            val v = kv.substringAfter('=', "").trim().toIntOrNull()
            if (k.isNotEmpty() && v != null) defaults[k] = v
        }

        return EffectControls(sliders, checkboxes, colors, usesPalette, paletteLabel, flags, defaults)
    }
}
