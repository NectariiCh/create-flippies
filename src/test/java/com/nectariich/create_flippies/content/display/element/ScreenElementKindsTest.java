package com.nectariich.create_flippies.content.display.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * The kinds the visual editor added: each keeps only the fields that mean something
 * for it, a bar keeps its last value through text with no number in it, a line may
 * lie flat, everything round-trips, and an element put back in its slot carries its
 * generation so links made against it work again.
 */
class ScreenElementKindsTest {

    private static final Viewport VIEW = new Viewport(400, 200);
    private static final String HASH = "0123456789abcdef0123456789abcdef";

    private static ScreenElement roundTrip(ScreenElement element) {
        return ScreenElement.read(element.write(), VIEW);
    }

    @Test
    void fieldsThatMeanNothingForAKindArePinned() {
        ScreenElement rect = ScreenElement.create(0, ElementKind.FILL, 0, 0, 100, 40).toBuilder().text("hi")
                .art(HASH).flip(3).quarters(1).build();
        assertEquals("", rect.text());
        assertEquals("", rect.art());
        assertEquals(0, rect.flip());
        assertEquals(0, rect.quarters());

        ElementPaint bands = ElementPaint.SOLID.withType(ElementPaint.Type.BANDS);
        assertEquals(ElementPaint.Type.SOLID, rect.toBuilder().paint(bands).build().paint().type(),
                "bands are a bar's alone");
        assertEquals(ElementPaint.Type.BANDS,
                ScreenElement.create(0, ElementKind.BAR, 0, 0, 100, 40).toBuilder().paint(bands).build().paint().type());
        assertEquals(ElementPaint.SOLID,
                ScreenElement.create(0, ElementKind.FRAME, 0, 0, 100, 40).toBuilder().paint(bands).build().paint());
    }

    @Test
    void aFrameIsAHollowRectangle() {
        ScreenElement frame = ScreenElement.create(0, ElementKind.FRAME, 0, 0, 100, 40);
        assertEquals(ElementKind.FILL, frame.kind());
        assertTrue(frame.outline());
        assertEquals(frame, ScreenElement.read(frame.write(), VIEW), "saved and read back as one");

        // A frame saved before reads as a hollow rectangle with its stroke
        CompoundTag old = new CompoundTag();
        old.putByte("I", (byte) 3);
        old.putByte("K", (byte) ElementKind.FRAME.ordinal());
        old.putShort("W", (short) 100);
        old.putShort("H", (short) 40);
        old.putByte("Sw", (byte) 20);
        ScreenElement read = ScreenElement.read(old, VIEW);
        assertEquals(ElementKind.FILL, read.kind());
        assertTrue(read.outline());
        assertEquals(20, read.stroke());

        // Filled again, it loses the stroke it no longer draws
        ScreenElement filled = frame.toBuilder().outline(false).stroke(30).build();
        assertFalse(filled.outline());
        assertEquals(ScreenElement.DEFAULT_STROKE, filled.stroke());
    }

    @Test
    void cornersRoundAtMostToHalfTheShorterSide() {
        ScreenElement rect = new ScreenElement(0, ElementKind.FILL, 0, 0, 100, 40, -1, 0, 10, "", null, false, false,
                0, 0, 1, 1, "", false, 900, false, 0, 0, 0, 40, 30, null, "", false, null, Float.NaN);
        assertEquals(20, rect.radius());
    }

    @Test
    void aLineMayLieFlatAndRunsCornerToCorner() {
        ScreenElement line = ScreenElement.create(0, ElementKind.LINE, 0, 0, 0, 0).withEnds(300, 50, 100, 50);
        assertEquals(0, line.h());
        assertEquals(200, line.w());
        assertEquals(List.of(300, 50), List.of(line.lineStart()[0], line.lineStart()[1]));
        assertEquals(List.of(100, 50), List.of(line.lineEnd()[0], line.lineEnd()[1]));
        assertEquals(line, roundTrip(line));
        assertEquals(1, ScreenElement.create(0, ElementKind.FILL, 0, 0, 0, 0).h(), "a fill always covers something");
    }

    @Test
    void aBarHoldsItsLastNumber() {
        ScreenElement bar = ScreenElement.create(3, ElementKind.BAR, 0, 0, 100, 20);
        assertTrue(Float.isNaN(bar.value()), "empty until a number arrives");
        bar = bar.withText("Level 42 %");
        assertEquals(42, bar.value());
        bar = bar.withText("offline");
        assertEquals(42, bar.value(), "text with no number leaves it where it was");
        assertEquals("offline", bar.text());
        assertEquals(bar, roundTrip(bar));
    }

