package com.nectariich.create_flippies.content.cabinet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.nectariich.create_flippies.content.device.DeviceShape;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** The cabinet's walls, door and room inside, and how it stands on a wall. */
class CabinetGeometryTest {

    private static final double EPS = 1e-9;

    private static CabinetSpec spec(int w, int h, int d, CabinetSpec.Hinge hinge) {
        return new CabinetSpec(w, h, d, 0xCBD0CC, 0, hinge).clamp();
    }

    private static double volume(double[] b) {
        return Math.max(0, b[3] - b[0]) * Math.max(0, b[4] - b[1]) * Math.max(0, b[5] - b[2]);
    }

    private static double overlap(double[] a, double[] b) {
        return volume(new double[] {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]),
                Math.min(a[3], b[3]), Math.min(a[4], b[4]), Math.min(a[5], b[5])});
    }

    private static void assertWhole(double v, String what) {
        assertEquals(Math.rint(v), v, EPS, what + " is not on a whole pixel: " + v);
    }

    @Test
    void everyEdgeIsOnAWholePixel() {
        for (int w = 1; w <= 48; w += 1)
            for (int d : new int[] {1, 2, 3, 7, 16})
                for (CabinetSpec.Hinge hinge : CabinetSpec.Hinge.values())
                    for (boolean open : new boolean[] {false, true}) {
                        CabinetSpec spec = spec(w, w % 7 + 1, d, hinge);
                        for (double[] b : CabinetGeometry.boxes(spec, open))
                            for (double v : b)
                                assertWhole(v, spec + (open ? " open" : " closed"));
                        double[] handle = CabinetGeometry.handle(spec, open);
                        if (handle != null)
                            for (double v : handle)
                                assertWhole(v, "handle of " + spec);
                    }
    }

    @Test
    void theOutlineIsTheSizeAskedForWithItsBackOnTheWall() {
        CabinetSpec spec = spec(25, 40, 9, CabinetSpec.Hinge.LEFT);
        double[] o = CabinetGeometry.outline(spec);
        assertEquals(CabinetGeometry.BACK, o[0], EPS);
        assertEquals(9, o[3] - o[0], EPS);
        assertEquals(40, o[4] - o[1], EPS);
        assertEquals(25, o[5] - o[2], EPS);
        // About the position up and down and across (the model's z = 8), an odd size half a pixel off high
        assertEquals(-20, o[1], EPS);
        assertEquals(8 - 12, o[2], EPS);
        CabinetSpec odd = spec(25, 41, 9, CabinetSpec.Hinge.LEFT);
        assertEquals(-20, CabinetGeometry.bottom(odd), EPS);
        assertEquals(21, CabinetGeometry.top(odd), EPS);
    }

    @Test
    void closedItIsWallsRoundAnEmptyRoom() {
        for (CabinetSpec spec : new CabinetSpec[] {spec(16, 16, 8, CabinetSpec.Hinge.LEFT),
                spec(48, 48, 16, CabinetSpec.Hinge.RIGHT), spec(3, 3, 3, CabinetSpec.Hinge.LEFT),
                spec(17, 5, 4, CabinetSpec.Hinge.RIGHT), spec(3, 3, 2, CabinetSpec.Hinge.LEFT),
                spec(30, 20, 2, CabinetSpec.Hinge.RIGHT)}) {
            List<double[]> boxes = CabinetGeometry.boxes(spec, false);
            double[] outline = CabinetGeometry.outline(spec);
            double[] room = {CabinetGeometry.BACK + 1, outline[1] + 1, outline[2] + 1, outline[3] - 1,
                    outline[4] - 1, outline[5] - 1};
            double total = 0;
            for (int i = 0; i < boxes.size(); i++) {
                double[] b = boxes.get(i);
                assertEquals(volume(b), overlap(b, outline), EPS, "inside the outline: " + spec);
                assertEquals(0, overlap(b, room), EPS, "clear of the room: " + spec);
                for (int j = i + 1; j < boxes.size(); j++)
                    assertEquals(0, overlap(b, boxes.get(j)), EPS, "walls overlap: " + spec);
                total += volume(b);
            }
            // Nothing missing: the walls and the room fill the outline exactly
            assertEquals(volume(outline), total + volume(room), EPS, "walls and room: " + spec);
        }
    }

    @Test
    void openTheDoorStandsOutInLineWithTheHingeSide() {
        CabinetSpec left = spec(20, 30, 6, CabinetSpec.Hinge.LEFT);
        double[] o = CabinetGeometry.outline(left);
        double[] door = CabinetGeometry.door(left, true);
        assertEquals(o[3], door[0], EPS);
        assertEquals(o[3] + 18, door[3], EPS);
        assertEquals(o[5] - 1, door[2], EPS);
        assertEquals(o[5], door[5], EPS);
        assertEquals(o[1] + 1, door[1], EPS);
        assertEquals(o[4] - 1, door[4], EPS);

        CabinetSpec right = left.withHinge(CabinetSpec.Hinge.RIGHT);
        double[] other = CabinetGeometry.door(right, true);
        assertEquals(o[2], other[2], EPS);
        assertEquals(o[2] + 1, other[5], EPS);
        assertEquals(o[3] + 18, other[3], EPS);

        // Open, the front is open: nothing at all is in the room or its doorway
        double[] doorway = {CabinetGeometry.BACK + 1, o[1] + 1, o[2] + 1, o[3], o[4] - 1, o[5] - 1};
        for (double[] b : CabinetGeometry.boxes(left, true))
            assertEquals(0, overlap(b, doorway), EPS);
    }

    @Test
    void theHandleIsOnTheFreeEdgeOutsideTheDoor() {
        CabinetSpec left = spec(16, 16, 8, CabinetSpec.Hinge.LEFT);
        double[] o = CabinetGeometry.outline(left);
        double[] closed = CabinetGeometry.handle(left, false);
        assertNotNull(closed);
        assertEquals(o[3], closed[0], EPS);
        assertTrue(closed[5] < (o[2] + o[5]) / 2, "a left-hung door's handle is on the right (low z)");
        double[] open = CabinetGeometry.handle(left, true);
        assertEquals(o[5], open[2], EPS, "open, it stands off the door's outer face, away from the room");
        double[] rightClosed = CabinetGeometry.handle(left.withHinge(CabinetSpec.Hinge.RIGHT), false);
        assertTrue(rightClosed[2] > (o[2] + o[5]) / 2);
    }

    @Test
    void theSmallestHasADoorOnItsBackWithNoRoomBetween() {
        // Asked for smaller, it is clamped to the smallest: 3 by 3 by 2
        CabinetSpec spec = spec(1, 1, 1, CabinetSpec.Hinge.RIGHT);
        assertEquals(new CabinetSpec(3, 3, 2, 0xCBD0CC, 0, CabinetSpec.Hinge.RIGHT), spec);
        assertEquals(0, CabinetGeometry.roomDepth(spec), EPS);
        assertEquals(6, CabinetGeometry.boxes(spec, false).size());
        double[] door = CabinetGeometry.door(spec, false);
        assertNotNull(door);
        CabinetGeometry.BackPlane back = CabinetGeometry.backPlane(spec);
        assertEquals(back.x(), door[0], EPS, "the door's inner face is on the back plate's");
        assertEquals(1, back.width(), EPS);
        assertEquals(1, back.height(), EPS);
        assertNull(CabinetGeometry.handle(spec, false), "no room on a 1 px door for a handle");
        double front = CabinetGeometry.front(spec);
        assertEquals(CabinetGeometry.Part.DOOR, CabinetGeometry.partAt(spec, false, new Vec3(front, 0.5, 8)));
        // It still opens, and then the back plate shows
        assertEquals(CabinetGeometry.Part.BACK_INNER, CabinetGeometry.partAt(spec, true, new Vec3(back.x(), 0.5, 8)));
        assertEquals(front + 1, CabinetGeometry.door(spec, true)[3], EPS);
    }

    @Test
    void theBackPlaneIsTheInsideOfTheBack() {
        CabinetSpec spec = spec(32, 24, 10, CabinetSpec.Hinge.LEFT);
        CabinetGeometry.BackPlane back = CabinetGeometry.backPlane(spec);
        assertEquals(CabinetGeometry.BACK + 1, back.x(), EPS);
        assertEquals(30, back.width(), EPS);
        assertEquals(22, back.height(), EPS);
        assertEquals(CabinetGeometry.z0(spec) + 1, back.z0(), EPS);
        assertEquals(CabinetGeometry.bottom(spec) + 1, back.y0(), EPS);
    }

    @Test
    void partsAreTellApart() {
        CabinetSpec spec = spec(16, 16, 8, CabinetSpec.Hinge.LEFT);
        double front = CabinetGeometry.front(spec);
        assertEquals(CabinetGeometry.Part.DOOR, CabinetGeometry.partAt(spec, false, new Vec3(front, 0, 8)));
        assertEquals(CabinetGeometry.Part.DOOR, CabinetGeometry.partAt(spec, false, new Vec3(front - 1, 0, 8)));
        assertEquals(CabinetGeometry.Part.WALL, CabinetGeometry.partAt(spec, true, new Vec3(front, 0, 8)));
        assertEquals(CabinetGeometry.Part.BACK_INNER, CabinetGeometry.partAt(spec, true, new Vec3(9, 0, 8)));
        assertEquals(CabinetGeometry.Part.WALL,
                CabinetGeometry.partAt(spec, false, new Vec3(12, 0, CabinetGeometry.z1(spec))));
        // The top of the box is a wall, not the door's top edge
        assertEquals(CabinetGeometry.Part.WALL,
                CabinetGeometry.partAt(spec, false, new Vec3(front - 0.5, CabinetGeometry.top(spec), 8)));
        double[] open = CabinetGeometry.door(spec, true);
        assertEquals(CabinetGeometry.Part.DOOR,
                CabinetGeometry.partAt(spec, true, new Vec3((open[0] + open[3]) / 2, 0, open[5])));
    }

    @Test
    void onAWallItStandsOutOfItOnThePixelGrid() {
        // Steps 0, 8, 16, 24 face south, west, north, east
        Vec3[] out = {new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 0, -1), new Vec3(1, 0, 0)};
        CabinetSpec spec = spec(23, 17, 5, CabinetSpec.Hinge.RIGHT);
        for (boolean open : new boolean[] {false, true}) {
            DeviceShape shape = CabinetGeometry.shape(spec, open);
            for (int quarter = 0; quarter < 4; quarter++) {
                int step = quarter * DeviceShape.STEPS / 4;
                for (AABB box : shape.localShape(step).toAabbs())
                    for (double v : new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ})
                        assertWhole(v * 16, "step " + step);
                // The back is on the wall, the position's plane, and the box reaches its depth out of it
                AABB bounds = shape.localShape(step).bounds();
                Vec3 o = out[quarter];
                double near = o.x + o.z > 0 ? (o.x != 0 ? bounds.minX : bounds.minZ)
                        : -(o.x != 0 ? bounds.maxX : bounds.maxZ);
                assertEquals(0, near, EPS, "back on the wall, step " + step);
                assertEquals(-8 / 16.0, bounds.minY, EPS);
                assertEquals(9 / 16.0, bounds.maxY, EPS);
                if (!open) {
                    double reach = o.x != 0 ? bounds.getXsize() : bounds.getZsize();
                    assertEquals(5 / 16.0, reach, EPS, "depth, step " + step);
                }
            }
        }
    }

    @Test
    void shapesAreShared() {
        CabinetSpec spec = spec(16, 16, 8, CabinetSpec.Hinge.LEFT);
        assertSame(CabinetGeometry.shape(spec, false), CabinetGeometry.shape(spec.withColour(0x112233), false));
        assertFalse(CabinetGeometry.shape(spec, false) == CabinetGeometry.shape(spec, true));
        // Even the smallest has a door to open
        CabinetSpec least = spec(3, 3, 2, CabinetSpec.Hinge.LEFT);
        assertFalse(CabinetGeometry.shape(least, false) == CabinetGeometry.shape(least, true));
    }
}
