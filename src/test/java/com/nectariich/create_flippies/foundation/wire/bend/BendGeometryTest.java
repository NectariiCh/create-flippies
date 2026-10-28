package com.nectariich.create_flippies.foundation.wire.bend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.wire.CurveParameters;

class BendGeometryTest {

    /** Power Grid's copper wire. */
    private static final double H = 1.01, V = 1.2, T = 0.0625;

    private record Piece(int leg, Vec3 from, Vec3 to, double offset, double length) {}

    private static List<Piece> pieces(List<Vec3> path, double step) {
        List<Piece> out = new ArrayList<>();
        BendGeometry.forEachPiece(path, H, V, T, step, (leg, from, to, offset, length) ->
                out.add(new Piece(leg, from, to, offset, length)));
        return out;
    }

    private static void near(Vec3 expected, Vec3 actual, double tolerance) {
        assertTrue(expected.distanceTo(actual) < tolerance, "expected " + expected + " but was " + actual);
    }

    @Test
    void eachLegStartsAndEndsOnItsPathPoints() {
        Vec3 a = new Vec3(0, 70, 0), p = new Vec3(5, 68, 3), b = new Vec3(9, 71, -2);
        List<Piece> pieces = pieces(List.of(a, p, b), 0.25);
        Piece firstOfLeg0 = pieces.stream().filter(x -> x.leg() == 0).findFirst().orElseThrow();
        Piece lastOfLeg0 = pieces.stream().filter(x -> x.leg() == 0).reduce((x, y) -> y).orElseThrow();
        Piece firstOfLeg1 = pieces.stream().filter(x -> x.leg() == 1).findFirst().orElseThrow();
        Piece lastOfLeg1 = pieces.get(pieces.size() - 1);
        near(a, firstOfLeg0.from(), 1e-4);
        near(p, lastOfLeg0.to(), 1e-4);
        near(p, firstOfLeg1.from(), 1e-4);
        near(b, lastOfLeg1.to(), 1e-4);
    }

    @Test
    void eachLegSagsAsItsOwnWireWould() {
        Vec3 a = new Vec3(0, 70, 0), p = new Vec3(10, 70, 0), b = new Vec3(10, 70, 10);
        List<Vec3> path = List.of(a, p, b);
        // The lowest point of each leg is the lowest point of a lone wire between its ends
        for (int leg = 0; leg < 2; leg++) {
            Vec3 from = path.get(leg), to = path.get(leg + 1);
            //? if forge {
            CurveParameters lone = new CurveParameters(from, to, H, V, T);
            //?} else {
            /*CurveParameters lone = new CurveParameters(from, to, BendGeometry.curveLength(from, to, H, V), T);
            *///?}
            double[] loneLowest = {Double.MAX_VALUE};
            lone.runForSegments((x1, y1, z1, x2, y2, z2, o, l) -> loneLowest[0] = Math.min(loneLowest[0], y2 + from.y),
                    0.25);
            int index = leg;
            double bentLowest = pieces(path, 0.25).stream().filter(x -> x.leg() == index)
                    .mapToDouble(x -> x.to().y).min().orElseThrow();
            assertEquals(loneLowest[0], bentLowest, 1e-9);
            assertTrue(bentLowest < 70 - 0.1, "a level 10-block leg sags");
        }
        // The legs meet at the bend point rather than dipping through it
        double atBend = pieces(path, 0.25).stream().filter(x -> x.leg() == 0).reduce((x, y) -> y).orElseThrow()
                .to().y;
        assertEquals(70, atBend, 1e-4);
    }

    @Test
    void offsetsRunOnAcrossLegs() {
        List<Piece> pieces = pieces(List.of(new Vec3(0, 0, 0), new Vec3(4, 0, 0), new Vec3(4, 0, 4)), 0.5);
        double along = 0;
        for (Piece piece : pieces) {
            assertEquals(along, piece.offset(), 1e-9);
            along += piece.length();
        }
    }

    @Test
    void verticalAndDegenerateLegs() {
        Vec3 a = new Vec3(0, 70, 0), above = new Vec3(0, 74, 0);
        List<Piece> vertical = pieces(List.of(a, above), 0.25);
        near(above, vertical.get(vertical.size() - 1).to(), 1e-6);
        // Two points on one another draw nothing between them
        assertEquals(0, pieces(List.of(a, a), 0.25).size());
    }

    @Test
    void boundsHoldTheSagAndEveryPoint() {
        Vec3 a = new Vec3(0, 70, 0), p = new Vec3(10, 75, 0), b = new Vec3(20, 70, 0);
        List<Vec3> path = List.of(a, p, b);
        AABB box = BendGeometry.bounds(path, H, V, T);
        for (Vec3 point : path)
            assertTrue(box.contains(point));
        for (Piece piece : pieces(path, 0.1))
            assertTrue(box.contains(piece.to()), "sag point " + piece.to());
        assertTrue(box.maxY >= 75);
    }

