package com.nectariich.create_flippies.foundation.panel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nectariich.create_flippies.content.panel.PanelGeometry;
import com.nectariich.create_flippies.foundation.SurfaceMount;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** The eighth turns about x and z, held to what the turn about the vertical already promised. */
class PanelPlaneTurnTest {

    private static final double EPS = 1e-9;
    private static final double ROOT_HALF = Math.sqrt(2.0) / 2.0;

    @Test
    void everyFrameIsOrthonormal() {
        for (Direction face : Direction.values())
            for (int yaw = 0; yaw < PanelPlane.TURNS; yaw++) {
                PanelPlane plane = new PanelPlane(face, yaw, 0);
                String name = face + " yaw " + yaw;
                Vec3 u = plane.tangentU(), v = plane.tangentV(), n = plane.outward();
                assertEquals(1, u.length(), EPS, name);
                assertEquals(1, v.length(), EPS, name);
                assertEquals(1, n.length(), EPS, name);
                assertEquals(0, u.dot(v), EPS, name);
                assertEquals(0, u.dot(n), EPS, name);
                assertEquals(0, v.dot(n), EPS, name);
            }
    }

    @Test
    void bothSidesOfASurfaceNameItAlike() {
        for (Direction face : Direction.values())
            for (int yaw = 0; yaw < PanelPlane.TURNS; yaw++) {
                PanelPlane plane = new PanelPlane(face, yaw, 0);
                PanelPlane back = plane.flipped();
                String name = face + " yaw " + yaw;
                assertArrayEquals(plane.latticeNormal(), back.latticeNormal(), name);
                assertEquals(-plane.outwardSign(), back.outwardSign(), name);
                assertEquals(0, plane.tangentU().subtract(back.tangentU()).length(), EPS, name);
                assertEquals(0, plane.tangentV().subtract(back.tangentV()).length(), EPS, name);
                assertEquals(0, plane.outward().add(back.outward()).length(), EPS, name);
            }
    }

    @Test
    void aTurnLeavesItsOwnAxisAlone() {
        for (Direction face : Direction.values())
            for (int yaw = 1; yaw < PanelPlane.TURNS; yaw++) {
                PanelPlane plane = new PanelPlane(face, yaw, 0);
                Axis axis = plane.turnAxis();
                assertEquals(face.getAxis() == axis, plane.isSpun(), face + " yaw " + yaw);
                assertEquals(face.getAxis() != axis, plane.isDiagonal(), face + " yaw " + yaw);
                // The normal never gains a component along the axis it was turned about
                assertEquals(face.getAxis() == axis ? 1 : 0, Math.abs(component(plane.outward(), axis)), EPS);
            }
    }

    @Test
    void theVerticalTurnIsUnchanged() {
        PanelPlane northEast = new PanelPlane(Direction.NORTH, PanelPlane.TURN_Y, 0);
        assertEquals(0, northEast.outward().subtract(new Vec3(ROOT_HALF, 0, -ROOT_HALF)).length(), EPS);
        assertArrayEquals(new int[] { 1, 0, -1 }, northEast.latticeNormal());
        assertEquals(0, northEast.tangentV().subtract(new Vec3(0, 1, 0)).length(), EPS);
        assertEquals(0, northEast.tangentU().subtract(new Vec3(ROOT_HALF, 0, ROOT_HALF)).length(), EPS);
    }

    @Test
    void aTippedSlopeClimbsAlongV() {
        PanelPlane console = new PanelPlane(Direction.NORTH, PanelPlane.TURN_X, 0);
        assertTrue(console.tangentV().y > 0.5);
        assertEquals(0, console.tangentU().subtract(new Vec3(1, 0, 0)).length(), EPS);
        PanelPlane roof = new PanelPlane(Direction.EAST, PanelPlane.TURN_Z, 0);
        assertTrue(roof.tangentV().y > 0.5);
        assertEquals(0, roof.tangentU().subtract(new Vec3(0, 0, 1)).length(), EPS);
    }

    @Test
    void aControlIsTurnedTheWayItsPanelIs() {
        // A control is mounted plate-down on the opposite face with the same yaw, so the
        // authored downward axis has to land on the panel's inward normal
        for (Direction face : Direction.values())
            for (int yaw = 0; yaw < PanelPlane.TURNS; yaw++) {
                PanelPlane plane = new PanelPlane(face, yaw, 0);
                double[] from = SurfaceMount.point(8, 8, 8, face.getOpposite(), yaw, 0);
                double[] to = SurfaceMount.point(8, 7, 8, face.getOpposite(), yaw, 0);
                Vec3 plate = new Vec3(to[0] - from[0], to[1] - from[1], to[2] - from[2]);
                assertEquals(0, plate.add(plane.outward()).length(), EPS, face + " yaw " + yaw);
            }
    }

    @Test
    void aMountedPointComesBack() {
        for (Direction face : Direction.values())
            for (int yaw = 0; yaw < PanelPlane.TURNS; yaw++) {
                double[] there = SurfaceMount.point(3, 5, 11, face, yaw, 1);
                double[] back = SurfaceMount.localPoint(there[0], there[1], there[2], face, yaw, 1);
                assertArrayEquals(new double[] { 3, 5, 11 }, back, 1e-9, face + " yaw " + yaw);
            }
    }

    @Test
    void everyCellOfATippedPanelHoldsPlate() {
        int block = PanelPlane.UNITS_PER_BLOCK;
        for (Direction face : Direction.values())
            for (int yaw : new int[] { PanelPlane.TURN_X, PanelPlane.TURN_Z }) {
                PanelPlane plane = new PanelPlane(face, yaw, 0).withOffset(block / 2 + 3 * PanelPlane.UNITS_PER_PIXEL);
                PanelDefinition panel = PanelDefinition.between(plane, -block, -block / 2, 2 * block + 48,
                        2 * block);
                String name = face + " yaw " + yaw;
                List<BlockPos> cells = panel.cells();
                assertFalse(cells.isEmpty(), name);
                Set<BlockPos> walked = new HashSet<>(cells);
                for (BlockPos cell : cells) {
                    assertTrue(PanelGeometry.plateBoxes(panel, cell).length > 0, name + " boxes at " + cell);
                    assertTrue(PanelGeometry.plateTiles(panel, cell).length > 0, name + " tiles at " + cell);
                }
                // And every point well inside the plate is in a cell the walk found
                double scale = block;
                double depth = -PanelDefinition.THICKNESS / scale / 2;
                for (double u = panel.minU() / scale + 0.01; u < panel.maxU() / scale; u += 0.0625)
                    for (double v = panel.minV() / scale + 0.01; v < panel.maxV() / scale; v += 0.0625) {
                        BlockPos at = BlockPos.containing(plane.at(u, v, depth));
                        assertTrue(walked.contains(at), name + " misses " + at + " at u " + u + ", v " + v);
                    }
            }
    }

    private static double component(Vec3 vector, Axis axis) {
        return axis == Axis.X ? vector.x : axis == Axis.Y ? vector.y : vector.z;
    }
}
