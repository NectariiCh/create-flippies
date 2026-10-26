package com.nectariich.create_flippies.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.display.element.BarSettings;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.TestElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * The element editor's packet: every op and every field the editor sets arrives as it
 * was sent, a set carries only the fields in its mask, values a client made up arrive
 * sanitised, and the edit it applies touches only what it names.
 */
class EditScreenElementPacketTest {

    private static final BlockPos POS = new BlockPos(3, 64, -7);
    private static final Viewport VIEWPORT = Viewport.ofPixels(15, 8);
    private static final String HASH = "0123456789abcdef0123456789abcdef";

    /** The fields every element had before the visual editor: the box, colours, text and lettering. */
    private static final int OLD_FIELDS = (1 << 15) - 1;

    /** Every field moved off its default. */
    private static ScreenElement.Fields edited() {
        return new ScreenElement.Fields(12, 34, 56, 40, 0xFF20C020, 0x80102030, 7, "READY", "minecraft:uniform",
                true, true, 25, 3, ScreenElement.ALIGN_END, ScreenElement.ALIGN_START);
    }

    private static FriendlyByteBuf encode(EditScreenElementPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        packet.encode(buffer);
        return buffer;
    }

    private static EditScreenElementPacket decode(FriendlyByteBuf buffer) {
        return new EditScreenElementPacket(buffer);
    }

    private static EditScreenElementPacket roundTrip(EditScreenElementPacket packet) {
        FriendlyByteBuf sent = encode(packet);
        EditScreenElementPacket read = decode(new FriendlyByteBuf(sent.copy()));
        assertEquals(sent, encode(read), "a relayed packet encodes the same");
        return read;
    }

