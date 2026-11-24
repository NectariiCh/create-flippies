package com.nectariich.create_flippies.content.cabinet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.content.device.DeviceShape;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** Cabinet rails in the world: the frame against the cabinet's own transform, and the cells a rail runs through. */
class CabinetRailFrameTest {

    private static final double EPS = 1e-9;
    private static final UUID CABINET = new UUID(1, 2);
    private static final Direction[] WALLS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static final CabinetSpec BIG = new CabinetSpec(48, 48, 12, 0xCBD0CC, 0, CabinetSpec.Hinge.LEFT).clamp();

    /** Positions on a wall: on a block boundary, and a few pixels off it in every way. */
    private static Vec3 position(Direction out, int dx, int dy, int dz) {
        // On the face of the block at the origin that looks out
        Vec3 face = new Vec3(0.5 + out.getStepX() * 0.5, 0, 0.5 + out.getStepZ() * 0.5);
        return face.add(dx / 16.0, dy / 16.0, dz / 16.0);
    }

    @Test
    void frameIsTheCabinetsOwnTransform() {
        for (Direction out : WALLS) {
            DeviceShape shape = CabinetGeometry.shape(BIG, false);
            int step = DeviceShape.stepFor(out.toYRot());
            Vec3 position = position(out, 3, -5, 7);
            CabinetRailFrame frame = CabinetRailFrame.of(position, out);
            for (double[] m : new double[][] {{8, 0, 8}, {9, 3, -2}, {9, -11, 17}, {20, 5, 1}}) {
                Vec3 world = position.add(shape.toLocal(new Vec3(m[0], m[1], m[2]), step)).scale(16);
                double[] px = frame.worldPx(m[0], m[1], m[2]);
                assertEquals(world.x, px[0], 1e-6, out + " x of " + m[0] + "," + m[1] + "," + m[2]);
                assertEquals(world.y, px[1], 1e-6, out + " y");
                assertEquals(world.z, px[2], 1e-6, out + " z");
            }
            // The front looks out of the wall
            Vec3 front = shape.directionToLocal(new Vec3(1, 0, 0), step);
            assertEquals(out.getStepX(), front.x, 1e-9);
            assertEquals(out.getStepZ(), front.z, 1e-9);
        }
    }

    @Test
    void piecesFallABlockAtATime() {
        int b = 16 * ControlMount.UNITS_PER_PIXEL;
        assertPieces(CabinetRailFrame.pieces(0, b), new int[] {0, 0, b});
        assertPieces(CabinetRailFrame.pieces(100, 300), new int[] {0, 100, b}, new int[] {1, 0, 300 - b});
        assertPieces(CabinetRailFrame.pieces(-50, 10), new int[] {-1, b - 50, b}, new int[] {0, 0, 10});
        // Ending on a boundary leaves nothing in the next cell
        assertPieces(CabinetRailFrame.pieces(0, 2 * b), new int[] {0, 0, b}, new int[] {1, 0, b});
        assertPieces(CabinetRailFrame.pieces(5, 5));
    }

    private static void assertPieces(List<int[]> pieces, int[]... expected) {
        assertEquals(expected.length, pieces.size());
        for (int i = 0; i < expected.length; i++)
            assertArrayEquals(expected[i], pieces.get(i));
    }

    @Test
    void cellsCarryExactlyTheRail() {
        int checked = 0;
        for (Direction out : WALLS)
            for (int[] off : new int[][] {{0, 0, 0}, {3, -5, 7}, {-6, 8, -1}, {11, 13, -9}})
                for (boolean vertical : new boolean[] {false, true})
                    for (int[] r : new int[][] {{-23, 0, 46}, {-7, -9, 5}, {0, 4, 16}, {-16, 8, 32}, {3, -12, 1}}) {
                        CabinetRail rail = new CabinetRail(4, vertical, r[0], r[1], r[2]);
                        Vec3 position = position(out, off[0], off[1], off[2]);
                        CabinetRailFrame frame = CabinetRailFrame.of(position, out);
                        check(frame, rail, out + " " + rail + " at " + position);
                        checked++;
                    }
        assertTrue(checked > 100);
    }

    private static void check(CabinetRailFrame frame, CabinetRail rail, String what) {
        List<CabinetRailFrame.Cell> cells = frame.cells(CABINET, rail, BIG);
        double plateX = CabinetGeometry.backPlane(BIG).x();
        double[] start = frame.worldPx(plateX, rail.pointAlong(rail.start())[0], rail.pointAlong(rail.start())[1]);
        double[] end = frame.worldPx(plateX, rail.pointAlong(rail.end())[0], rail.pointAlong(rail.end())[1]);
        double total = 0;
        Set<BlockPos> seen = new HashSet<>();
        for (CabinetRailFrame.Cell cell : cells) {
            RailSpan span = cell.span();
            assertTrue(seen.add(cell.pos()), what + ": a cell twice");
            assertTrue(span.isOf(CABINET, rail.id()));
            assertEquals(frame.facing(), span.facing());
            assertTrue(span.fromPx() >= 0 && span.toPx() <= 16 && span.toPx() > span.fromPx(), what + ": " + span);
            assertTrue(span.acrossPx() >= 0 && span.acrossPx() < 16, what + ": across " + span.acrossPx());
            assertTrue(span.platePx() >= 0 && span.platePx() < 16, what + ": plate " + span.platePx());
            assertEquals(rail.length(), span.railToPx() - span.railFromPx(), EPS);
            total += span.toPx() - span.fromPx();
            // Both ends of the piece are on the rail's centre line, on the plate, in the world
            for (double along : new double[] {span.fromPx(), span.toPx()}) {
                double[] p = SurfaceMount.point(along, span.platePx(), span.acrossPx(), span.facing(), span.rotation());
                double[] w = {p[0] + cell.pos().getX() * 16, p[1] + cell.pos().getY() * 16,
                        p[2] + cell.pos().getZ() * 16};
                assertOnSegment(start, end, w, what);
            }
            // The cell is the one just in front of the plate, along the way it looks out
            double[] nudged = {start[0] + 0.5 * frame.out().getStepX(), 0, start[2] + 0.5 * frame.out().getStepZ()};
            int axis = frame.out().getAxis() == Direction.Axis.X ? 0 : 2;
            int cellAt = axis == 0 ? cell.pos().getX() : cell.pos().getZ();
            assertEquals(cellAt, Math.floorDiv((int) Math.floor(nudged[axis]), 16), what + ": cell " + cell.pos()
                    + " not in front of the plate");
        }
        assertEquals(rail.length(), total, EPS, what + ": pieces do not add up to the rail");
    }

