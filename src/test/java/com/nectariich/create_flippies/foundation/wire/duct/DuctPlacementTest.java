package com.nectariich.create_flippies.foundation.wire.duct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class DuctPlacementTest {

    private static final double EPS = 1e-9;

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-6, "x of " + actual);
        assertEquals(expected.y, actual.y, 1e-6, "y of " + actual);
        assertEquals(expected.z, actual.z, 1e-6, "z of " + actual);
    }

    @Test
    void snapPutsTheCentreLineHalfADepthOffTheFaceOnThePixelGrid() {
        // A click on the east face of a block at x = 4, slightly off the grid
        Vec3 snapped = DuctPlacement.snap(new Vec3(4, 10.51, 3.27), Direction.EAST, DuctSize.PX_4);
        assertVec(new Vec3(4 + DuctSize.PX_4.depth() / 2, 10.5, 3.25), snapped);
        // On the floor it lifts up
        Vec3 floor = DuctPlacement.snap(new Vec3(1.03, 64, 2), Direction.UP, DuctSize.PX_6);
        assertVec(new Vec3(1, 64 + DuctSize.PX_6.depth() / 2, 2), floor);
    }

    @Test
    void theDuctsBackSitsFlushOnTheFace() {
        Vec3 a = DuctPlacement.snap(new Vec3(4, 10, 3), Direction.EAST, DuctSize.PX_4);
        Vec3 b = DuctPlacement.snap(new Vec3(4, 10, 6), Direction.EAST, DuctSize.PX_4);
        AABB box = DuctPlacement.plan(a, b, Direction.EAST, DuctSize.PX_4).get(0).box();
        assertEquals(4, box.minX, EPS);
        assertEquals(4 + DuctSize.PX_4.depth(), box.maxX, EPS);
        assertEquals(10 - DuctSize.PX_4.width() / 2, box.minY, EPS);
        assertEquals(3, box.minZ, EPS);
        assertEquals(6, box.maxZ, EPS);
    }

    @Test
    void pointsInLineMakeOneStraightRun() {
        Vec3 a = new Vec3(1, 64.0625, 1), b = new Vec3(5, 64.0625, 1);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.PX_4);
        assertEquals(1, pieces.size());
        assertVec(a, pieces.get(0).from());
        assertVec(b, pieces.get(0).to());
        assertEquals(Direction.Axis.X, pieces.get(0).axis());
    }

    @Test
    void pointsOutOfLineMakeAnLTurningAfterTheLongerLeg() {
        Vec3 a = new Vec3(1, 64.0625, 1), b = new Vec3(6, 64.0625, 3);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.PX_4);
        assertEquals(2, pieces.size());
        // The X leg is longer (5 against 2), so it goes first
        assertEquals(Direction.Axis.X, pieces.get(0).axis());
        assertEquals(Direction.Axis.Z, pieces.get(1).axis());
        assertVec(new Vec3(6, 64.0625, 1), pieces.get(0).to());
        assertVec(pieces.get(0).to(), pieces.get(1).from());
        assertVec(b, pieces.get(1).to());
        // Every piece stays axis aligned and in the face
        for (DuctSegment piece : pieces)
            assertEquals(64.0625, piece.from().y, EPS);
    }

    @Test
    void pointsOffTheFacePlaneOrOnTopOfOneAnotherPlaceNothing() {
        Vec3 a = new Vec3(1, 64.0625, 1);
        assertTrue(DuctPlacement.plan(a, new Vec3(3, 65, 1), Direction.UP, DuctSize.PX_4).isEmpty());
        assertTrue(DuctPlacement.plan(a, a, Direction.UP, DuctSize.PX_4).isEmpty());
        assertEquals(DuctPlacement.Verdict.NOT_FLAT, DuctPlacement.check(List.of(), a, 5));
    }

    @Test
    void runsAreCutAtEverySectionBorder() {
        Vec3 a = new Vec3(10, 64.0625, 1), b = new Vec3(40, 64.0625, 1);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.PX_4);
        assertEquals(3, pieces.size());
        assertEquals(16, pieces.get(0).to().x, EPS);
        assertEquals(32, pieces.get(1).to().x, EPS);
        assertEquals(30, DuctPlacement.totalLength(pieces), EPS);
        // Running the other way cuts at the same places, in order
        List<DuctSegment> back = DuctPlacement.plan(b, a, Direction.UP, DuctSize.PX_4);
        assertEquals(32, back.get(0).to().x, EPS);
        assertEquals(16, back.get(1).to().x, EPS);
        // A vertical run on a wall is cut too, so no piece is longer than 16
        List<DuctSegment> up = DuctPlacement.plan(new Vec3(0.0625, 60, 0), new Vec3(0.0625, 100, 0),
                Direction.EAST, DuctSize.PX_4);
        for (DuctSegment piece : up)
            assertTrue(piece.length() <= 16 + EPS);
        assertEquals(40, DuctPlacement.totalLength(up), EPS);
        // A run ending exactly on a border is not cut there
        assertEquals(1, DuctPlacement.plan(new Vec3(4, 1, 1), new Vec3(16, 1, 1), Direction.UP, DuctSize.PX_4)
                .size());
    }

    @Test
    void aDuctMayBeAnyLengthAsLongAsTheEndClickedIsInReach() {
        // A thousand blocks down a corridor: one placement, cut into joined section-long pieces
        Vec3 a = new Vec3(3, 64.0625, 1), b = new Vec3(1003, 64.0625, 1);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.PX_4);
        assertEquals(1000, DuctPlacement.totalLength(pieces), 1e-6);
        assertEquals(63, pieces.size());
        for (int i = 0; i + 1 < pieces.size(); i++) {
            assertTrue(pieces.get(i).length() <= 16 + EPS);
            assertVec(pieces.get(i).to(), pieces.get(i).jointWith(pieces.get(i + 1)));
        }
        // Standing at the far end, where the second click is made
        Vec3 eye = new Vec3(1002, 65.6, 1);
        assertEquals(DuctPlacement.Verdict.OK, DuctPlacement.check(pieces, eye, 5));
        // Only the end being clicked has to be in reach
        assertEquals(DuctPlacement.Verdict.OUT_OF_REACH, DuctPlacement.check(pieces, new Vec3(3, 65.6, 1), 5));
        // And a slide along all of it is one pass through 63 joined pieces
        DuctPass pass = DuctNetwork.route(pieces, 0, a, pieces.size() - 1, b, 0);
        assertEquals(1000, pass.length(), 1e-6);
    }

    @Test
    void checkStillRefusesTooShort() {
        List<DuctSegment> tiny = List.of(new DuctSegment(new Vec3(1, 64.0625, 0), new Vec3(1.01, 64.0625, 0),
                Direction.UP, DuctSize.PX_4));
        assertEquals(DuctPlacement.Verdict.TOO_SHORT, DuctPlacement.check(tiny, new Vec3(1, 65, 0), 5));
    }

    @Test
    void itemsArePricedPerBlockBegun() {
        assertEquals(1, DuctPlacement.itemCost(0.25));
        assertEquals(1, DuctPlacement.itemCost(1));
        assertEquals(2, DuctPlacement.itemCost(1.0625));
        assertEquals(16, DuctPlacement.itemCost(16));
    }

    @Test
    void aClickNearAnExistingEndSnapsOntoIt() {
        Vec3 end = new Vec3(2.0625, 64.0625, 1);
        assertVec(end, DuctPlacement.snapToEnds(new Vec3(2.1, 64.0625, 1.1), List.of(end)));
        Vec3 away = new Vec3(3, 64.0625, 1);
        assertVec(away, DuctPlacement.snapToEnds(away, List.of(end)));
    }

    @Test
    void anInsideCornerFromWallToFloorMeetsAtOneEnd() {
        // A wall duct coming down to the floor and a floor duct leaving the wall share a centre-line end
        double h = DuctSize.PX_4.depth() / 2;
        Vec3 corner = new Vec3(4 + h, 64 + h, 2);
        DuctSegment wall = new DuctSegment(new Vec3(4 + h, 66, 2), corner, Direction.EAST, DuctSize.PX_4);
        List<DuctSegment> floor = DuctPlacement.plan(corner, new Vec3(7, 64 + h, 2), Direction.UP, DuctSize.PX_4);
        assertEquals(1, floor.size());
        assertVec(corner, wall.jointWith(floor.get(0)));
    }

    /** The boxes of planned pieces as placing them joins them: each joint tiled by {@link DuctNetwork#jointExtensions}. */
    private static List<AABB> joinedBoxes(List<DuctSegment> pieces) {
        double[] from = new double[pieces.size()], to = new double[pieces.size()];
        for (int i = 0; i + 1 < pieces.size(); i++) {
            double[] ext = DuctNetwork.jointExtensions(pieces.get(i), pieces.get(i + 1));
            to[i] = ext[0];
            from[i + 1] = ext[1];
        }
        List<AABB> boxes = new java.util.ArrayList<>();
        for (int i = 0; i < pieces.size(); i++)
            boxes.add(pieces.get(i).box(from[i], to[i]));
        return boxes;
    }

    private static void assertBox(AABB expected, AABB actual) {
        assertEquals(expected.minX, actual.minX, 1e-6, "minX of " + actual);
        assertEquals(expected.minY, actual.minY, 1e-6, "minY of " + actual);
        assertEquals(expected.minZ, actual.minZ, 1e-6, "minZ of " + actual);
        assertEquals(expected.maxX, actual.maxX, 1e-6, "maxX of " + actual);
        assertEquals(expected.maxY, actual.maxY, 1e-6, "maxY of " + actual);
        assertEquals(expected.maxZ, actual.maxZ, 1e-6, "maxZ of " + actual);
    }

    @Test
    void aDuctTurnsFromTheFloorUpAWallOverTheInsideCorner() {
        // A wall facing east at x = 1, the floor at y = 64
        DuctSize size = DuctSize.PX_4;
        double h = size.depth() / 2, d = size.depth(), w = size.width() / 2;
        Vec3 floor = new Vec3(3, 64 + h, 2), wall = new Vec3(1 + h, 66, 2);
        List<DuctSegment> pieces = DuctPlacement.plan(floor, Direction.UP, wall, Direction.EAST, size, p -> false);
        assertEquals(2, pieces.size());
        assertEquals(Direction.UP, pieces.get(0).face());
        assertEquals(Direction.EAST, pieces.get(1).face());
        assertVec(new Vec3(1 + h, 64 + h, 2), pieces.get(0).to());
        assertVec(pieces.get(0).to(), pieces.get(1).from());
        assertVec(wall, pieces.get(1).to());
        // The floor stretch runs on into the corner, and the wall stretch starts on its lid
        List<AABB> boxes = joinedBoxes(pieces);
        assertBox(new AABB(1, 64, 2 - w, 3, 64 + d, 2 + w), boxes.get(0));
        assertBox(new AABB(1, 64 + d, 2 - w, 1 + d, 66, 2 + w), boxes.get(1));
    }

    @Test
    void aDuctTurnsOverTheTopOfABlockAndDownItsSide() {
        // A block's top at y = 65 and its east side at x = 1
        DuctSize size = DuctSize.PX_6;
        double h = size.depth() / 2, d = size.depth(), w = size.width() / 2;
        Vec3 top = new Vec3(0.5, 65 + h, 2), side = new Vec3(1 + h, 64.5, 2);
        List<DuctSegment> pieces = DuctPlacement.plan(top, Direction.UP, side, Direction.EAST, size, p -> false);
        assertEquals(2, pieces.size());
        assertVec(new Vec3(1 + h, 65 + h, 2), pieces.get(0).to());
        // The top stretch runs on past the edge to cover the side's lid, which starts at the top
        List<AABB> boxes = joinedBoxes(pieces);
        assertBox(new AABB(0.5, 65, 2 - w, 1 + d, 65 + d, 2 + w), boxes.get(0));
        assertBox(new AABB(1, 64.5, 2 - w, 1 + d, 65, 2 + w), boxes.get(1));
    }

    @Test
    void aClickOnTheEdgeItselfStillFillsTheInsideCorner() {
        DuctSize size = DuctSize.PX_4;
        double h = size.depth() / 2, d = size.depth(), w = size.width() / 2;
        // The first click right where the floor meets the wall: a short stretch fills the corner
        Vec3 edge = new Vec3(1, 64 + h, 2), wall = new Vec3(1 + h, 66, 2);
        List<DuctSegment> pieces = DuctPlacement.plan(edge, Direction.UP, wall, Direction.EAST, size, p -> false);
        assertEquals(2, pieces.size());
        List<AABB> boxes = joinedBoxes(pieces);
        assertBox(new AABB(1, 64, 2 - w, 1 + d, 64 + d, 2 + w), boxes.get(0));
        assertBox(new AABB(1, 64 + d, 2 - w, 1 + d, 66, 2 + w), boxes.get(1));
        // The second click there instead: the wall stretch lies inside the floor's corner and shrinks away
        Vec3 floor = new Vec3(3, 64 + h, 2), foot = new Vec3(1 + h, 64, 2);
        boxes = joinedBoxes(DuctPlacement.plan(floor, Direction.UP, foot, Direction.EAST, size, p -> p.x < 1));
        assertBox(new AABB(1, 64, 2 - w, 3, 64 + d, 2 + w), boxes.get(0));
        assertEquals(0, boxes.get(1).getYsize(), 1e-6);
    }

    @Test
    void bothClicksOnTheEdgeAskTheWorldWhichSideTheBlockIs() {
        DuctSize size = DuctSize.PX_4;
        double h = size.depth() / 2;
        Vec3 onFloor = new Vec3(1, 64 + h, 2), onWall = new Vec3(1 + h, 64, 2);
        // A wall block behind the wall face: inside, both points moved onto the faces' meeting line
        List<DuctSegment> inside = DuctPlacement.plan(onFloor, Direction.UP, onWall, Direction.EAST, size,
                p -> p.x < 1);
        for (DuctSegment piece : inside)
            assertTrue(piece.box().minX >= 1 - 1e-6 && piece.box().minY >= 64 - 1e-6, "inside the room: " + piece);
        // Nothing there: the edge of a block, and the duct wraps round it
        List<DuctSegment> outside = DuctPlacement.plan(onFloor, Direction.UP, onWall, Direction.EAST, size,
                p -> false);
        assertEquals(2, outside.size());
        assertVec(new Vec3(1 + h, 64 + h, 2), outside.get(0).to());
        assertEquals(Direction.Axis.Y, outside.get(1).axis());
        assertTrue(outside.get(1).to().y < outside.get(1).from().y);
    }

    @Test
    void aShiftAlongTheEdgeIsMadeOnTheFaceFurtherFromIt() {
        DuctSize size = DuctSize.PX_4;
        double h = size.depth() / 2;
        Vec3 floor = new Vec3(5, 64 + h, 2), wall = new Vec3(1 + h, 65, 6);
        List<DuctSegment> pieces = DuctPlacement.plan(floor, Direction.UP, wall, Direction.EAST, size, p -> false);
        assertEquals(3, pieces.size());
        assertEquals(Direction.Axis.Z, pieces.get(0).axis());
        assertEquals(Direction.UP, pieces.get(0).face());
        assertEquals(Direction.Axis.X, pieces.get(1).axis());
        assertEquals(Direction.Axis.Y, pieces.get(2).axis());
        assertEquals(Direction.EAST, pieces.get(2).face());
        // Nearer the floor than the wall's click is to the wall: the shift goes on the wall
        Vec3 near = new Vec3(2, 64 + h, 2), high = new Vec3(1 + h, 67, 6);
        pieces = DuctPlacement.plan(near, Direction.UP, high, Direction.EAST, size, p -> false);
        assertEquals(3, pieces.size());
        assertEquals(Direction.Axis.X, pieces.get(0).axis());
        assertEquals(Direction.Axis.Y, pieces.get(1).axis());
        assertEquals(Direction.Axis.Z, pieces.get(2).axis());
        assertEquals(Direction.EAST, pieces.get(2).face());
    }

    @Test
    void facesNoEdgeJoinsPlaceNothing() {
        DuctSize size = DuctSize.PX_4;
        double h = size.depth() / 2;
        // In front of the wall on the floor, but on the wall below the floor
        Vec3 floor = new Vec3(3, 64 + h, 2), below = new Vec3(1 + h, 62, 2);
        assertTrue(DuctPlacement.plan(floor, Direction.UP, below, Direction.EAST, size, p -> false).isEmpty());
        // Floor and ceiling never meet
        assertTrue(DuctPlacement.plan(floor, Direction.UP, new Vec3(3, 67 - h, 4), Direction.DOWN, size,
                p -> false).isEmpty());
    }

    // Colour

    @Test
    void aDuctIsWhiteUntilDyedAndKeepsItsColourThroughASave() {
        // A save from before ducts had colours, or a plain item, reads as white
        assertEquals(DuctColor.WHITE, DuctColor.read(new CompoundTag()));
        assertEquals(DuctColor.WHITE, DuctColor.read(null));
        // Red dye's diffuse colour, as the game gives it
        int red = DuctColor.of(new float[] {0.6901961f, 0.18039216f, 0.14901961f});
        assertEquals(0xB02E26, red);
        CompoundTag saved = new CompoundTag();
        DuctColor.write(saved, red);
        assertEquals(red, DuctColor.read(saved));
        // Read back from a copy, as a save is
        assertEquals(red, DuctColor.read(saved.copy()));
    }

    @Test
    void shadingDarkensEachChannelAndKeepsWhiteWhiteOnTop() {
        assertEquals(0xFFFFFFFF, DuctColor.shaded(DuctColor.WHITE, 1f));
        assertEquals(0xFF808080, DuctColor.shaded(DuctColor.WHITE, 128 / 255f));
        assertEquals(0xFF400000, DuctColor.shaded(0x800000, 0.5f));
    }
}
