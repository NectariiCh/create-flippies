package com.nectariich.create_flippies.client.schematic_arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.schematic_arrow.ArrowHead;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowStyle;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class ArrowExtrusionTest {

    private static final int BLOCK = PanelPlane.UNITS_PER_BLOCK;
    private static final int PIXEL = PanelPlane.UNITS_PER_PIXEL;

    /** A wall facing south whose front surface is the block boundary z = 1, plate behind it in z = 0. */
    private static final PanelPlane WALL = new PanelPlane(Direction.SOUTH, 0, BLOCK);

    private static final ArrowStyle STRIP = ArrowStyle.DEFAULT.withHeads(ArrowHead.NONE, ArrowHead.NONE)
            .withHeight(PIXEL);

    private static List<ArrowExtrusion.Face> faces(PanelArrow arrow, BlockPos cell) {
        List<ArrowExtrusion.Face> faces = new ArrayList<>();
        ArrowExtrusion.build(arrow, ArrowMesh.of(arrow, null), cell, 0, faces::add);
        return faces;
    }

    private static double area(List<double[]> polygon) {
        Vec3 sum = Vec3.ZERO;
        for (int i = 0; i < polygon.size(); i++) {
            double[] a = polygon.get(i);
            double[] b = polygon.get((i + 1) % polygon.size());
            sum = sum.add(new Vec3(a[0], a[1], a[2]).cross(new Vec3(b[0], b[1], b[2])));
        }
        return sum.length() / 2;
    }

    @Test
    void theTopFacesOutAtItsHeightAndTheWallsStandOnThePlate() {
        // A strip across the middle of the block, quarter to three quarters along u
        PanelArrow arrow = PanelArrow.drawn(1, WALL, 0, STRIP, new int[] { BLOCK / 4, BLOCK / 2, 3 * BLOCK / 4,
                BLOCK / 2 });
        BlockPos cell = BlockPos.ZERO;
        List<ArrowExtrusion.Face> faces = faces(arrow, cell);
        Vec3 outward = WALL.outward();
        int tops = 0;
        int walls = 0;
        double topArea = 0;
        for (ArrowExtrusion.Face face : faces) {
            assertTrue(ArrowExtrusion.facing(face.polygon(), face.normal()) > 0, "Wound away from its normal");
            if (face.normal().dot(outward) > 0.999) {
                tops++;
                topArea += area(face.polygon());
                for (double[] p : face.polygon())
                    assertEquals(1 + 1.0 / 16, p[2], 1e-9);
            } else {
                walls++;
                assertEquals(0, face.normal().dot(outward), 1e-9);
                double low = Double.MAX_VALUE;
                double high = -Double.MAX_VALUE;
                for (double[] p : face.polygon()) {
                    low = Math.min(low, p[2]);
                    high = Math.max(high, p[2]);
                }
                assertEquals(1, low, 1e-9);
                assertEquals(1 + 1.0 / 16, high, 1e-9);
            }
        }
        assertEquals(2, tops);
        assertEquals(4, walls);
        assertEquals(0.5 * 2.0 / 16, topArea, 1e-9);
    }

    @Test
    void cellsShareAnArrowOutWithoutGapsOrOverlap() {
        // Three blocks long, from the middle of one cell to the middle of the third
        PanelArrow arrow = PanelArrow.drawn(1, WALL, 0, STRIP, new int[] { BLOCK / 2, BLOCK / 2, 5 * BLOCK / 2,
                BLOCK / 2 });
        double total = 0;
        double sides = 0;
        for (BlockPos cell : arrow.cells())
            for (ArrowExtrusion.Face face : faces(arrow, cell)) {
                double a = area(face.polygon());
                if (face.normal().dot(WALL.outward()) > 0.999)
                    total += a;
                else
                    sides += a;
            }
        assertEquals(2 * 2.0 / 16, total, 1e-9);
        // Two long sides and two ends, a pixel high
        assertEquals((2 * 2 + 2 * 2.0 / 16) / 16, sides, 1e-9);
    }

    @Test
    void theBackOfAPlateRaisesTheOtherWay() {
        PanelArrow arrow = PanelArrow.drawn(1, WALL.flipped(), PanelDefinition.THICKNESS, STRIP,
                new int[] { BLOCK / 4, BLOCK / 2, 3 * BLOCK / 4, BLOCK / 2 });
        for (ArrowExtrusion.Face face : faces(arrow, BlockPos.ZERO)) {
            assertTrue(ArrowExtrusion.facing(face.polygon(), face.normal()) > 0);
            if (face.normal().dot(WALL.flipped().outward()) > 0.999)
                for (double[] p : face.polygon())
                    // The plate's back is two pixels behind its front, and the arrow a pixel behind that
                    assertEquals(1 - 3.0 / 16, p[2], 1e-9);
        }
    }

    @Test
    void aDiagonalPanelsWallsAreSquareToIt() {
        PanelPlane diagonal = new PanelPlane(Direction.SOUTH, 1, 0);
        PanelArrow arrow = PanelArrow.drawn(1, diagonal, 0, STRIP, new int[] { 0, BLOCK / 2, BLOCK, BLOCK / 2 });
        int faces = 0;
        for (BlockPos cell : arrow.cells())
            for (ArrowExtrusion.Face face : faces(arrow, cell)) {
                faces++;
                assertTrue(ArrowExtrusion.facing(face.polygon(), face.normal()) > 0);
                double along = face.normal().dot(diagonal.outward());
                assertTrue(Math.abs(along) < 1e-9 || along > 0.999);
            }
        assertTrue(faces > 0);
    }

    @Test
    void overlappingArrowsStack() {
        PanelArrow first = PanelArrow.drawn(1, WALL, 0, STRIP, new int[] { 0, 0, BLOCK, 0 });
        PanelArrow branch = PanelArrow.drawn(2, WALL, 0, STRIP, new int[] { BLOCK / 2, 0, BLOCK / 2, BLOCK });
        PanelArrow apart = PanelArrow.drawn(3, WALL, 0, STRIP, new int[] { 4 * BLOCK, 0, 5 * BLOCK, 0 });
        List<PanelArrow> arrows = List.of(first, branch, apart);
        assertEquals(0, ArrowExtrusion.stacked(arrows, 0));
        assertEquals(1, ArrowExtrusion.stacked(arrows, 1));
        assertEquals(0, ArrowExtrusion.stacked(arrows, 2));
    }
}
