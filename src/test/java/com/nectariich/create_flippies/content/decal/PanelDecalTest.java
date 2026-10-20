package com.nectariich.create_flippies.content.decal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class PanelDecalTest {

    private static final String ART = "0123456789abcdef0123456789abcdef";
    private static final int BLOCK = PanelPlane.UNITS_PER_BLOCK;

    private static PanelDecal on(PanelPlane plane, int depth, int quarters) {
        return new PanelDecal(1, ART, plane, depth, 0, 0, 2 * BLOCK, BLOCK, quarters, PanelDecal.OWN_COLOURS);
    }

    /** Where a point of the picture lands, in the world, on the surface. */
    private static Vec3 at(PanelDecal decal, double x, double y) {
        double[] uv = new double[2];
        decal.map(x, y, uv);
        return decal.plane().at(uv[0], uv[1], 0);
    }

    @Test
    void aWidePictureInATallRectangleKeepsItsShape() {
        assertArrayEquals(new int[] { 0, 75, 200, 125 }, PanelDecal.fit(0, 0, 200, 200, 4, 1, 0));
        // A quarter turn makes it tall
        assertArrayEquals(new int[] { 75, 0, 125, 200 }, PanelDecal.fit(200, 200, 0, 0, 4, 1, 1));
    }

    @Test
    void thePictureReadsTheRightWayRoundFromTheFront() {
        // A wall facing south is read by someone looking north, whose right is east
        PanelDecal decal = on(new PanelPlane(Direction.SOUTH, 0, 0), 0, 0);
        Vec3 topLeft = at(decal, 0, 0);
        Vec3 topRight = at(decal, 1, 0);
        Vec3 bottomLeft = at(decal, 0, 1);
        assertEquals(1, Math.signum(topRight.x - topLeft.x));
        assertEquals(-1, Math.signum(bottomLeft.y - topLeft.y));
    }

    @Test
    void andFromTheBack() {
        // The same plane seen from the north: the viewer's right is now west
        PanelDecal decal = on(new PanelPlane(Direction.SOUTH, 0, 0).flipped(), PanelDefinition.THICKNESS, 0);
        assertEquals(-1, Math.signum(at(decal, 1, 0).x - at(decal, 0, 0).x));
    }

    @Test
    void aQuarterTurnIsClockwiseAsSeen() {
        PanelDecal decal = on(new PanelPlane(Direction.SOUTH, 0, 0), 0, 1);
        // The picture's top left corner goes to the top right of the rectangle
        Vec3 corner = at(decal, 0, 0);
        assertEquals(2, corner.x, 1e-9);
        assertEquals(1, corner.y, 1e-9);
    }

    @Test
    void itSurvivesSaving() {
        PanelDecal decal = new PanelDecal(42, ART, new PanelPlane(Direction.EAST, 0, 37), PanelDefinition.THICKNESS,
                -10, 3, 400, 90, 3, 0xFF336699);
        assertEquals(decal, PanelDecal.load(decal.save()));
    }
}