    private static void assertOnSegment(double[] a, double[] b, double[] p, String what) {
        double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double length2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
        double t = ((p[0] - a[0]) * ab[0] + (p[1] - a[1]) * ab[1] + (p[2] - a[2]) * ab[2]) / length2;
        assertTrue(t >= -EPS && t <= 1 + EPS, what + ": off the end of the rail, t " + t);
        for (int i = 0; i < 3; i++)
            assertEquals(a[i] + t * ab[i], p[i], 1e-6, what + ": off the rail's line");
    }

    /**
     * Gear clicked anywhere along a rail, from any of its cells, is seated whole on
     * the rail, on the plate, in a cell of that rail that has its middle on its face,
     * and at the same place in the world whichever cell the click came through.
     */
    @Test
    void gearSeatsAlongTheWholeRail() {
        int seated = 0;
        for (Direction out : WALLS)
            for (int[] off : new int[][] {{0, 0, 0}, {3, -5, 7}, {-6, 8, -1}})
                for (boolean vertical : new boolean[] {false, true}) {
                    CabinetRail rail = new CabinetRail(2, vertical, -20, vertical ? 5 : -3, 41);
                    CabinetRailFrame frame = CabinetRailFrame.of(position(out, off[0], off[1], off[2]), out);
                    List<CabinetRailFrame.Cell> cells = frame.cells(CABINET, rail, BIG);
                    java.util.Map<BlockPos, RailSpan> byPos = new java.util.HashMap<>();
                    for (CabinetRailFrame.Cell cell : cells)
                        byPos.put(cell.pos(), cell.span());
                    double plateX = CabinetGeometry.backPlane(BIG).x();
                    double[] a = frame.worldPx(plateX, rail.pointAlong(rail.start())[0], rail.pointAlong(rail.start())[1]);
                    double[] b = frame.worldPx(plateX, rail.pointAlong(rail.end())[0], rail.pointAlong(rail.end())[1]);
                    for (double width : new double[] {0.5, 1, 3}) {
                        for (CabinetRailFrame.Cell from : cells) {
                            RailSpan span = from.span();
                            for (double along = span.fromPx(); along <= span.toPx(); along += 0.25) {
                                RailSpan.CellSeat seat = span.seat(along, width);
                                String what = out + " " + rail + " width " + width + " along " + along;
                                BlockPos host = from.pos().offset(seat.dx(), seat.dy(), seat.dz());
                                RailSpan there = byPos.get(host);
                                assertTrue(there != null, what + ": seated in " + host + ", not a cell of the rail");
                                assertTrue(seat.mount().isOnFace(), what + ": off its host's face");
                                // Its middle, in the world, is on the rail and far enough in for its width
                                double[] m = seat.mount().point(8, 0, 8);
                                double[] w = {m[0] + host.getX() * 16, m[1] + host.getY() * 16, m[2] + host.getZ() * 16};
                                double t = along(a, b, w, what);
                                double length = rail.length();
                                assertTrue(t * length >= width / 2 - EPS && t * length <= length - width / 2 + EPS,
                                        what + ": hangs off the rail at " + t * length);
                                // The host's span finds it on the same rail
                                double[] p = there.authored(m[0], m[1], m[2]);
                                assertEquals(there.acrossPx(), p[2], 1e-6, what + ": off the centre line");
                                assertEquals(there.platePx(), p[1], 1e-6, what + ": off the plate");
                                seated++;
                            }
                        }
                    }
                }
        assertTrue(seated > 1000, "seated only " + seated);
    }

    /** How far along a to b the point p is, as a fraction, asserting it is on that line. */
    private static double along(double[] a, double[] b, double[] p, String what) {
        double[] ab = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double length2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
        double t = ((p[0] - a[0]) * ab[0] + (p[1] - a[1]) * ab[1] + (p[2] - a[2]) * ab[2]) / length2;
        for (int i = 0; i < 3; i++)
            assertEquals(a[i] + t * ab[i], p[i], 1e-6, what + ": off the rail's line");
        return t;
    }

    /** Gear centred on a rail on a block boundary across is seated in the cell that carries that rail. */
    @Test
    void railOnABoundaryBelongsToOneCell() {
        for (Direction out : WALLS) {
            CabinetRailFrame frame = CabinetRailFrame.of(position(out, 0, 0, 0), out);
            // Model y 0 is the block boundary at the position's height
            List<CabinetRailFrame.Cell> cells = frame.cells(CABINET, new CabinetRail(0, false, -8, 0, 16), BIG);
            for (CabinetRailFrame.Cell cell : cells)
                assertTrue(cell.span().acrossPx() == 0, out + ": across " + cell.span().acrossPx());
        }
    }
}
