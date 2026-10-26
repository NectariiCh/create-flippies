package com.nectariich.create_flippies.client.design;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;

/**
 * The pose the workbench preview lays a screen design flat in for the display editor:
 * a proper rotation taking the viewport's right to world +X and the glass's front to
 * +Y, and the viewport's centre to the middle of the bench at
 * {@link DesignPreview#FLAT_HEIGHT}. Checked on the plate mapping the editor's camera
 * reads clicks with.
 */
class DesignPreviewLayFlatTest {

    private static final double EPS = 1e-4;

    /** A 16 px glass on the south face (+Z out), right along +X and down along -Y. */
    private static ScreenSurface southFacing(boolean flipX) {
        return new ScreenSurface("", null, null, flipX, false, new float[] { 0, 16, 16, 16, 0, 0, 0, -16, 0 }, null,
                null);
    }

    private static double determinant(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    @Test
    void theDefaultGlassFacesUpAlreadyAndIsCentredOnTheBench() {
        ScreenSurface surface = ScreenSurface.defaults();
        double[] m = DesignPreview.flatFrame(surface, DesignPreview.FLAT_HEIGHT);
        double[] identity = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
        for (int i = 0; i < 9; i++)
            assertEquals(identity[i], m[i], EPS);
        double w = surface.viewport().widthPixels(), h = surface.viewport().heightPixels();
        double[] centre = DesignPreview.viewportToPlate(surface, w / 2, h / 2);
        assertEquals(8, centre[0], EPS);
        assertEquals(8, centre[1], EPS);
        double[] corner = DesignPreview.viewportToPlate(surface, 0, 0);
        assertEquals(8 - w / 2, corner[0], EPS);
        assertEquals(8 - h / 2, corner[1], EPS);
    }

    @Test
    void anUprightGlassIsTurnedGlassUpWithRightAcrossAndDownDown() {
        ScreenSurface surface = southFacing(false);
        double[] m = DesignPreview.flatFrame(surface, DesignPreview.FLAT_HEIGHT);
        assertEquals(1, determinant(m), EPS);
        // The front, +Z, goes to +Y
        assertEquals(0, m[2], EPS);
        assertEquals(1, m[5], EPS);
        assertEquals(0, m[8], EPS);
        double w = surface.viewport().widthPixels(), h = surface.viewport().heightPixels();
        double[] centre = DesignPreview.viewportToPlate(surface, w / 2, h / 2);
        assertEquals(8, centre[0], EPS);
        assertEquals(8, centre[1], EPS);
        double[] right = DesignPreview.viewportToPlate(surface, w / 2 + 1, h / 2);
        double[] down = DesignPreview.viewportToPlate(surface, w / 2, h / 2 + 1);
        assertEquals(9, right[0], EPS);
        assertEquals(8, right[1], EPS);
        assertEquals(8, down[0], EPS);
        assertEquals(9, down[1], EPS);
        // The centre lands at the flat height
        double[] f = surface.viewportFrame();
        double[] c = new double[3];
        for (int i = 0; i < 3; i++)
            c[i] = (f[i] + f[3 + i] * w / 2 + f[6 + i] * h / 2) / 16;
        assertEquals(DesignPreview.FLAT_HEIGHT, m[3] * c[0] + m[4] * c[1] + m[5] * c[2] + m[10], EPS);
    }

    @Test
    void aMirroredGlassIsStillTurnedNotReflected() {
        ScreenSurface surface = southFacing(true);
        double[] m = DesignPreview.flatFrame(surface, DesignPreview.FLAT_HEIGHT);
        assertEquals(1, determinant(m), EPS);
        assertEquals(1, m[5], EPS);
        double w = surface.viewport().widthPixels(), h = surface.viewport().heightPixels();
        double[] right = DesignPreview.viewportToPlate(surface, w / 2 + 1, h / 2);
        assertEquals(9, right[0], EPS);
        // Mirrored content reads mirrored: down goes up the screen
        double[] down = DesignPreview.viewportToPlate(surface, w / 2, h / 2 + 1);
        assertEquals(7, down[1], EPS);
    }
}
