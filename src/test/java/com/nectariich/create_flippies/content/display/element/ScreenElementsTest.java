package com.nectariich.create_flippies.content.display.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.content.label.PanelLabelFont;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * The element collection every display draws from: slots stay put whatever the
 * drawing order does, a freed slot's links die with it, the caps hold, and a save
 * (new, old or hostile) reads back as something safe to draw.
 */
class ScreenElementsTest {

    /** 12 by 6 pixels. */
    private static final Viewport VIEW = new Viewport(120, 60);

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    private static List<Integer> ids(ScreenElements elements) {
        return elements.snapshot().stream().map(ScreenElement::id).toList();
    }

    private static ScreenElement textValues(String text) {
        return ScreenElement.create(0, ElementKind.TEXT, VIEW).withText(text);
    }

    private static ScreenElements roundTrip(ScreenElements elements, Viewport viewport) {
        CompoundTag tag = new CompoundTag();
        elements.write(tag);
        ScreenElements read = new ScreenElements();
        read.read(tag, viewport, DisplayForm.values()[0]);
        return read;
    }

    @Test
    void holdsAtMostOneHundredAndTwentyEight() {
        ScreenElements elements = new ScreenElements();
        for (int i = 0; i < ScreenElements.MAX; i++)
            assertEquals(i, TestElements.add(elements, ElementKind.FILL, VIEW).id());
        assertNull(TestElements.add(elements, ElementKind.TEXT, VIEW));
        assertEquals(ScreenElements.MAX, elements.size());
    }

    @Test
    void idsStayPutWhenTheDrawingOrderMovesAndWhenOthersGo() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        TestElements.add(elements, ElementKind.FILL, VIEW);
        TestElements.add(elements, ElementKind.FRAME, VIEW);
        assertEquals(List.of(0, 1, 2), ids(elements));

        assertTrue(elements.zTo(2, 0));
        assertEquals(List.of(2, 0, 1), ids(elements));
        assertTrue(elements.zTo(2, 1));
        assertEquals(List.of(0, 2, 1), ids(elements));
        assertTrue(elements.zTo(0, 99), "clamped to the front");
        assertEquals(List.of(2, 1, 0), ids(elements));
        assertTrue(elements.zTo(0, -40));
        assertEquals(List.of(0, 2, 1), ids(elements));
        assertFalse(elements.zTo(1, 2), "already at the front");
        assertFalse(elements.zTo(9, 0), "no such element");

