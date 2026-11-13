package com.ketronkowski.xlights.look

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// Entries are real /json/fxdata strings (WLED strips the name and '@').
class EffectControlsParserTest {

    @Test
    fun `labels sliders, checkbox, colors and defaults`() {
        val c = EffectControlsParser.parse(40, "!,Fade rate,,,,Smear;!,!;!;1;sx=24,pal=50")
        assertEquals(listOf(EffectControl("sx", "Effect speed"), EffectControl("ix", "Fade rate")), c.sliders)
        assertEquals(listOf(EffectControl("o1", "Smear")), c.checkboxes)
        assertEquals(listOf("Fx", "Bg", null), c.colors)
        assertTrue(c.usesPalette)
        assertEquals("Color palette", c.paletteLabel)
        assertEquals("1", c.flags)
        assertEquals(mapOf("sx" to 24, "pal" to 50), c.defaults)
        assertFalse(c.is2dOnly)
    }

    @Test
    fun `custom slider labels, three colors, no flags means 1D`() {
        val c = EffectControlsParser.parse(13, "!,Gap size;!,!,!;!")
        assertEquals(listOf("sx", "ix"), c.sliders.map { it.key })
        assertEquals("Gap size", c.sliders[1].label)
        assertEquals(listOf("Fx", "Bg", "Cs"), c.colors)
        assertEquals("1", c.flags)
        assertTrue(c.defaults.isEmpty())
    }

    @Test
    fun `effect with control data but no colors hides every color slot`() {
        val c = EffectControlsParser.parse(9, "!,Size;;!")
        assertEquals(listOf<String?>(null, null, null), c.colors)
        assertTrue(c.usesPalette)
    }

    @Test
    fun `numeric or empty palette field means palette not used`() {
        assertFalse(EffectControlsParser.parse(2, "!,Duty cycle;!,!;0;01").usesPalette)
        val noPal = EffectControlsParser.parse(3, "!,!;!,!;;01")
        assertFalse(noPal.usesPalette)
        assertNull(noPal.paletteLabel)
    }

    @Test
    fun `custom palette label strips an embedded default`() {
        val c = EffectControlsParser.parse(70, "!;!;Clouds=5;1")
        assertTrue(c.usesPalette)
        assertEquals("Clouds", c.paletteLabel)
    }

    @Test
    fun `custom checkbox labels and custom sliders`() {
        val c = EffectControlsParser.parse(76, "!,!,Width,,Blur,!,Overlay,;!,!;!")
        assertEquals(listOf("sx", "ix", "c1", "c3"), c.sliders.map { it.key })
        assertEquals("Width", c.sliders[2].label)
        assertEquals("Blur", c.sliders[3].label)
        assertEquals(listOf(EffectControl("o1", "Check 1"), EffectControl("o2", "Overlay")), c.checkboxes)
    }

    @Test
    fun `empty entry shows speed and intensity, numbered color slots and the palette`() {
        val c = EffectControlsParser.parse(5, "")
        assertEquals(listOf("sx", "ix"), c.sliders.map { it.key })
        assertEquals(listOf("1", "2", "3"), c.colors)
        assertTrue(c.usesPalette)
        assertTrue(c.checkboxes.isEmpty())
    }

    @Test
    fun `empty entry for an id of 128 or more shows all five sliders`() {
        assertEquals(5, EffectControlsParser.parse(130, "").sliders.size)
    }

    @Test
    fun `Solid shows one color and nothing else`() {
        val c = EffectControlsParser.parse(0, "")
        assertTrue(c.sliders.isEmpty())
        assertEquals(listOf("Fx", null, null), c.colors)
        assertFalse(c.usesPalette)
        assertEquals("", c.flags)
    }

    @Test
    fun `2D-only effects are flagged, 1D-and-2D ones are not`() {
        assertTrue(EffectControlsParser.parse(150,
            "Fade rate,Outer Y freq.,Outer X freq.,Inner X freq.,Inner Y freq.,Solid,,Blur;;;2;pal=11").is2dOnly)
        assertFalse(EffectControlsParser.parse(151, "!,!;!;!;12").is2dOnly)
        assertFalse(EffectControlsParser.parse(152, "!,!;!;!;1v").is2dOnly)
    }
}
