package com.nectariich.create_flippies.foundation.wire.duct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.Direction;
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
        Vec3 snapped = DuctPlacement.snap(new Vec3(4, 10.51, 3.27), Direction.EAST, DuctSize.SMALL);
        assertVec(new Vec3(4 + DuctSize.SMALL.depth() / 2, 10.5, 3.25), snapped);
        // On the floor it lifts up
        Vec3 floor = DuctPlacement.snap(new Vec3(1.03, 64, 2), Direction.UP, DuctSize.LARGE);
        assertVec(new Vec3(1, 64 + DuctSize.LARGE.depth() / 2, 2), floor);
    }

    @Test
    void theDuctsBackSitsFlushOnTheFace() {
        Vec3 a = DuctPlacement.snap(new Vec3(4, 10, 3), Direction.EAST, DuctSize.SMALL);
        Vec3 b = DuctPlacement.snap(new Vec3(4, 10, 6), Direction.EAST, DuctSize.SMALL);
        AABB box = DuctPlacement.plan(a, b, Direction.EAST, DuctSize.SMALL).get(0).box();
        assertEquals(4, box.minX, EPS);
        assertEquals(4 + DuctSize.SMALL.depth(), box.maxX, EPS);
        assertEquals(10 - DuctSize.SMALL.width() / 2, box.minY, EPS);
        assertEquals(3, box.minZ, EPS);
        assertEquals(6, box.maxZ, EPS);
    }

    @Test
    void pointsInLineMakeOneStraightRun() {
        Vec3 a = new Vec3(1, 64.0625, 1), b = new Vec3(5, 64.0625, 1);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.SMALL);
        assertEquals(1, pieces.size());
        assertVec(a, pieces.get(0).from());
        assertVec(b, pieces.get(0).to());
        assertEquals(Direction.Axis.X, pieces.get(0).axis());
    }

    @Test
    void pointsOutOfLineMakeAnLTurningAfterTheLongerLeg() {
        Vec3 a = new Vec3(1, 64.0625, 1), b = new Vec3(6, 64.0625, 3);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.SMALL);
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
        assertTrue(DuctPlacement.plan(a, new Vec3(3, 65, 1), Direction.UP, DuctSize.SMALL).isEmpty());
        assertTrue(DuctPlacement.plan(a, a, Direction.UP, DuctSize.SMALL).isEmpty());
        assertEquals(DuctPlacement.Verdict.NOT_FLAT, DuctPlacement.check(List.of(), a, 5));
    }

    @Test
    void runsAreCutAtEverySectionBorder() {
        Vec3 a = new Vec3(10, 64.0625, 1), b = new Vec3(40, 64.0625, 1);
        List<DuctSegment> pieces = DuctPlacement.plan(a, b, Direction.UP, DuctSize.SMALL);
        assertEquals(3, pieces.size());
        assertEquals(16, pieces.get(0).to().x, EPS);
        assertEquals(32, pieces.get(1).to().x, EPS);
        assertEquals(30, DuctPlacement.totalLength(pieces), EPS);
        // Running the other way cuts at the same places, in order
        List<DuctSegment> back = DuctPlacement.plan(b, a, Direction.UP, DuctSize.SMALL);
        assertEquals(32, back.get(0).to().x, EPS);
        assertEquals(16, back.get(1).to().x, EPS);
        // A vertical run on a wall is cut too, so no piece is longer than 16
        List<DuctSegment> up = DuctPlacement.plan(new Vec3(0.0625, 60, 0), new Vec3(0.0625, 100, 0),
                Direction.EAST, DuctSize.SMALL);
        for (DuctSegment piece : up)
            assertTrue(piece.length() <= 16 + EPS);
        assertEquals(40, DuctPlacement.totalLength(up), EPS);
        // A run ending exactly on a border is not cut there
        assertEquals(1, DuctPlacement.plan(new Vec3(4, 1, 1), new Vec3(16, 1, 1), Direction.UP, DuctSize.SMALL)
                .size());
    }

    @Test
    void checkRefusesTooShortTooLongAndOutOfReach() {
        Vec3 eye = new Vec3(0, 65, 0);
        List<DuctSegment> ok = DuctPlacement.plan(new Vec3(1, 64.0625, 0), new Vec3(3, 64.0625, 0), Direction.UP,
                DuctSize.SMALL);
        assertEquals(DuctPlacement.Verdict.OK, DuctPlacement.check(ok, eye, 5));
        List<DuctSegment> far = DuctPlacement.plan(new Vec3(1, 64.0625, 0), new Vec3(20, 64.0625, 0), Direction.UP,
                DuctSize.SMALL);
        assertEquals(DuctPlacement.Verdict.OUT_OF_REACH, DuctPlacement.check(far, eye, 5));
        List<DuctSegment> huge = DuctPlacement.plan(new Vec3(1, 64.0625, 0), new Vec3(80, 64.0625, 0),
                Direction.UP, DuctSize.SMALL);
        assertEquals(DuctPlacement.Verdict.TOO_LONG, DuctPlacement.check(huge, eye, 100));
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
        double h = DuctSize.SMALL.depth() / 2;
        Vec3 corner = new Vec3(4 + h, 64 + h, 2);
        DuctSegment wall = new DuctSegment(new Vec3(4 + h, 66, 2), corner, Direction.EAST, DuctSize.SMALL);
        List<DuctSegment> floor = DuctPlacement.plan(corner, new Vec3(7, 64 + h, 2), Direction.UP, DuctSize.SMALL);
        assertEquals(1, floor.size());
        assertVec(corner, wall.jointWith(floor.get(0)));
    }
}
