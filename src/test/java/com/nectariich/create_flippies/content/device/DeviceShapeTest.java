package com.nectariich.create_flippies.content.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** How a device's model is turned, traced and made solid. */
class DeviceShapeTest {

    /** A CRT-like box, front +x, and a keyboard-like slab, front -x. */
    private static final DeviceShape FRONT_X = new DeviceShape(new double[][] {{0.5, 0, 1, 12, 12, 15}},
            new double[][] {}, 1);
    private static final DeviceShape FRONT_MINUS_X = new DeviceShape(new double[][] {{0, 0, 0, 7, 1, 16}},
            new double[][] {}, -1);

    private static final double EPS = 1e-9;

    @Test
    void theFrontFacesTheYawOfTheStep() {
        // Yaw 0 is south (+z), 90 west (-x), 180 north (-z), 270 east (+x)
        Vec3[] facing = {new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 0, -1), new Vec3(1, 0, 0)};
        for (int quarter = 0; quarter < 4; quarter++) {
            int step = quarter * DeviceShape.STEPS / 4;
            Vec3 front = FRONT_X.directionToLocal(new Vec3(1, 0, 0), step);
            assertEquals(0, front.subtract(facing[quarter]).length(), EPS, "front +x, step " + step);
            Vec3 back = FRONT_MINUS_X.directionToLocal(new Vec3(-1, 0, 0), step);
            assertEquals(0, back.subtract(facing[quarter]).length(), EPS, "front -x, step " + step);
        }
    }

    @Test
    void modelAndLocalRoundTrip() {
        Vec3 p = new Vec3(3.25, 5.5, 12.75);
        for (int step = 0; step < DeviceShape.STEPS; step++) {
            Vec3 back = FRONT_X.toModel(FRONT_X.toLocal(p, step), step);
            assertEquals(0, back.subtract(p).length(), 1e-9, "step " + step);
        }
    }

    @Test
    void theCellCentreStaysPut() {
        for (int step = 0; step < DeviceShape.STEPS; step++)
            assertEquals(0, FRONT_X.toLocal(new Vec3(8, 0, 8), step).length(), EPS);
    }

    @Test
    void aQuarterTurnIsTheTurnedBoxExactly() {
        // Step 0 turns a +x front a quarter turn (to face south): the 11.5 x 14 footprint becomes 14 x 11.5
        AABB box = FRONT_X.localShape(0).bounds();
        assertEquals(14 / 16.0, box.getXsize(), EPS);
        assertEquals(11.5 / 16.0, box.getZsize(), EPS);
        assertEquals(12 / 16.0, box.getYsize(), EPS);
    }

    @Test
    void otherStepsAreWholePixelColumnsInsideTheBounds() {
        for (int step = 1; step < DeviceShape.STEPS; step++) {
            if (step % 8 == 0)
                continue;
            var shape = FRONT_MINUS_X.localShape(step);
            assertFalse(shape.isEmpty(), "step " + step);
            AABB bounds = FRONT_MINUS_X.localBounds(step);
            for (AABB box : shape.toAabbs()) {
                assertEquals(0, Math.abs(box.minX * 16 - Math.round(box.minX * 16)), EPS);
                assertEquals(0, Math.abs(box.minZ * 16 - Math.round(box.minZ * 16)), EPS);
                assertTrue(box.minX >= bounds.minX - 1 / 16.0 && box.maxX <= bounds.maxX + 1 / 16.0);
                assertTrue(box.minZ >= bounds.minZ - 1 / 16.0 && box.maxZ <= bounds.maxZ + 1 / 16.0);
            }
        }
    }

    @Test
    void aRayHitsTheTurnedBoxNotItsBounds() {
        int step = 4; // 45 degrees
        // Straight down through the middle of the slab
        Vec3 middle = FRONT_MINUS_X.toLocal(new Vec3(3.5, 0, 8), step);
        DeviceShape.Hit hit = FRONT_MINUS_X.clip(middle.add(0, 1, 0), middle.add(0, -1, 0), step);
        assertNotNull(hit);
        assertEquals(1, hit.modelPoint().y, 1e-6);
        // Down through a corner of the bounding box the turned slab does not reach
        AABB bounds = FRONT_MINUS_X.localBounds(step);
        Vec3 corner = new Vec3(bounds.minX + 0.01, 0, bounds.minZ + 0.01);
        assertNull(FRONT_MINUS_X.clip(corner.add(0, 1, 0), corner.add(0, -1, 0), step));
    }
}
