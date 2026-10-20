package com.nectariich.create_flippies.content.procedural_gauge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The edgewise face layout, against a monospaced stand-in for a font: where the
 * bands, spine and ticks go, which side the labels hang from, and how the name and
 * units are fitted, unflipped and mirrored.
 */
class EdgewiseLayoutTest {

    /** Every character six tenths of the size across. */
    private static final EdgewiseLayout.Measure MONO = (text, px) -> text.length() * px * 0.6f;

    private static EdgewiseFaceDefinition.Builder face() {
        return EdgewiseFaceDefinition.builder(0, 10)
                .majorTickInterval(5)
                .minorTicksPerMajor(4)
                .faceAspect(4)
                .bakeResolution(256);
    }

    @Test
    void sizesTheTextureFromTheAspect() {
        assertArrayEquals(new int[] { 64, 256 }, EdgewiseLayout.size(face().build()));
        assertArrayEquals(new int[] { 52, 256 }, EdgewiseLayout.size(face().faceAspect(4.887f).build()));
    }

    @Test
    void laysTicksDownTheScaleWithTheBandsUnderThem() {
        EdgewiseFaceDefinition def = face()
                .bands(new GaugeFaceDefinition.ColorBand(8, 10, 0xFFFF0000))
                .build();
        EdgewiseLayout layout = EdgewiseLayout.of(def, MONO);
        // The band first, then the spine, then 3 majors and 8 minors
        assertEquals(1 + 1 + 3 + 8, layout.rects.size());
        EdgewiseLayout.Rect band = layout.rects.get(0);
        assertEquals(0xFFFF0000, band.argb());
        assertEquals(def.bandLeft * 64, band.x0(), 1e-4f);
        assertEquals((def.bandLeft + def.bandWidth) * 64, band.x1(), 1e-4f);
        assertEquals(def.valueToFraction(10) * 256, band.y0(), 1e-3f);
        assertEquals(def.valueToFraction(8) * 256, band.y1(), 1e-3f);

        // The spine stops at the end ticks, squared off with them, and grows away from the ticks
        EdgewiseLayout.Rect spine = layout.rects.get(1);
        float half = def.majorTickWidth * 64 / 2;
        assertEquals(def.scaleTop * 256 - half, spine.y0(), 1e-3f);
        assertEquals(def.scaleBottom * 256 + half, spine.y1(), 1e-3f);
        assertEquals(def.tickEdge * 64, spine.x1(), 1e-4f);
        assertEquals((def.tickEdge - def.spineWidth) * 64, spine.x0(), 1e-4f);

        // The bottom major is at the minimum, centred on it, and runs out from the tick edge
        EdgewiseLayout.Rect bottom = layout.rects.get(2);
        assertEquals(def.scaleBottom * 256, (bottom.y0() + bottom.y1()) / 2, 1e-3f);
        assertEquals(def.majorTickWidth * 64, bottom.y1() - bottom.y0(), 1e-3f);
        assertEquals(def.tickEdge * 64, bottom.x0(), 1e-4f);
        assertEquals((def.tickEdge + def.majorTickLength) * 64, bottom.x1(), 1e-4f);
        // A minor is shorter
        EdgewiseLayout.Rect minor = layout.rects.get(5);
        assertEquals((def.tickEdge + def.minorTickLength) * 64, minor.x1(), 1e-4f);
    }

    @Test
    void runsTheSpineTheWholeHeightWhenAsked() {
        EdgewiseLayout layout = EdgewiseLayout.of(face().spine(0.1f, true).build(), MONO);
        EdgewiseLayout.Rect spine = layout.rects.get(0);
        assertEquals(0, spine.y0());
        assertEquals(256, spine.y1());
        // No spine at all at zero width
        assertEquals(3 + 8, EdgewiseLayout.of(face().spine(0, false).build(), MONO).rects.size());
    }

    @Test
    void hangsLabelsOffTheTicksAndMirrorsThem() {
        EdgewiseFaceDefinition def = face().units("V").build();
        EdgewiseLayout layout = EdgewiseLayout.of(def, MONO);
        List<EdgewiseLayout.Text> labels = layout.texts.subList(0, 3);
        float edge = (def.tickEdge + def.majorTickLength + def.labelGap) * 64;
        for (EdgewiseLayout.Text label : labels) {
            assertEquals(EdgewiseLayout.Align.LEFT, label.align());
            assertEquals(edge, label.x(), 1e-4f);
            assertEquals(def.labelSize * 64, label.px(), 1e-4f);
        }
        assertEquals("0", labels.get(0).text());
        assertEquals(def.scaleBottom * 256, labels.get(0).y(), 1e-3f);
        assertEquals("10", labels.get(2).text());
        assertEquals(def.scaleTop * 256, labels.get(2).y(), 1e-3f);

        EdgewiseFaceDefinition flipped = def.toBuilder().flipTicks(true).build();
        EdgewiseLayout mirrored = EdgewiseLayout.of(flipped, MONO);
        EdgewiseLayout.Text label = mirrored.texts.get(0);
        assertEquals(EdgewiseLayout.Align.RIGHT, label.align());
        assertEquals(64 - edge, label.x(), 1e-4f);
        // Every rectangle mirrored about the middle of the face
        for (int i = 0; i < layout.rects.size(); i++) {
            assertEquals(64 - layout.rects.get(i).x1(), mirrored.rects.get(i).x0(), 1e-4f);
            assertEquals(64 - layout.rects.get(i).x0(), mirrored.rects.get(i).x1(), 1e-4f);
        }
    }

