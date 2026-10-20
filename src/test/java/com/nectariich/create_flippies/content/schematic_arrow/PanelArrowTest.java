package com.nectariich.create_flippies.content.schematic_arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;
import com.nectariich.create_flippies.network.ConfigureArrowPacket;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;

import org.junit.jupiter.api.Test;

class PanelArrowTest {

    private static final String ART = "0123456789abcdef0123456789abcdef";
    private static final int PIXEL = PanelPlane.UNITS_PER_PIXEL;
    private static final int BLOCK = PanelPlane.UNITS_PER_BLOCK;
    private static final PanelPlane WALL = new PanelPlane(Direction.SOUTH, 0, 0);

    private static PanelArrow drawn(int... points) {
        return PanelArrow.drawn(7, WALL, 0, ArrowStyle.DEFAULT, points);
    }

    @Test
    void aDrawnArrowSurvivesNbt() {
        PanelArrow arrow = PanelArrow.drawn(42, WALL.flipped(), PanelDefinition.THICKNESS,
                ArrowStyle.DEFAULT.withJoin(ArrowJoin.ROUND).withColour(0xFF3366CC), new int[] { 0, 0, 64, 0, 64, 64 });
        PanelArrow back = PanelArrow.load(arrow.save());
        assertEquals(arrow, back);
        assertEquals(arrow.hashCode(), back.hashCode());
    }

    @Test
    void aStampedArrowSurvivesNbt() {
        PanelArrow arrow = PanelArrow.stamped(9, WALL, 0, ArrowStyle.DEFAULT.withColour(ArrowStyle.OWN_COLOURS), ART,
                new int[] { 0, 0, 128, 64 }, 3);
        PanelArrow back = PanelArrow.load(arrow.save());
        assertEquals(arrow, back);
        assertTrue(back.isStamped());
        assertEquals(ArrowStyle.OWN_COLOURS, back.style().colour());
    }

    @Test
    void anArrowSurvivesAPacket() {
        PanelArrow arrow = drawn(0, 0, 100, 0);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        arrow.write(buffer);
        assertEquals(arrow, PanelArrow.read(buffer));
    }

    @Test
    void damagedTagsReadAsNothing() {
        CompoundTag tag = drawn(0, 0, 100, 0).save();
        tag.put("Points", new IntArrayTag(new int[] { 1, 2, 3 }));
        assertNull(PanelArrow.load(tag));
        tag.put("Points", new IntArrayTag(new int[2 * PanelArrow.MAX_POINTS + 2]));
        assertNull(PanelArrow.load(tag));
        CompoundTag art = PanelArrow.stamped(1, WALL, 0, ArrowStyle.DEFAULT, ART, new int[] { 0, 0, 64, 64 }, 0)
                .save();
        art.putString("Art", "not a hash");
        assertNull(PanelArrow.load(art));
        assertNull(PanelArrow.load(new CompoundTag()));
    }

    @Test
    void equalityLooksAtThePointsNotTheArray() {
        assertEquals(drawn(0, 0, 100, 0), drawn(0, 0, 100, 0));
        assertNotEquals(drawn(0, 0, 100, 0), drawn(0, 0, 100, 8));
    }

    @Test
    void theStyleIsClampedWhereverItComesFrom() {
        ArrowStyle wild = new ArrowStyle(100_000, null, ArrowHead.CHEVRON, null, -5, 0x123456, false);
        assertEquals(ArrowStyle.MAX_WIDTH, wild.width());
        assertEquals(ArrowStyle.MIN_HEIGHT, wild.height());
        assertEquals(ArrowHead.NONE, wild.start());
        assertEquals(ArrowJoin.MITER, wild.join());
        assertEquals(0xFF123456, wild.colour());
        CompoundTag tag = new CompoundTag();
        wild.save(tag);
        tag.putByte("End", (byte) 99);
        assertEquals(ArrowHead.NONE, ArrowStyle.load(tag).end());
    }

    @Test
    void theSettingsPacketRoundTrips() {
        ArrowSettings settings = new ArrowSettings(ArrowStyle.DEFAULT.withWidth(3 * PIXEL).withHeight(PIXEL)
                .withHeads(ArrowHead.CHEVRON, ArrowHead.NONE).withShadedSides(false), ArrowAction.MOVE,
                ArrowSnap.FREE);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new ConfigureArrowPacket(InteractionHand.OFF_HAND, settings).encode(buffer);
        assertEquals(settings, new ConfigureArrowPacket(buffer).settings());
        assertEquals(settings, ArrowSettings.load(settings.save()));
    }