    @Test
    void everyNewFieldRoundTrips() {
        ElementPaint paint = ElementPaint.SOLID.withType(ElementPaint.Type.LINEAR).withAngle(30)
                .withStops(List.of(new ElementPaint.Stop(0xFF102030, 0), new ElementPaint.Stop(0xFFA0B0C0, 1000)));
        List<ScreenElement> all = List.of(
                new ScreenElement(1, ElementKind.FILL, 5, 6, 100, 50, 0xFF00FF00, 0, 10, "", null, false, false, 0,
                        0, 1, 1, "Back", true, 12, false, 0, 0, 0, 40, 30, paint, "", false, null, Float.NaN),
                new ScreenElement(2, ElementKind.ELLIPSE, 5, 6, 100, 50, -1, 0, 25, "", null, false, false, 0, 0, 1,
                        1, "", false, 0, true, 0, 0, 0, 40, 30, null, "", false, null, Float.NaN),
                new ScreenElement(3, ElementKind.LINE, 5, 6, 100, 50, -1, 0, 25, "", null, false, false, 0, 0, 1, 1,
                        "", false, 0, false, 3, ScreenElement.HEADS_BOTH, ScreenElement.HEAD_OPEN, 70, 55, null, "",
                        false, null, Float.NaN),
                new ScreenElement(4, ElementKind.SVG, 5, 6, 100, 50, 0xFF123456, 0, 10, "", null, false, false, 0, 3,
                        1, 1, "Logo", false, 0, false, 0, 0, 0, 40, 30, null, HASH, true, null, Float.NaN),
                new ScreenElement(5, ElementKind.BAR, 5, 6, 100, 50, -1, 0xFF222222, 10, "7.5", null, false, false,
                        0, 1, 1, 1, "", false, 0, false, 0, 0, 0, 40, 30,
                        paint.withType(ElementPaint.Type.BANDS)
                                .withBands(List.of(new ElementPaint.Band(0xFFFF0000, 0, 10))),
                        "", false, new BarSettings(0, 10, BarSettings.BOTTOM_TO_TOP, false), Float.NaN));
        for (ScreenElement element : all)
            assertEquals(element, roundTrip(element), element.kind().name());
    }

    @Test
    void anOldTagReadsExactlyAsBefore() {
        // A text element as a save made before any of these fields existed
        CompoundTag old = new CompoundTag();
        old.putByte("I", (byte) 0);
        old.putByte("K", (byte) ElementKind.TEXT.ordinal());
        old.putShort("W", (short) 120);
        old.putShort("H", (short) 60);
        old.putString("T", "READY");
        old.putByte("Fl", (byte) 1);
        ScreenElement read = ScreenElement.read(old, VIEW);
        assertEquals(new ScreenElement(0, ElementKind.TEXT, 0, 0, 120, 60, ScreenElement.DEFAULT_COLOUR, 0,
                ScreenElement.DEFAULT_STROKE, "READY", null, true, false, 0, 0, 1, 1), read);
        assertEquals(old, read.write(), "and writes back the same keys");
    }

    @Test
    void putRestoresAnElementInItsOwnSlotWithItsGeneration() {
        ScreenElements elements = new ScreenElements();
        ScreenElement a = TestElements.add(elements, ElementKind.TEXT, VIEW);
        ScreenElement b = TestElements.add(elements, ElementKind.FILL, VIEW);
        int generation = elements.generation(a.id());
        assertTrue(elements.remove(a.id()));
        assertTrue(elements.generation(a.id()) != generation);

        assertTrue(elements.put(a, 0, generation, VIEW, hash -> true, ScreenElements.MAX_TOTAL_TEXT));
        assertEquals(List.of(a.id(), b.id()), elements.snapshot().stream().map(ScreenElement::id).toList());
        assertEquals(generation, elements.generation(a.id()));
        assertFalse(elements.put(a, 0, generation, VIEW, hash -> true, ScreenElements.MAX_TOTAL_TEXT),
                "a taken slot is refused");
    }

    @Test
    void putRefusesArtTheStoreLacksAndClampsTheBox() {
        ScreenElements elements = new ScreenElements();
        ScreenElement art = ScreenElement.create(7, ElementKind.SVG, 0, 0, 100, 100).toBuilder().art(HASH).build();
        assertFalse(elements.put(art, 0, 0, VIEW, hash -> false, ScreenElements.MAX_TOTAL_TEXT));
        ScreenElement big = ScreenElement.create(9, ElementKind.FILL, 350, 0, 100, 900);
        assertTrue(elements.put(big, 5, 0, VIEW, hash -> false, ScreenElements.MAX_TOTAL_TEXT));
        assertEquals(new ScreenElement(9, ElementKind.FILL, 300, 0, 100, 200, -1, 0, 10, "", null, false, false, 0,
                0, 1, 1), elements.get(9));
    }

    @Test
    void linksWriteToBarsInIdOrderWithLettering() {
        ScreenElements elements = new ScreenElements();
        TestElements.add(elements, ElementKind.BAR, VIEW);
        TestElements.add(elements, ElementKind.FILL, VIEW);
        TestElements.add(elements, ElementKind.TEXT, VIEW);
        assertEquals(List.of(0, 2), elements.linkTargets().stream().map(ScreenElement::id).toList());
        assertTrue(elements.acceptLinked(0, 0, "55"));
        assertEquals(55, elements.get(0).value());
        assertFalse(elements.acceptLinked(1, 0, "55"), "a fill takes no text");
    }

    @Test
    void anEditCannotSetArtTheStoreLacks() {
        ScreenElements elements = new ScreenElements();
        ScreenElement art = ScreenElement.create(0, ElementKind.SVG, 0, 0, 100, 100);
        elements.put(art, 0, 0, VIEW, hash -> true, ScreenElements.MAX_TOTAL_TEXT);
        ScreenElement.Fields values = art.toBuilder().art(HASH).build().fields();
        assertFalse(elements.setFields(0, ScreenElement.FIELD_ART, values, VIEW, hash -> false));
        assertTrue(elements.setFields(0, ScreenElement.FIELD_ART, values, VIEW, hash -> true));
        assertEquals(HASH, elements.get(0).art());
    }

    @Test
    void differenceNamesExactlyTheFieldsThatDiffer() {
        ScreenElement a = ScreenElement.create(0, ElementKind.LINE, 0, 0, 100, 0);
        ScreenElement b = a.withEnds(100, 0, 0, 30).toBuilder().name("Pointer").build();
        assertEquals(ScreenElement.FIELD_H | ScreenElement.FIELD_FLIP | ScreenElement.FIELD_NAME, a.difference(b));
        assertEquals(b, a.merged(b.fields(), a.difference(b)));
    }
}