        assertTrue(elements.get(2).outline(), "the frame, as a hollow rectangle");
        assertTrue(elements.remove(2));
        assertEquals(List.of(0, 1), ids(elements));
        assertEquals(ElementKind.FILL, elements.get(1).kind());
        assertFalse(elements.get(1).outline());
        assertEquals(ElementKind.TEXT, elements.get(0).kind());
    }

    @Test
    void removingMovesTheGenerationAndTheSlotIsReused() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        TestElements.add(elements, ElementKind.TEXT, VIEW);
        assertEquals(0, elements.generation(1));
        assertTrue(elements.acceptLinked(1, 0, "old"));

        assertTrue(elements.remove(1));
        assertFalse(elements.remove(1));
        assertEquals(1, elements.generation(1));
        assertEquals(1, TestElements.add(elements, ElementKind.TEXT, VIEW).id(), "lowest free slot");
        assertFalse(elements.acceptLinked(1, 0, "stale link"), "a link made against the old element");
        assertTrue(elements.acceptLinked(1, 1, "new link"));
        assertEquals("new link", elements.get(1).text());

        for (int i = 0; i < 127; i++) {
            elements.remove(1);
            TestElements.add(elements, ElementKind.TEXT, VIEW);
        }
        assertEquals(0, elements.generation(1), "seven bits wrap");
    }

    @Test
    void linkWritesOnlyTextAndOnlyWhenItChanges() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        ScreenElement fill = TestElements.add(elements, ElementKind.FILL, VIEW);
        assertFalse(elements.acceptLinked(fill.id(), 0, "x"), "a rectangle has nothing to write to");
        assertTrue(elements.acceptLinked(0, 0, "42 rpm"));
        assertFalse(elements.acceptLinked(0, 0, "42 rpm"), "same text costs no sync");
        assertTrue(elements.acceptLinked(0, 0, "§kA\nB"));
        assertEquals("kAB", elements.get(0).text(), "section signs and line breaks filtered");
    }

    @Test
    void typedAndLinkedTextShareOneBudget() {
        ScreenElements elements = new ScreenElements();
        String full = "x".repeat(ScreenElement.MAX_TEXT + 50);
        for (int i = 0; i < 17; i++) {
            ScreenElement element = TestElements.add(elements, ElementKind.TEXT, VIEW);
            elements.setFields(element.id(), ScreenElement.FIELD_TEXT, textValues(full).fields(), VIEW, hash -> false);
        }
        // Sixteen full elements use the whole budget; the seventeenth gets nothing
        for (int i = 0; i < 16; i++)
            assertEquals(ScreenElement.MAX_TEXT, elements.get(i).text().length());
        assertEquals("", elements.get(16).text());

        assertTrue(elements.setFields(3, ScreenElement.FIELD_TEXT, textValues("abc").fields(), VIEW, hash -> false));
        assertTrue(elements.acceptLinked(16, 0, full));
        assertEquals(ScreenElement.MAX_TEXT - 3, elements.get(16).text().length(), "cut to what is left");
        int total = elements.snapshot().stream().mapToInt(element -> element.text().length()).sum();
        assertEquals(ScreenElements.MAX_TOTAL_TEXT, total);

        assertEquals(ScreenElement.MAX_TEXT, ScreenElement.sanitiseText(full).length(), "per-element cap");
    }

    @Test
    void textIsNeverCutBetweenTheHalvesOfAPair() {
        String pairs = "😀".repeat(ScreenElement.MAX_TEXT);
        String cut = ScreenElement.sanitiseText("a" + pairs);
        assertEquals(ScreenElement.MAX_TEXT - 1, cut.length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    @Test
    void boxesAreKeptInsideTheViewport() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        ScreenElement values = ScreenElement.create(0, ElementKind.TEXT, VIEW).withBox(500, -20, 30, 9000);
        assertTrue(elements.setFields(0, ScreenElement.FIELD_X | ScreenElement.FIELD_Y | ScreenElement.FIELD_W
                | ScreenElement.FIELD_H, values.fields(), VIEW, hash -> false));
        ScreenElement element = elements.get(0);
        assertEquals(30, element.w(), "a box that fits keeps its size");
        assertEquals(90, element.x(), "and slides back inside");
        assertEquals(0, element.y());
        assertEquals(60, element.h(), "only a box larger than the viewport is cut");

        ScreenElement shapes = ScreenElement.create(0, ElementKind.FILL, VIEW).withBox(0, 0, 0, -5);
        assertEquals(1, shapes.w());
        assertEquals(1, shapes.h());

        // A frame's outline is at most half its shorter side
        ScreenElement frame = new ScreenElement(1, ElementKind.FRAME, 0, 0, 40, 10, 0, 0, 100, "", "", false,
                false, 0, 0, 1, 1);
        assertEquals(5, frame.stroke());

        // A viewport that shrank fits every box again
        assertTrue(elements.clampTo(new Viewport(20, 20)));
        assertEquals(new Viewport(20, 20).width(), elements.get(0).x() + elements.get(0).w());
    }

    @Test
    void fieldMasksTouchOnlyTheirFields() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        // The id is never taken from the values: the edit names its element separately
        ScreenElement everything = new ScreenElement(9, ElementKind.TEXT, 10, 20, 30, 40, 0x123456, 0x80FF0000, 30, "typed",
                "minecraft:uniform", true, true, 45, 3, ScreenElement.ALIGN_START, ScreenElement.ALIGN_END);

        assertTrue(elements.setFields(0, ScreenElement.FIELD_COLOUR, everything.fields(), VIEW, hash -> false));
        ScreenElement element = elements.get(0);
        assertEquals(0xFF123456, element.colour(), "forced opaque");
        assertEquals(0, element.x());
        assertEquals("", element.text());
        assertEquals(0, element.id());

        assertTrue(elements.setFields(0, ScreenElement.FIELD_H_ALIGN | ScreenElement.FIELD_V_ALIGN, everything.fields(),
                VIEW, hash -> false));
        assertEquals(ScreenElement.ALIGN_START, elements.get(0).hAlign());
        assertEquals(ScreenElement.ALIGN_END, elements.get(0).vAlign());
        assertFalse(elements.setFields(0, ScreenElement.FIELD_H_ALIGN, everything.fields(), VIEW, hash -> false),
                "nothing changed");

        assertTrue(elements.setFields(0, ScreenElement.ALL_FIELDS, everything.fields(), VIEW, hash -> false));
        assertEquals(new ScreenElement(0, ElementKind.TEXT, 10, 20, 30, 40, 0x123456, 0x80FF0000, 30, "typed",
                "minecraft:uniform", true, true, 45, 3, ScreenElement.ALIGN_START, ScreenElement.ALIGN_END),
                elements.get(0));
        assertFalse(elements.setFields(7, ScreenElement.ALL_FIELDS, everything.fields(), VIEW, hash -> false));
    }

    @Test
    void editValuesAreSanitisedAgainstTheTargetNotTheCarrier() {
        ScreenElements elements = new ScreenElements();
        TestElements.add(elements, ElementKind.FRAME, VIEW);
        TestElements.add(elements, ElementKind.TEXT, VIEW);
        assertTrue(elements.setFields(0, ScreenElement.FIELD_W | ScreenElement.FIELD_H,
                ScreenElement.create(0, ElementKind.FRAME, VIEW).withBox(0, 0, 100, 60).fields(), VIEW,
                hash -> false));

        // One raw carrier holds a stroke and text at once; each lands on the kind that has it
        ScreenElement.Fields sent = new ScreenElement.Fields(0, 0, 1, 1, 0, 0, 20, "typed", null, false, false, 0,
                0, 1, 1);
        assertTrue(elements.setFields(0, ScreenElement.FIELD_STROKE, sent, VIEW, hash -> false));
        assertEquals(20, elements.get(0).stroke(), "held to the frame's box, not the carrier's 1x1");
        assertTrue(elements.setFields(1, ScreenElement.FIELD_TEXT, sent, VIEW, hash -> false));
        assertEquals("typed", elements.get(1).text());

        // Past half the target's shorter side the stroke is held there, and a box sent
        // with it is the one it is held to
        assertTrue(elements.setFields(0, ScreenElement.FIELD_STROKE, new ScreenElement.Fields(0, 0, 0, 0, 0, 0, 100,
                "", "", false, false, 0, 0, 1, 1), VIEW, hash -> false));
        assertEquals(30, elements.get(0).stroke());
        assertTrue(elements.setFields(0, ScreenElement.FIELD_H | ScreenElement.FIELD_STROKE,
                new ScreenElement.Fields(0, 0, 0, 20, 0, 0, 100, "", "", false, false, 0, 0, 1, 1), VIEW,
                hash -> false));
        assertEquals(10, elements.get(0).stroke());
        assertEquals("", elements.get(0).text(), "a frame still holds no text");
    }

    @Test
    void aTtfFontIsTakenOnlyWhenTheStoreHasIt() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        ScreenElement values = textValues("").withFont(DesignText.TTF_PREFIX + HASH);
        assertEquals(DesignText.TTF_PREFIX + HASH, values.font(), "well-formed, so the record keeps it");

        assertFalse(elements.setFields(0, ScreenElement.FIELD_FONT, values.fields(), VIEW, hash -> false));
        assertEquals(DesignText.DEFAULT_FONT, elements.get(0).font());

        Set<String> store = Set.of(HASH);
        assertTrue(elements.setFields(0, ScreenElement.FIELD_FONT, values.fields(), VIEW, store::contains));
        assertEquals(DesignText.TTF_PREFIX + HASH, elements.get(0).font());

        // A TTF's weight is its face, so bold is dropped
        ScreenElement bold = new ScreenElement(0, ElementKind.TEXT, 0, 0, 10, 10, 0, 0, 0, "", "ttf:" + HASH, true,
                false, 0, 0, 1, 1);
        assertFalse(bold.bold());
        assertEquals(DesignText.DEFAULT_FONT, values.withFont("ttf:not-a-hash").font());
    }

    @Test
    void roundTripsLeavingDefaultsOut() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        TestElements.add(elements, ElementKind.FRAME, VIEW);
        elements.setFields(0, ScreenElement.ALL_FIELDS, new ScreenElement(0, ElementKind.TEXT, 5, 6, 70, 30,
                0x00FF00, 0x40000000, 0, "hello", "minecraft:alt", true, false, 25, 1, ScreenElement.ALIGN_END,
                ScreenElement.ALIGN_START).fields(), VIEW, hash -> false);
        elements.remove(1);
        TestElements.add(elements, ElementKind.FILL, VIEW);
        TestElements.add(elements, ElementKind.FRAME, VIEW);
        elements.zTo(2, 0);

        CompoundTag tag = new CompoundTag();
        elements.write(tag);
        ScreenElements read = roundTrip(elements, VIEW);
        assertEquals(elements.snapshot(), read.snapshot());
        for (int id = 0; id < ScreenElements.MAX; id++)
            assertEquals(elements.generation(id), read.generation(id));
        assertTrue(tag.contains("Gen"));

        // A fresh default element is only its id, kind and box
        CompoundTag fresh = new CompoundTag();
        ScreenElements.defaults(VIEW).write(fresh);
        assertEquals(Set.of("I", "K", "W", "H"), fresh.getList("Elements", 10).getCompound(0).getAllKeys());
        assertFalse(fresh.contains("Gen"), "no generation has moved");
    }

    @Test
    void anEmptyDisplayStaysEmpty() {
        ScreenElements elements = ScreenElements.defaults(VIEW);
        elements.remove(0);
        ScreenElements read = roundTrip(elements, VIEW);
        assertTrue(read.isEmpty(), "not migrated back to one line");
        assertEquals(1, read.generation(0));
    }

    @Test
    void anOldDisplayMigratesToElementZeroFieldByField() {
        DisplayForm form = DisplayForm.values()[0];
        Viewport viewport = Viewport.of(form);
        CompoundTag old = new CompoundTag();
        old.putString("Text", "RPM 128");
        new DisplayPrint(PanelLabelFont.UNIFORM, true, true, 37, 3, 0xFF20C020).write(old);

        ScreenElements elements = new ScreenElements();
        elements.read(old, viewport, form);
        assertEquals(List.of(new ScreenElement(0, ElementKind.TEXT, 0, 0, viewport.width(), viewport.height(),
                0xFF20C020, 0, 0, "RPM 128", "minecraft:uniform", true, true, 37, 3, ScreenElement.ALIGN_CENTRE,
                ScreenElement.ALIGN_CENTRE)), elements.snapshot());

        // Saved before the print was stored: the form's default height, fitted not
        CompoundTag older = new CompoundTag();
        older.putString("Text", "hi");
        elements.read(older, viewport, form);
        ScreenElement element = elements.get(0);
        assertEquals(DisplayPrint.defaultFor(form).size(), element.size());
        assertEquals("minecraft:default", element.font());
        assertEquals(0xFFFFFFFF, element.colour());

        CompoundTag written = new CompoundTag();
        elements.write(written);
        for (String legacy : List.of("Text", "Font", "Bold", "Italic", "TextSize", "Quarters", "Colour"))
            assertFalse(written.contains(legacy), legacy);

        // Without a form to migrate from, a missing list is the defaults
        elements.read(new CompoundTag(), VIEW, null);
        assertEquals(ScreenElements.defaults(VIEW).snapshot(), elements.snapshot());
    }

    @Test
    void aHostileTagReadsAsSomethingSafe() {
        ListTag list = new ListTag();
        CompoundTag wild = new CompoundTag();
        wild.putByte("I", (byte) 3);
        wild.putByte("K", (byte) 0);
        wild.putShort("X", (short) -400);
        wild.putShort("Y", (short) 30000);
        wild.putShort("W", (short) -1);
        wild.putShort("H", (short) 32767);
        wild.putInt("C", 0x00000000);
        wild.putInt("B", 0x00FFFFFF);
        wild.putString("T", "§k" + "z".repeat(5000));
        wild.putString("F", "../../etc/passwd");
        wild.putShort("S", (short) -12);
        wild.putByte("Q", (byte) 99);
        wild.putByte("Ah", (byte) 40);
        wild.putByte("Av", (byte) -3);
        list.add(wild);
        CompoundTag duplicateId = wild.copy();
        duplicateId.putString("T", "second");
        list.add(duplicateId);
        CompoundTag badId = new CompoundTag();
        badId.putByte("I", (byte) -1);
        list.add(badId);
        CompoundTag unknownKind = new CompoundTag();
        unknownKind.putByte("I", (byte) 4);
        unknownKind.putByte("K", (byte) 77);
        list.add(unknownKind);
        CompoundTag frame = new CompoundTag();
        frame.putByte("I", (byte) 5);
        frame.putByte("K", (byte) ElementKind.FRAME.ordinal());
        frame.putByte("Sw", (byte) -128);
        list.add(frame);
        for (int i = 0; i < 300; i++) {
            CompoundTag more = new CompoundTag();
            more.putByte("I", (byte) (6 + i % 122));
            more.putByte("K", (byte) 1);
            list.add(more);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("Elements", list);
        tag.putByteArray("Gen", new byte[] { (byte) 0xFF, 5 });

        ScreenElements elements = new ScreenElements();
        elements.read(tag, VIEW, null);
        assertEquals(ScreenElements.MAX - 4, elements.size(), "the repeated id, the bad id and the unknown kind are dropped, the rest capped");
        ScreenElement element = elements.get(3);
        assertEquals(0, element.x());
        assertEquals(0, element.y(), "slid back up to make room for the whole height");
        assertEquals(1, element.w());
        assertEquals(VIEW.height(), element.h());
        assertEquals(0xFF000000, element.colour());
        assertEquals(0, element.highlight(), "a transparent highlight is none");
        assertEquals(ScreenElement.MAX_TEXT, element.text().length());
        assertFalse(element.text().contains("§"));
        assertEquals(DesignText.DEFAULT_FONT, element.font());
        assertEquals(1, element.size());
        assertEquals(3, element.quarters());
        assertEquals(ScreenElement.ALIGN_END, element.hAlign());
        assertEquals(ScreenElement.ALIGN_START, element.vAlign());
        assertNull(elements.get(4));
        assertEquals(1, elements.get(5).stroke());
        assertEquals(0x7F, elements.generation(0));
        assertEquals(5, elements.generation(1));
    }
}