    @Test
    void checkCatchesWhatCannotBeDrawn() {
        assertTrue(drawn(0, 0, 10 * PIXEL, 0).check().ok());
        // Two pixels wide with a triangle: the head alone is four pixels long
        assertEquals(PanelArrow.Problem.TOO_SHORT, drawn(0, 0, 3 * PIXEL, 0).check());
        int[] many = new int[2 * (PanelArrow.MAX_POINTS + 1)];
        for (int i = 0; i < many.length; i += 2)
            many[i] = i * PIXEL;
        assertEquals(PanelArrow.Problem.TOO_MANY_POINTS, drawn(many).check());
        assertEquals(PanelArrow.Problem.TOO_LARGE, drawn(0, 0, 40 * BLOCK, 0).check());
        assertEquals(PanelArrow.Problem.TOO_MANY_CELLS, drawn(0, 0, 31 * BLOCK, 0, 31 * BLOCK, 31 * BLOCK, 0,
                31 * BLOCK, 0, 2 * BLOCK).check());
        assertEquals(PanelArrow.Problem.TOO_SHORT, PanelArrow.stamped(1, WALL, 0, ArrowStyle.DEFAULT, ART,
                new int[] { 0, 0, 4, 64 }, 0).check());
    }

    @Test
    void theBoundsHoldTheOutlineAndTheCellsFollowThem() {
        PanelArrow arrow = drawn(BLOCK / 2, BLOCK / 2, 3 * BLOCK + BLOCK / 2, BLOCK / 2);
        int[] bounds = arrow.bounds();
        for (double[] ring : arrow.outline().rings())
            for (int i = 0; i < ring.length; i += 2) {
                assertTrue(ring[i] >= bounds[0] && ring[i] <= bounds[2]);
                assertTrue(ring[i + 1] >= bounds[1] && ring[i + 1] <= bounds[3]);
            }
        assertEquals(4, arrow.cells().size());
    }

    @Test
    void holdsIsTheOutlineNotTheBox() {
        PanelArrow arrow = drawn(0, 0, 10 * PIXEL, 0, 10 * PIXEL, 10 * PIXEL);
        double pixel = 1.0 / 16;
        assertTrue(arrow.holds(5 * pixel, 0));
        assertTrue(arrow.holds(10 * pixel, 5 * pixel));
        assertFalse(arrow.holds(5 * pixel, 5 * pixel));
    }

    @Test
    void movingSlidesEveryPointAndKeepsTheId() {
        PanelArrow arrow = drawn(0, 0, 160, 0);
        PanelArrow moved = arrow.moved(16, -8);
        assertEquals(arrow.id(), moved.id());
        assertEquals(16, moved.points()[0]);
        assertEquals(-8, moved.points()[3]);
        PanelArrow stamp = PanelArrow.stamped(1, WALL, 0, ArrowStyle.DEFAULT, ART, new int[] { 0, 0, 64, 64 }, 0);
        PanelArrow slid = stamp.moved(8, 8);
        assertEquals(8, slid.minU());
        assertEquals(72, slid.maxV());
    }

    @Test
    void aDraftGrowsAndShrinks() {
        ArrowDraft draft = new ArrowDraft(WALL, 0, new int[] { 0, 0 });
        draft = draft.plus(new int[] { 16, 0 }).plus(new int[] { 16, 16 });
        assertEquals(3, draft.pointCount());
        assertEquals(16, draft.last()[1]);
        assertEquals(2, draft.less().pointCount());
        assertNull(new ArrowDraft(WALL, 0, new int[] { 0, 0 }).less());
    }

    @Test
    void theBackOfAPlateIsItsOwnSurface() {
        PanelArrow back = PanelArrow.drawn(1, WALL.flipped(), PanelDefinition.THICKNESS, ArrowStyle.DEFAULT,
                new int[] { 0, 0, 160, 0 });
        assertEquals(WALL, back.cellPlane());
        assertTrue(back.isOn(WALL.flipped(), PanelDefinition.THICKNESS / (double) BLOCK));
        assertFalse(back.isOn(WALL, 0));
    }

    // Hex colours and sized heads

    @Test
    void hexReadsTheWaysAPlayerTypesIt() {
        assertEquals(0xFF3366CC, ArrowStyle.parseHex("#3366CC"));
        assertEquals(0xFF3366CC, ArrowStyle.parseHex("3366cc"));
        assertEquals(0xFF3366CC, ArrowStyle.parseHex("#36C"));
        assertEquals(0xFFAABBCC, ArrowStyle.parseHex("abc"));
        assertEquals(0xFF000000, ArrowStyle.parseHex("#000000"));
        assertEquals(0xFFFFFFFF, ArrowStyle.parseHex(" #fff "));
        for (String bad : new String[] { "", "#", "#12", "#1234", "#12345", "#1234567", "#GGGGGG", "##123456",
                "12 34 56", "-12345" })
            assertNull(ArrowStyle.parseHex(bad), bad);
        assertNull(ArrowStyle.parseHex(null));
        assertEquals("#3366CC", ArrowStyle.hex(0xFF3366CC));
        assertEquals("#00000A", ArrowStyle.hex(0x0A));
        assertEquals(0xFF123ABC, ArrowStyle.parseHex(ArrowStyle.hex(0x77123ABC)));
    }