    @Test
    void touchesOnlyWhereALegPasses() {
        List<Vec3> path = List.of(new Vec3(0, 70, 0), new Vec3(5, 70, 5), new Vec3(10, 70, 0));
        assertTrue(BendGeometry.touches(path, H, V, T, new AABB(4.5, 68, 4.5, 5.5, 71, 5.5)));
        // The straight line between the ends passes here, the bent wire does not
        assertTrue(!BendGeometry.touches(path, H, V, T, new AABB(4.5, 68, -0.5, 5.5, 71, 0.5)));
    }

    @Test
    void pickPrefersAPointOverTheWireUnderIt() {
        Vec3 a = new Vec3(0, 70, 0), p = new Vec3(5, 71, 0), b = new Vec3(10, 70, 0);
        List<Vec3> path = List.of(a, p, b);
        Vec3 eye = new Vec3(5, 71, 5);
        BendGeometry.Hit hit = BendGeometry.pick(eye, new Vec3(0, 0, -1), 10, List.of(p), path, H, V, T);
        assertNotNull(hit);
        assertTrue(hit.isPoint());
        assertEquals(0, hit.point());
        assertEquals(5, hit.distance(), 1e-9);
    }

    @Test
    void pickOnALegNamesTheLegAndAPlaceOnTheWire() {
        Vec3 a = new Vec3(0, 70, 0), p = new Vec3(4, 70, 0), b = new Vec3(4, 70, 8);
        List<Vec3> path = List.of(a, p, b);
        // Aim straight down at the middle of the second leg, where it hangs lowest
        //? if forge {
        CurveParameters leg = new CurveParameters(p, b, H, V, T);
        //?} else {
        /*CurveParameters leg = new CurveParameters(p, b, BendGeometry.curveLength(p, b, H, V), T);
        *///?}
        double sagY = 70 + leg.apply(0);
        Vec3 eye = new Vec3(4, 73, 4);
        BendGeometry.Hit hit = BendGeometry.pick(eye, new Vec3(0, -1, 0), 10, List.of(p), path, H, V, T);
        assertNotNull(hit);
        assertTrue(!hit.isPoint());
        assertEquals(1, hit.leg());
        assertEquals(sagY, hit.onWire().y, 0.02);
        assertEquals(4, hit.onWire().z, 0.15);
        // Inserting there puts the new point between the leg's ends
        List<Vec3> after = WireBends.apply(WireBends.Op.INSERT, List.of(p), hit.leg(), hit.onWire());
        assertEquals(p, after.get(0));
        assertEquals(hit.onWire(), after.get(1));
    }

    @Test
    void pickMissesBeyondReachAndBesideTheWire() {
        List<Vec3> path = List.of(new Vec3(0, 70, 0), new Vec3(10, 70, 0));
        assertNull(BendGeometry.pick(new Vec3(5, 70, 5), new Vec3(0, 0, -1), 3, List.of(), path, H, V, T));
        assertNull(BendGeometry.pick(new Vec3(5, 75, 5), new Vec3(0, 0, -1), 10, List.of(), path, H, V, T));
    }

    @Test
    void raycastFindsABentLegTheStraightCurveWouldNot() {
        Vec3 a = new Vec3(0, 70, 0), p = new Vec3(5, 74, 0), b = new Vec3(10, 70, 0);
        List<Vec3> path = List.of(a, p, b);
        Vec3 from = new Vec3(5, 74, 4), to = new Vec3(5, 74, -4);
        Vec3 hit = BendGeometry.raycast(from, to, path, H, V, T);
        assertNotNull(hit);
        assertEquals(4, hit.distanceTo(from), 0.1);
        assertNull(BendGeometry.raycast(from, to, List.of(a, b), H, V, T));
    }

    @Test
    void dragHoldsThePointAtItsGrabDistance() {
        Vec3 eye = new Vec3(1, 65, 1);
        Vec3 point = new Vec3(4, 66, 5);
        double distance = BendGeometry.grabDistance(eye, point);
        assertEquals(Math.sqrt(9 + 1 + 16), distance, 1e-12);
        // Looking straight at the point leaves it where it is
        near(point, BendGeometry.dragTarget(eye, point.subtract(eye), distance), 1e-9);
        // Turning the head moves it over a sphere around the eye, never nearer or further
        Vec3 turned = BendGeometry.dragTarget(eye, new Vec3(0, 0.3, -2), distance);
        assertEquals(distance, turned.distanceTo(eye), 1e-9);
        // Walking carries it along with the eye
        Vec3 walked = BendGeometry.dragTarget(eye.add(2, 0, 0), point.subtract(eye), distance);
        near(point.add(2, 0, 0), walked, 1e-9);
    }
}