    @Test
    void printsTheNameAboveAndTheUnitsBelowShrinkingWhatIsTooWide() {
        EdgewiseFaceDefinition def = face().displayName("AMPERES TOTAL").units("A").build();
        EdgewiseLayout layout = EdgewiseLayout.of(def, MONO);
        EdgewiseLayout.Text name = layout.texts.get(3);
        EdgewiseLayout.Text units = layout.texts.get(4);
        assertEquals("AMPERES TOTAL", name.text());
        assertEquals(EdgewiseLayout.Align.CENTER, name.align());
        assertEquals(32, name.x(), 1e-4f);
        assertEquals(def.scaleTop * 0.5f * 256, name.y(), 1e-3f);
        // Shrunk to the face width less its margin
        assertEquals(64 * EdgewiseLayout.MAX_TEXT_WIDTH, MONO.width(name.text(), name.px()), 1e-3f);
        assertEquals(def.textSize * 64, units.px(), 1e-4f);
        assertEquals((1 + def.scaleBottom) * 0.5f * 256, units.y(), 1e-3f);

        // A name left off draws nothing
        EdgewiseLayout hidden = EdgewiseLayout.of(def.toBuilder().drawDisplayName(false).build(), MONO);
        assertEquals(4, hidden.texts.size());
    }

    @Test
    void stacksASideNameDownTheGapBehindTheSpine() {
        EdgewiseFaceDefinition def = face().displayName("INCHES").nameOnSide(true).tickEdge(0.4f).spine(0.05f, false)
                .build();
        EdgewiseLayout layout = EdgewiseLayout.of(def, MONO);
        List<EdgewiseLayout.Text> letters = layout.texts.subList(3, 9);
        float centreX = (def.tickEdge - def.spineWidth) * 0.5f * 64;
        float sideWidth = (def.tickEdge - def.spineWidth - 2 * def.sideNameMargin) * 64;
        float pitch = letters.get(1).y() - letters.get(0).y();
        for (int i = 0; i < letters.size(); i++) {
            EdgewiseLayout.Text letter = letters.get(i);
            assertEquals(String.valueOf("INCHES".charAt(i)), letter.text());
            assertEquals(centreX, letter.x(), 1e-4f);
            assertTrue(MONO.width(letter.text(), letter.px()) <= sideWidth + 1e-3f);
            assertEquals(letter.px() * EdgewiseLayout.STACK_PITCH, pitch, 1e-3f);
        }
        // Centred down the scale
        float middle = (letters.get(0).y() + letters.get(5).y()) / 2;
        assertEquals((def.scaleTop + def.scaleBottom) / 2 * 256, middle, 1e-3f);
    }

    @Test
    void fitsAStackToBothItsWidthAndItsHeight() {
        // Too tall: six letters at pitch 1.15 must fit 60 pixels
        assertEquals(60 / (6 * 1.15f), EdgewiseLayout.fitStacked(MONO, "ABCDEF", 20, 100, 60), 1e-4f);
        // Too wide: a letter is 0.6 of its size across
        assertEquals(10 / 0.6f, EdgewiseLayout.fitStacked(MONO, "AB", 20, 10, 1000), 1e-4f);
        assertEquals(0, EdgewiseLayout.fitStacked(MONO, "AB", 20, 0, 1000));
        assertEquals(20, EdgewiseLayout.fit(MONO, "AB", 20, 1000));
    }

    @Test
    void runsTheNeedleInFromThePointerSideAndTheBarUpTheBandStrip() {
        EdgewiseFaceDefinition def = face().tickEdge(0.3f).band(0.3f, 0.12f).build();
        assertArrayEquals(new float[] { 0, 0.3f }, EdgewiseLayout.pointerSpan(def), 1e-6f);
        EdgewiseFaceDefinition flipped = def.toBuilder().flipTicks(true).build();
        assertArrayEquals(new float[] { 0.7f, 1 }, EdgewiseLayout.pointerSpan(flipped), 1e-6f);
        EdgewiseFaceDefinition bar = def.toBuilder().pointerStyle(EdgewiseFaceDefinition.PointerStyle.BAR).build();
        assertArrayEquals(new float[] { 0.3f, 0.42f }, EdgewiseLayout.pointerSpan(bar), 1e-6f);
    }
}