    @Test
    void anyColourIsCutToAnOpaque24BitOne() {
        assertEquals(0xFF123456, ArrowStyle.DEFAULT.withColour(0x00123456).colour());
        assertEquals(0xFF123456, ArrowStyle.DEFAULT.withColour(0x7F123456).colour());
        assertEquals(0xFF000000, ArrowStyle.DEFAULT.withColour(0x01000000).colour());
        assertEquals(ArrowStyle.OWN_COLOURS, ArrowStyle.DEFAULT.withColour(ArrowStyle.OWN_COLOURS).colour());
        // A colour with an odd alpha byte, as a hand-made packet might send, arrives cut
        ArrowStyle sent = ArrowStyle.DEFAULT.withColour(0xFFABCDEF);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        new ArrowSettings(sent, ArrowAction.DRAW, ArrowSnap.GRID).write(buffer);
        buffer.setInt(buffer.readerIndex() + 5, 0x12ABCDEF);
        assertEquals(0xFFABCDEF, ArrowSettings.read(buffer).style().colour());
    }

    @Test
    void headSizesAreClampedToTheMenusRanges() {
        ArrowStyle wild = ArrowStyle.DEFAULT.withHeadLength(100_000).withHeadWidth(1).withHeadStroke(-7);
        assertEquals(ArrowStyle.MAX_HEAD_LENGTH, wild.headLength());
        assertEquals(ArrowStyle.MIN_HEAD, wild.headWidth());
        assertEquals(ArrowStyle.AUTO, wild.headStroke());
        assertEquals(ArrowStyle.MAX_HEAD_WIDTH, ArrowStyle.DEFAULT.withHeadWidth(Integer.MAX_VALUE).headWidth());
        assertEquals(ArrowStyle.MAX_HEAD_STROKE, ArrowStyle.DEFAULT.withHeadStroke(1 << 20).headStroke());
        // Every size the menu offers is kept as it is
        for (int option : ArrowStyle.HEAD_LENGTHS)
            assertEquals(option, ArrowStyle.DEFAULT.withHeadLength(option).headLength());
        for (int option : ArrowStyle.HEAD_WIDTHS)
            assertEquals(option, ArrowStyle.DEFAULT.withHeadWidth(option).headWidth());
        for (int option : ArrowStyle.HEAD_STROKES)
            assertEquals(option, ArrowStyle.DEFAULT.withHeadStroke(option).headStroke());
    }

