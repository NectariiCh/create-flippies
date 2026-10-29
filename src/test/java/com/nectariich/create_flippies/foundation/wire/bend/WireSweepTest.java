package com.nectariich.create_flippies.foundation.wire.bend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.patryk3211.powergrid.electricity.wire.CurveParameters;

class WireSweepTest {

    /** Power Grid's copper wire, and its copper cord. */
    private static final double H = 1.01, V = 1.2, T = 0.0625;
    private static final double CORD = 1.005, CORD_T = 0.125;

    private static final Vec3 A = new Vec3(0, 70, 0), P = new Vec3(5, 68, 3), Q = new Vec3(9, 71, -2),
            B = new Vec3(14, 70, -6);

    private static List<WireSweep.Strip> sweep(List<Vec3> path, long ducted) {
        return WireSweep.sweep(path, ducted, H, V, T, 0.5, null);
    }

    private static void near(Vec3 expected, Vec3 actual, double tolerance) {
        assertTrue(expected.distanceTo(actual) < tolerance, "expected " + expected + " but was " + actual);
    }

    private static Vec3[] corners(WireSweep.Ring ring) {
        Vec3 c1 = ring.cross1(), c2 = ring.cross2();
        return new Vec3[] {ring.at().add(c1), ring.at().add(c2), ring.at().subtract(c1), ring.at().subtract(c2)};
    }

    @Test
    void legVerticesEndExactlyOnTheirPoints() {
        for (Vec3[] leg : new Vec3[][] {{A, P}, {P, Q}, {new Vec3(0.3, 64, 0.1), new Vec3(7.3, 61.9, 3.7)},
                {new Vec3(0, 64, 0), new Vec3(0, 68, 0)}}) {
            List<Vec3> v = BendGeometry.legVertices(leg[0], leg[1], H, V, T, 0.5);
            assertEquals(leg[0], v.get(0));
            assertEquals(leg[1], v.get(v.size() - 1));
        }
    }

    @Test
    void legVerticesFollowPowerGridsCurve() {
        //? if forge {
        CurveParameters curve = new CurveParameters(A, P, H, V, T);
        //?} else {
        /*CurveParameters curve = new CurveParameters(A, P, BendGeometry.curveLength(A, P, H, V), T);
        *///?}
        Vec3 origin = BendGeometry.curveOrigin(A, P);
        List<Vec3> pg = new ArrayList<>();
        curve.runForSegments((x1, y1, z1, x2, y2, z2, o, l) -> pg.add(origin.add(x2, y2, z2)), 0.5);
        List<Vec3> ours = BendGeometry.legVertices(A, P, H, V, T, 0.5);
        assertEquals(pg.size() + 1, ours.size());
        for (int i = 0; i < pg.size(); i++)
            near(pg.get(i), ours.get(i + 1), 1e-5);
    }

    @Test
    void aBentWireIsOneTubeThroughItsBendPoints() {
        List<WireSweep.Strip> strips = sweep(List.of(A, P, Q, B), 0);
        assertEquals(1, strips.size());
        List<WireSweep.Ring> rings = strips.get(0).rings();
        assertEquals(A, rings.get(0).at());
        assertEquals(B, rings.get(rings.size() - 1).at());
        for (Vec3 point : List.of(P, Q))
            assertEquals(1, rings.stream().filter(r -> r.at().equals(point)).count(), "one shared ring at " + point);
    }

    /**
     * Every edge of the tube runs straight along its piece from one ring to the
     * next: the two ends of a piece are the same square, slid along the piece, so
     * nothing twists, gapes or overlaps, and that holds through each bend point.
     */
    @Test
    void edgesRunAlongEachPieceWithNoTwistAtTheJoints() {
        for (List<Vec3> path : List.of(List.of(A, P, Q, B), List.of(A, new Vec3(3, 70, 0), new Vec3(3, 70, 3)),
                List.of(A, new Vec3(0, 74, 0), new Vec3(4, 74, 1)))) {
            for (WireSweep.Strip strip : sweep(path, 0)) {
                List<WireSweep.Ring> rings = strip.rings();
                for (int i = 1; i < rings.size(); i++) {
                    Vec3 dir = rings.get(i).at().subtract(rings.get(i - 1).at()).normalize();
                    Vec3[] a = corners(rings.get(i - 1)), b = corners(rings.get(i));
                    for (int k = 0; k < 4; k++) {
                        Vec3 edge = b[k].subtract(a[k]);
                        assertTrue(edge.cross(dir).length() < 1e-6 * Math.max(1, edge.length()),
                                "edge " + k + " of piece " + i + " leaves its piece");
                        assertTrue(edge.dot(dir) > 0, "edge " + k + " of piece " + i + " runs backwards");
                    }
                }
            }
        }
    }