    /** A forged set: header written by hand, then {@code body} writes the mask and fields. */
    private static EditScreenElementPacket forgedSet(int id, java.util.function.Consumer<FriendlyByteBuf> body) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(POS);
        buffer.writeBoolean(false);
        buffer.writeByte(EditScreenElementPacket.Op.SET.ordinal());
        buffer.writeByte(id);
        body.accept(buffer);
        return decode(buffer);
    }

    @Test
    void carriesEveryFieldOfAFullSet() {
        ControlMount mount = ControlMount.centred(Direction.NORTH, 1);
        EditScreenElementPacket read = roundTrip(
                EditScreenElementPacket.set(POS, mount, 5, ScreenElement.ALL_FIELDS, edited()));
        assertEquals(EditScreenElementPacket.Op.SET, read.op());
        assertEquals(POS, read.pos());
        assertEquals(mount, read.mount());
        assertEquals(5, read.id());
        assertEquals(ScreenElement.ALL_FIELDS, read.mask());
        assertEquals(edited(), read.values());
    }

    @Test
    void carriesEveryFieldTheVisualEditorAdded() {
        ElementPaint paint = ElementPaint.SOLID.withType(ElementPaint.Type.BANDS).withBands(java.util.List.of(
                new ElementPaint.Band(0xFFFF0000, 0, 10), new ElementPaint.Band(0xFF00FF00, 20, 30)));
        ScreenElement.Fields values = new ScreenElement.Fields(1, 2, 0, 4, 0xFF20C020, 0x80102030, 7, "42",
                "minecraft:uniform", false, false, 0, 1, 0, 0, "Tank", true, 9, true, 3, ScreenElement.HEADS_BOTH,
                ScreenElement.HEAD_OPEN, 60, 70, paint, HASH, true, new BarSettings(-5, 5, 2, false));
        EditScreenElementPacket read = roundTrip(EditScreenElementPacket.set(POS, null, 5, ScreenElement.ALL_FIELDS,
                values));
        assertEquals(values, read.values());
    }

    @Test
    void aForgedPictureHashIsNoEdit() {
        EditScreenElementPacket read = forgedSet(0, buffer -> {
            buffer.writeVarInt(ScreenElement.FIELD_ART | ScreenElement.FIELD_TINT);
            buffer.writeUtf("../../etc/passwd");
            buffer.writeBoolean(true);
        });
        assertEquals(ScreenElement.FIELD_TINT, read.mask());
    }

    @Test
    void aPutCarriesTheElementWholeAndLandsInItsSlot() {
        ScreenElement line = ScreenElement.create(6, ElementKind.LINE, 0, 0, 0, 0).withEnds(100, 70, 10, 70)
                .toBuilder().name("Arrow").build();
        EditScreenElementPacket read = roundTrip(EditScreenElementPacket.put(POS, null, line, 1, 5));
        assertEquals(EditScreenElementPacket.Op.PUT, read.op());
        assertEquals(line, read.element());
        assertEquals(5, read.generation());
        assertEquals(1, read.amount());

        ScreenElements elements = ScreenElements.defaults(VIEWPORT);
        TestElements.add(elements, ElementKind.FILL, VIEWPORT);
        assertTrue(read.apply(elements, VIEWPORT, hash -> true).changed());
        assertEquals(6, elements.snapshot().get(1).id());
        assertEquals(5, elements.generation(6));
        assertFalse(read.apply(elements, VIEWPORT, hash -> true).changed(), "the slot is taken now");
        assertFalse(EditScreenElementPacket.put(POS, null, line.withId(7), 0, 0)
                .apply(elements, VIEWPORT, hash -> true, false, ScreenElements.MAX_TOTAL_TEXT).changed(),
                "refused when the host is full");
    }

    @Test
    void aPutWhoseElementNamesAnotherSlotIsNothing() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeBlockPos(POS);
        buffer.writeBoolean(false);
        buffer.writeByte(EditScreenElementPacket.Op.PUT.ordinal());
        buffer.writeByte(3);
        buffer.writeVarInt(0);
        buffer.writeByte(0);
        buffer.writeNbt(ScreenElement.create(4, ElementKind.FILL, 0, 0, 10, 10).write());
        assertNull(decode(buffer).op());
    }

    @Test
    void aSetCarriesOnlyTheFieldsInItsMask() {
        int mask = ScreenElement.FIELD_COLOUR | ScreenElement.FIELD_TEXT;
        EditScreenElementPacket narrow = EditScreenElementPacket.set(POS, null, 2, mask, edited());
        EditScreenElementPacket read = roundTrip(narrow);
        assertEquals(mask, read.mask());
        assertEquals(0xFF20C020, read.values().colour());
        assertEquals("READY", read.values().text());
        // Left out of the wire entirely, so the receiver sees placeholders, never the sender's values
        assertEquals(0, read.values().x());
        assertEquals(DesignText.DEFAULT_FONT, read.values().font());
        assertTrue(encode(narrow).readableBytes() < encode(
                EditScreenElementPacket.set(POS, null, 2, ScreenElement.ALL_FIELDS, edited())).readableBytes());
    }

    @Test
    void carriesEveryOtherOp() {
        assertEquals(EditScreenElementPacket.Op.REMOVE, roundTrip(EditScreenElementPacket.remove(POS, null, 127)).op());
        assertEquals(40, roundTrip(EditScreenElementPacket.zTo(POS, null, 4, 40)).amount());
    }

    @Test
    void anOpOrIdThatCannotBeOneIsNothing() {
        FriendlyByteBuf op = new FriendlyByteBuf(Unpooled.buffer());
        op.writeBlockPos(POS);
        op.writeBoolean(false);
        op.writeByte(42);
        assertNull(decode(op).op());

        FriendlyByteBuf id = new FriendlyByteBuf(Unpooled.buffer());
        id.writeBlockPos(POS);
        id.writeBoolean(false);
        id.writeByte(EditScreenElementPacket.Op.REMOVE.ordinal());
        id.writeByte(200);
        EditScreenElementPacket nothing = decode(id);
        assertNull(nothing.op());

        ScreenElements elements = ScreenElements.defaults(VIEWPORT);
        assertFalse(nothing.apply(elements, VIEWPORT, hash -> true).changed());
        assertEquals(1, elements.size());
        // And a nothing relays as a nothing
        assertNull(decode(encode(nothing)).op());
    }

    @Test
    void sanitisesValuesTheClientMadeUp() {
        EditScreenElementPacket read = forgedSet(0, buffer -> {
            buffer.writeVarInt(OLD_FIELDS | 1 << 30); // every field this test writes, and a bit no field has
            buffer.writeVarInt(-500); // x
            buffer.writeVarInt(1_000_000); // y
            buffer.writeVarInt(0); // w
            buffer.writeVarInt(-3); // h
            buffer.writeInt(0x00123456); // colour, transparent
            buffer.writeInt(0x00FFFFFF); // highlight with no alpha
            buffer.writeVarInt(5000); // stroke
            buffer.writeUtf("a§cb\nc\u0007d"); // text with a section sign and control characters
            buffer.writeUtf("evil:font"); // a font nobody can resolve
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
            buffer.writeVarInt(99_999); // size
            buffer.writeByte(7); // quarters
            buffer.writeByte(9); // across
            buffer.writeByte(-4); // down
        });
        assertNotNull(read.op());
        ScreenElement.Fields values = read.values();
        assertEquals(OLD_FIELDS & ~ScreenElement.FIELD_FONT, read.mask(),
                "the mask is cut to real fields, and an unreadable font is no edit");
        assertEquals(0, values.x());
        assertEquals(Short.MAX_VALUE, values.y());
        // Nought may be a line's; the element it lands on holds anything else to one
        assertEquals(0, values.w());
        assertEquals(0, values.h());
        assertEquals(0xFF123456, values.colour());
        assertEquals(0, values.highlight());
        assertEquals(ScreenElement.MAX_STROKE, values.stroke());
        assertFalse(values.text().contains("§") || values.text().contains("\n")
                || values.text().contains("\u0007"));
        assertEquals(ScreenElement.MAX_SIZE, values.size());
        assertEquals(3, values.quarters());
        assertEquals(ScreenElement.ALIGN_END, values.hAlign());
        assertEquals(ScreenElement.ALIGN_START, values.vAlign());

        // Applied, the element keeps its font and lands inside the viewport
        ScreenElements elements = ScreenElements.defaults(VIEWPORT);
        assertTrue(read.apply(elements, VIEWPORT, hash -> true).changed());
        ScreenElement element = elements.get(0);
        assertEquals(DesignText.DEFAULT_FONT, element.font());
        assertTrue(element.y() + element.h() <= VIEWPORT.height());
        assertEquals(1, element.w());
    }

    @Test
    void textIsCutToTheElementCapOnTheWayIn() {
        String longText = "x".repeat(ScreenElement.MAX_TEXT + 40);
        EditScreenElementPacket read = roundTrip(EditScreenElementPacket.set(POS, null, 0, ScreenElement.FIELD_TEXT,
                new ScreenElement.Fields(0, 0, 1, 1, 0, 0, 1, longText, null, false, false, 0, 0, 1, 1)));
        assertEquals(ScreenElement.MAX_TEXT, read.values().text().length());
    }

    @Test
    void editsToDifferentFieldsOfOneElementBothSurvive() {
        ScreenElements elements = ScreenElements.defaults(VIEWPORT);
        ScreenElement.Fields first = new ScreenElement.Fields(0, 0, 1, 1, 0xFFFF0000, 0, 1, "", null, false, false,
                0, 0, 1, 1);
        ScreenElement.Fields second = new ScreenElement.Fields(0, 0, 1, 1, 0xFF0000FF, 0, 1, "TYPED", null, false,
                false, 0, 0, 1, 1);
        // Two players: one recolours, the other types, each with a stale idea of the other field
        roundTrip(EditScreenElementPacket.set(POS, null, 0, ScreenElement.FIELD_COLOUR, first))
                .apply(elements, VIEWPORT, hash -> false);
        roundTrip(EditScreenElementPacket.set(POS, null, 0, ScreenElement.FIELD_TEXT, second))
                .apply(elements, VIEWPORT, hash -> false);
        assertEquals(0xFFFF0000, elements.get(0).colour());
        assertEquals("TYPED", elements.get(0).text());
    }

    @Test
    void aStoredFontIsTakenAndAMissingOneIsNot() {
        ScreenElement.Fields ttf = new ScreenElement.Fields(0, 0, 1, 1, 0, 0, 1, "", DesignText.TTF_PREFIX + HASH,
                false, false, 0, 0, 1, 1);
        EditScreenElementPacket read = roundTrip(
                EditScreenElementPacket.set(POS, null, 0, ScreenElement.FIELD_FONT, ttf));
        assertEquals(ScreenElement.FIELD_FONT, read.mask());

        ScreenElements missing = ScreenElements.defaults(VIEWPORT);
        assertFalse(read.apply(missing, VIEWPORT, hash -> false).changed());
        assertEquals(DesignText.DEFAULT_FONT, missing.get(0).font());

        ScreenElements stored = ScreenElements.defaults(VIEWPORT);
        assertTrue(read.apply(stored, VIEWPORT, HASH::equals).changed());
        assertEquals(DesignText.TTF_PREFIX + HASH, stored.get(0).font());
    }

    @Test
    void putsAndMovesKeepIdsStable() {
        ScreenElements elements = ScreenElements.defaults(VIEWPORT);
        EditScreenElementPacket.Outcome fill = roundTrip(EditScreenElementPacket.put(POS, null,
                ScreenElement.create(1, ElementKind.FILL, VIEWPORT), 1, 0)).apply(elements, VIEWPORT, hash -> false);
        assertEquals(new EditScreenElementPacket.Outcome(true), fill);
        assertTrue(roundTrip(EditScreenElementPacket.put(POS, null, ScreenElement.create(2, ElementKind.FRAME,
                VIEWPORT), 1, 0)).apply(elements, VIEWPORT, hash -> false).changed());
        assertEquals(1, elements.indexOf(2), "a put lands at the place it names");

        assertTrue(roundTrip(EditScreenElementPacket.zTo(POS, null, 0, 99)).apply(elements, VIEWPORT, h -> false)
                .changed());
        assertEquals(2, elements.indexOf(0));
        assertTrue(roundTrip(EditScreenElementPacket.zTo(POS, null, 0, 1)).apply(elements, VIEWPORT, h -> false)
                .changed());
        assertEquals(1, elements.indexOf(0));
        assertEquals(ElementKind.FILL, elements.get(1).kind());

        assertTrue(roundTrip(EditScreenElementPacket.remove(POS, null, 1)).apply(elements, VIEWPORT, h -> false)
                .changed());
        assertNull(elements.get(1));
        assertEquals(1, elements.generation(1));
        assertFalse(roundTrip(EditScreenElementPacket.remove(POS, null, 1)).apply(elements, VIEWPORT, h -> false)
                .changed());
    }

    @Test
    void aFullDisplayRefusesAPut() {
        ScreenElements elements = new ScreenElements();
        for (int i = 0; i < ScreenElements.MAX; i++)
            assertNotNull(TestElements.add(elements, ElementKind.FILL, VIEWPORT));
        EditScreenElementPacket.Outcome outcome = EditScreenElementPacket.put(POS, null,
                ScreenElement.create(3, ElementKind.TEXT, VIEWPORT), 0, 0).apply(elements, VIEWPORT, hash -> false);
        assertFalse(outcome.changed());
    }
}