    @Test
    void aStyledArrowSurvivesNbtAndAPacket() {
        ArrowStyle style = ArrowStyle.DEFAULT.withColour(0xFF12AB9C).withHeads(ArrowHead.CHEVRON, ArrowHead.TRIANGLE)
                .withHeadLength(6 * PIXEL).withHeadWidth(12 * PIXEL).withHeadStroke(PIXEL * 3 / 2);
        PanelArrow arrow = PanelArrow.drawn(5, WALL, 0, style, new int[] { 0, 0, 64 * PIXEL, 0 });
        assertEquals(arrow, PanelArrow.load(arrow.save()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        arrow.write(buffer);
        assertEquals(arrow, PanelArrow.read(buffer));

        ArrowSettings settings = ArrowSettings.DEFAULT.withStyle(style);
        FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
        new ConfigureArrowPacket(InteractionHand.MAIN_HAND, settings).encode(packet);
        assertEquals(settings, new ConfigureArrowPacket(packet).settings());
        assertEquals(settings, ArrowSettings.load(settings.save()));
    }

    /** A style tag as builds before hex colours and sized heads wrote it. */
    private static CompoundTag oldStyleTag(int colour) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Width", 3 * PIXEL);
        tag.putByte("Start", (byte) ArrowHead.CHEVRON.ordinal());
        tag.putByte("End", (byte) ArrowHead.TRIANGLE.ordinal());
        tag.putByte("Join", (byte) ArrowJoin.ROUND.ordinal());
        tag.putInt("Height", PIXEL);
        tag.putInt("Colour", colour);
        tag.putBoolean("Shaded", true);
        return tag;
    }

    @Test
    void anOldArrowLoadsUnchanged() {
        ArrowStyle old = new ArrowStyle(3 * PIXEL, ArrowHead.CHEVRON, ArrowHead.TRIANGLE, ArrowJoin.ROUND, PIXEL,
                0xFFD03A3A, true);
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", 3);
        WALL.save(tag);
        tag.putInt("Depth", 0);
        tag.put("Style", oldStyleTag(0xFFD03A3A));
        int[] points = { 0, 0, 40 * PIXEL, 0, 40 * PIXEL, 20 * PIXEL };
        tag.put("Points", new IntArrayTag(points));
        PanelArrow loaded = PanelArrow.load(tag);
        assertEquals(PanelArrow.drawn(3, WALL, 0, old, points), loaded);
        assertEquals(0xFFD03A3A, loaded.style().colour());
        assertEquals(ArrowStyle.AUTO, loaded.style().headLength());
        assertEquals(ArrowStyle.AUTO, loaded.style().headWidth());
        assertEquals(ArrowStyle.AUTO, loaded.style().headStroke());
        // Its outline and its cells are the ones it was drawn with
        ArrowOutline.Shape before = ArrowOutline.of(new double[] { 0, 0, 40 * PIXEL, 0, 40 * PIXEL, 20 * PIXEL },
                3 * PIXEL, ArrowHead.CHEVRON, ArrowHead.TRIANGLE, ArrowJoin.ROUND);
        assertEquals(before.rings().size(), loaded.outline().rings().size());
        for (int i = 0; i < before.rings().size(); i++)
            assertTrue(java.util.Arrays.equals(before.rings().get(i), loaded.outline().rings().get(i)));
        int reach = (int) Math.ceil(ArrowOutline.reach(3 * PIXEL));
        assertEquals(-reach, loaded.bounds()[0]);
        assertEquals(40 * PIXEL + reach, loaded.bounds()[2]);
        // Own colours and the old palette's black still read as themselves
        assertEquals(ArrowStyle.OWN_COLOURS, ArrowStyle.load(oldStyleTag(ArrowStyle.OWN_COLOURS)).colour());
        assertEquals(0xFF1A1A1A, ArrowStyle.load(oldStyleTag(0xFF1A1A1A)).colour());
    }

    @Test
    void anOldToolKeepsItsSettings() {
        CompoundTag tag = oldStyleTag(0xFF3FA34D);
        tag.putByte("Action", (byte) ArrowAction.MOVE.ordinal());
        tag.putByte("Snap", (byte) ArrowSnap.FREE.ordinal());
        ArrowSettings settings = ArrowSettings.load(tag);
        assertEquals(0xFF3FA34D, settings.style().colour());
        assertEquals(ArrowStyle.AUTO, settings.style().headLength());
        assertEquals(ArrowAction.MOVE, settings.action());
    }

    @Test
    void checkMeasuresTheHeadsAsSized() {
        int[] points = { 0, 0, 10 * PIXEL, 0 };
        ArrowStyle big = ArrowStyle.DEFAULT.withHeadLength(12 * PIXEL);
        assertTrue(drawn(points).check().ok());
        assertEquals(PanelArrow.Problem.TOO_SHORT, PanelArrow.drawn(1, WALL, 0, big, points).check());
        assertTrue(PanelArrow.drawn(1, WALL, 0, big, new int[] { 0, 0, 13 * PIXEL, 0 }).check().ok());
        // Square ends ignore the head size
        assertTrue(PanelArrow.drawn(1, WALL, 0, big.withHeads(ArrowHead.NONE, ArrowHead.NONE), points).check().ok());
        // A short head lets a short arrow through
        assertTrue(PanelArrow.drawn(1, WALL, 0, ArrowStyle.DEFAULT.withHeadLength(2 * PIXEL),
                new int[] { 0, 0, 3 * PIXEL, 0 }).check().ok());
    }

    @Test
    void wideHeadsWidenTheBounds() {
        int[] points = { BLOCK / 2, BLOCK / 2, 3 * BLOCK, BLOCK / 2 };
        PanelArrow wide = PanelArrow.drawn(1, WALL, 0, ArrowStyle.DEFAULT.withHeadWidth(32 * PIXEL)
                .withHeads(ArrowHead.CHEVRON, ArrowHead.TRIANGLE), points);
        int[] bounds = wide.bounds();
        for (double[] ring : wide.outline().rings())
            for (int i = 0; i < ring.length; i += 2) {
                assertTrue(ring[i] >= bounds[0] && ring[i] <= bounds[2]);
                assertTrue(ring[i + 1] >= bounds[1] && ring[i + 1] <= bounds[3]);
            }
        assertTrue(bounds[3] - bounds[1] > drawn(points).bounds()[3] - drawn(points).bounds()[1]);
    }
}