    @Test
    void theJointIsMitredHalfwayBetweenTheLegs() {
        Vec3 corner = new Vec3(4, 70, 0);
        List<WireSweep.Ring> rings = sweep(List.of(A, corner, new Vec3(4, 70, 4)), 0).get(0).rings();
        int at = 0;
        while (!rings.get(at).at().equals(corner))
            at++;
        Vec3 in = corner.subtract(rings.get(at - 1).at()).normalize();
        Vec3 out = rings.get(at + 1).at().subtract(corner).normalize();
        Vec3 mitre = in.add(out).normalize();
        assertEquals(0, rings.get(at).cross1().dot(mitre), 1e-9);
        assertEquals(0, rings.get(at).cross2().dot(mitre), 1e-9);
        // Wider than the wire there, by the mitre's slant, never narrower
        assertTrue(rings.get(at).cross1().length() >= T * Math.sqrt(0.5) - 1e-12);
    }

    @Test
    void aStraightSectionIsTheWiresOwnSquare() {
        List<WireSweep.Ring> rings = sweep(List.of(A, new Vec3(0, 66, 0), new Vec3(5, 66, 0)), 0).get(0).rings();
        WireSweep.Ring first = rings.get(0);
        Vec3 dir = rings.get(1).at().subtract(first.at()).normalize();
        double radius = T * Math.sqrt(0.5);
        assertEquals(radius, first.cross1().length(), 1e-9);
        assertEquals(radius, first.cross2().length(), 1e-9);
        assertEquals(0, first.cross1().dot(first.cross2()), 1e-12);
        assertEquals(0, first.cross1().dot(dir), 1e-9);
        assertEquals(0, first.cross2().dot(dir), 1e-9);
    }

    @Test
    void itStartsOutTurnedAsPowerGridWouldTurnIt() {
        Vec3 end = new Vec3(8, 70, 0);
        //? if forge {
        Vec3 pg = new CurveParameters(A, end, H, V, T).cross1;
        //?} else {
        /*Vec3 pg = new CurveParameters(A, end, BendGeometry.curveLength(A, end, H, V), T).cross1;
        *///?}
        WireSweep.Ring first = WireSweep.sweep(List.of(A, new Vec3(4, 70, 0), end), 0, H, V, T, 0.5, pg).get(0)
                .first();
        // The level first piece leaves Power Grid's cross-section as it was
        assertTrue(first.cross1().normalize().dot(pg.normalize()) > 0.95);
    }

    @Test
    void offsetsRunOnAlongTheWholePath() {
        List<Vec3> path = List.of(A, P, Q, B);
        List<WireSweep.Ring> rings = sweep(path, 0).get(0).rings();
        double walked = 0;
        assertEquals(0, rings.get(0).offset(), 1e-12);
        for (int i = 1; i < rings.size(); i++) {
            walked += rings.get(i).at().distanceTo(rings.get(i - 1).at());
            assertEquals(walked, rings.get(i).offset(), 1e-9);
        }
        assertEquals(WireBends.length(WireSweep.polyline(path, 0, H, V, T, 0.5)), walked, 1e-9);
    }

    @Test
    void aFoldBackIsTwoTubesMeetingAtThePoint() {
        Vec3 tip = new Vec3(5, 70, 0);
        List<WireSweep.Strip> strips = sweep(List.of(A, tip, new Vec3(0.5, 70, 0.2)), 0);
        assertEquals(2, strips.size());
        assertEquals(tip, strips.get(0).last().at());
        assertEquals(tip, strips.get(1).first().at());
    }

    @Test
    void ductedLegsAreNotDrawn() {
        Vec3 inDuct = new Vec3(5, 70, 0), out = new Vec3(5, 70, 2);
        // Leg 0 hangs, leg 1 runs in a duct: only the hanging leg is a tube, ending where the duct takes over
        List<WireSweep.Strip> strips = sweep(List.of(A, inDuct, out), 0b10);
        assertEquals(1, strips.size());
        assertEquals(A, strips.get(0).first().at());
        assertEquals(inDuct, strips.get(0).last().at());
    }

