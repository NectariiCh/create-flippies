package com.nectariich.create_flippies.content.procedural_gauge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * The edgewise face definition: how a value lands on the face, the mirroring, and
 * that a face survives a save and comes back sane from a tag anyone could have
 * written.
 */
class EdgewiseFaceDefinitionTest {

    /** The DHE sample face, BarGaugeDef_New, as its asset has it. */
    private static EdgewiseFaceDefinition sample() {
        return EdgewiseFaceDefinition.builder(-150, 60)
                .displayName("INCHES")
                .majorTickInterval(30)
                .minorTicksPerMajor(9)
                .faceAspect(4.887f)
                .scale(0.02f, 0.98f)
                .flipTicks(true)
                .tickEdge(0.477f)
                .tickLengths(0.19f, 0.08f)
                .tickWidths(0.022f, 0.011f)
                .spine(0.27f, true)
                .labelGap(0.03f)
                .labelSize(0.14f)
                .textSize(0.2f)
                .nameOnSide(true)
                .sideNameMargin(0.01f)
                .band(0.477f, 0.19f)
                .bands(new GaugeFaceDefinition.ColorBand(-150, 14, 0xFFA10000),
                        new GaugeFaceDefinition.ColorBand(54, 60, 0xFFA10000))
                .bakeResolution(1024)
                .build();
    }

    @Test
    void mapsValuesDownTheFaceAsTheUnityDefinitionDoes() {
        EdgewiseFaceDefinition face = sample();
        // Max at the top of the scale, min at the bottom, linear between
        assertEquals(0.02f, face.valueToFraction(60), 1e-6f);
        assertEquals(0.98f, face.valueToFraction(-150), 1e-6f);
        assertEquals(0.5f, face.valueToFraction(-45), 1e-6f);
        // Clamped off the scale, and a NaN reads as the bottom
        assertEquals(0.02f, face.valueToFraction(1000), 1e-6f);
        assertEquals(0.98f, face.valueToFraction(-1000), 1e-6f);
        assertEquals(0.98f, face.valueToFraction(Float.NaN), 1e-6f);
        // Progress is not clamped, so an overswing goes past the top of the scale
        assertTrue(face.progressToFraction(1.06f) < face.scaleTop);
        assertTrue(face.progressToFraction(-0.04f) > face.scaleBottom);
    }

    @Test
    void mirrorsHorizontalPositionsWhenFlipped() {
        EdgewiseFaceDefinition flipped = sample();
        assertEquals(0.523f, flipped.x(0.477f), 1e-6f);
        EdgewiseFaceDefinition plain = flipped.toBuilder().flipTicks(false).build();
        assertEquals(0.477f, plain.x(0.477f), 1e-6f);
    }

    @Test
    void survivesASaveExactly() {
        EdgewiseFaceDefinition face = sample().toBuilder()
                .units("in")
                .labelFormat("0.0")
                .labelMultiplier(10)
                .faceColor(0xEEEEE0)
                .markingColor(0x101010)
                .pointerColor(0x00FF00)
                .pointerStyle(EdgewiseFaceDefinition.PointerStyle.BAR)
                .pointerWidth(0.1f)
                .build();
        CompoundTag tag = face.toNbt();
        EdgewiseFaceDefinition read = EdgewiseFaceDefinition.fromNbt(tag);
        assertEquals(tag, read.toNbt());
        assertEquals(face.cacheKey(), read.cacheKey());
        assertEquals(EdgewiseFaceDefinition.PointerStyle.BAR, read.pointerStyle);
        assertEquals(0xFF00FF00, read.pointerColor);
        assertEquals(2, read.bands.length);
        assertTrue(read.flipTicks && read.nameOnSide && read.spineFullHeight);
    }

    @Test
    void readsAnEmptyTagAsTheDefaults() {
        EdgewiseFaceDefinition read = EdgewiseFaceDefinition.fromNbt(new CompoundTag());
        EdgewiseFaceDefinition defaults = EdgewiseFaceDefinition.builder(0, 100).build();
        assertEquals(defaults.toNbt(), read.toNbt());
        assertTrue(read.drawDisplayName);
    }

    @Test
    void sanitisesWhatItReads() {
        CompoundTag tag = sample().toNbt();
        tag.putFloat("Min", Float.NaN);
        tag.putFloat("TickEdge", 7);
        tag.putFloat("ScaleTop", 0.9f);
        tag.putFloat("ScaleBottom", 0.1f);
        tag.putFloat("Aspect", -3);
        tag.putInt("MinorTicks", 999);
        tag.putString("LabelFormat", "#.#####");
        tag.putFloat("LabelMultiplier", 0);
        tag.putInt("Resolution", 1 << 20);
        tag.putString("Name", "A|VERY\nLONG NAME THAT GOES ON AND ON");
        tag.putInt("FaceColor", 0x00123456);
        ListTag bands = new ListTag();
        for (int i = 0; i < 9; i++)
            bands.add(new CompoundTag());
        tag.put("Bands", bands);

        EdgewiseFaceDefinition read = EdgewiseFaceDefinition.fromNbt(tag);
        assertEquals(0, read.minValue);
        assertEquals(1, read.tickEdge);
        // The ends put back in order, max above min
        assertEquals(0.1f, read.scaleTop, 1e-6f);
        assertEquals(0.9f, read.scaleBottom, 1e-6f);
        assertEquals(0.25f, read.faceAspect);
        assertEquals(GaugeScale.MAX_MINORS, read.minorTicksPerMajor);
        assertEquals("0", read.labelFormat);
        assertEquals(1, read.labelMultiplier);
        assertEquals(1024, read.bakeResolution);
        assertEquals(24, read.displayName.length());
        assertFalse(read.displayName.contains("|") || read.displayName.contains("\n"));
        assertEquals(0xFF123456, read.faceColor);
        assertEquals(EdgewiseFaceDefinition.MAX_BANDS, read.bands.length);
    }

    @Test
    void thePointerIsNotPartOfThePrinting() {
        EdgewiseFaceDefinition face = sample();
        EdgewiseFaceDefinition repointed = face.toBuilder().pointerColor(0x0000FF)
                .pointerStyle(EdgewiseFaceDefinition.PointerStyle.BAR).build();
        assertEquals(face.cacheKey(), repointed.cacheKey());
        assertNotEquals(face.cacheKey(), face.toBuilder().units("mm").build().cacheKey());
    }

    @Test
    void everyVoltmeterRangeHasAnEdgewiseFaceOnItsOwnScale() {
        for (VoltmeterRange range : VoltmeterRange.values()) {
            EdgewiseFaceDefinition face = range.edgewiseFace();
            assertEquals(range.min(), face.minValue);
            assertEquals(range.max(), face.maxValue);
            assertEquals("V", face.units);
            assertEquals(4, face.faceAspect, 1e-6f);
            assertEquals(range.face().majorTickInterval, face.majorTickInterval);
            assertTrue(range.edgewiseBlockId().startsWith("edgewise_procedural_voltmeter_"));
        }
    }
}