    // Cords

    @Test
    void aBentCordsTrueEndsAreThePathsEnds() {
        List<Vec3> path = List.of(A, P, B);
        List<Vec3> line = WireSweep.polyline(path, 0, CORD, CORD, CORD_T, 0.5);
        // The plugs sit where the first leg starts and the last one ends
        assertEquals(A, line.get(0));
        assertEquals(B, line.get(line.size() - 1));
        List<WireSweep.Strip> strips = WireSweep.sweep(path, 0, CORD, CORD, CORD_T, 0.5, null);
        assertEquals(A, strips.get(0).first().at());
        assertEquals(B, strips.get(strips.size() - 1).last().at());
    }

    @Test
    void aSplitEndGivesUpTheFirstOrLastPieceOfTheWholeCord() {
        List<Vec3> path = List.of(A, P, B);
        List<Vec3> line = WireSweep.polyline(path, 0, CORD, CORD, CORD_T, 0.5);
        List<WireSweep.Strip> strips = WireSweep.sweep(path, 0, CORD, CORD, CORD_T, 0.5, null);
        List<WireSweep.Strip> trimmed = WireSweep.withoutLastPiece(WireSweep.withoutFirstPiece(strips));
        // The leads take over at the far end of the first piece and the near end of the last
        assertEquals(line.get(1), trimmed.get(0).first().at());
        assertEquals(line.get(line.size() - 2), trimmed.get(trimmed.size() - 1).last().at());
        assertEquals(strips.get(0).rings().size() - 2, trimmed.get(0).rings().size());
        // The leg in the middle is untouched
        assertSame(strips.get(0).rings().get(5), trimmed.get(0).rings().get(4));
    }

    @Test
    void stringLightsAreSpreadEvenlyOverEveryLeg() {
        List<Vec3> path = List.of(A, P, Q, B);
        List<Vec3> line = WireSweep.polyline(path, 0, CORD, CORD, CORD_T, 0.5);
        int count = 40;
        List<Vec3> spots = WireSweep.divide(line, count);
        assertEquals(count + 1, spots.size());
        assertEquals(A, spots.get(0));
        assertEquals(B, spots.get(count));
        double share = WireBends.length(line) / count;
        // Measured along the cord, each step is the same
        double walked = 0;
        int piece = 1;
        List<Double> along = new ArrayList<>();
        for (Vec3 spot : spots) {
            while (piece < line.size() - 1 && !onPiece(line.get(piece - 1), line.get(piece), spot)) {
                walked += line.get(piece - 1).distanceTo(line.get(piece));
                piece++;
            }
            along.add(walked + line.get(piece - 1).distanceTo(spot));
        }
        for (int k = 1; k <= count; k++)
            assertEquals(share * k, along.get(k), 1e-6);
        // Every leg gets lights, not only the straight line between the ends
        for (int leg = 0; leg < 3; leg++) {
            Vec3 mid = path.get(leg).lerp(path.get(leg + 1), 0.5);
            assertTrue(spots.stream().anyMatch(s -> s.distanceTo(mid) < 1.0), "lights along leg " + leg);
        }
    }

    private static boolean onPiece(Vec3 a, Vec3 b, Vec3 p) {
        return Math.abs(a.distanceTo(p) + p.distanceTo(b) - a.distanceTo(b)) < 1e-9;
    }

    @Test
    void stringLightBlocksFollowTheBentCordAtPowerGridsSpacing() {
        List<Vec3> path = List.of(A, P, B);
        List<Vec3> line = WireSweep.polyline(path, 0, CORD, CORD, CORD_T, 0.5);
        List<Vec3> blocks = WireSweep.spaced(line, 0.9f);
        assertEquals((int) (WireBends.length(line) / 0.9f) + 1, blocks.size());
        assertEquals(B, blocks.get(blocks.size() - 1));
        assertTrue(blocks.get(0).distanceTo(A) <= 0.9f + 1e-9);
        for (int i = 1; i < blocks.size(); i++)
            assertTrue(blocks.get(i).distanceTo(blocks.get(i - 1)) <= 0.9f + 1e-9);
        assertTrue(blocks.stream().anyMatch(b -> b.distanceTo(P) < 0.9), "a light near the bend point");
    }
}
