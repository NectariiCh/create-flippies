package com.nectariich.create_flippies.client.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The pose a designed screen's elements are drawn in, shared by the placed screen and
 * the workbench preview: the viewport's top left lands on the glass's corner, lifted
 * out of its front, a unit is one viewport pixel along the glass, and the frame is the
 * same handedness an alphanumeric display's is (x right, y down, z out of the glass),
 * so lettering faces the same way on both.
 */
class DesignScreenRendererTest {

    private static final float EPS = 1e-5f;

    @Test
    void theViewportIsPosedOnTheGlassAndLiftedOutOfItsFront() {
        // The default surface: a 16 px square glass at y = 1, facing up, with its
        // right along +x and its down along +z as the workbench's top view shows it
        ScreenSurface surface = ScreenSurface.defaults();
        double[] f = surface.viewportFrame();
        PoseStack ms = new PoseStack();
        assertTrue(DesignScreenRenderer.toViewport(ms, surface));
        Matrix4f pose = ms.last().pose();

        Vector3f corner = pose.transformPosition(new Vector3f());
        assertEquals((float) f[0], corner.x, EPS);
        assertEquals((float) f[1] + DesignScreenRenderer.LIFT, corner.y, EPS);
        assertEquals((float) f[2], corner.z, EPS);

        Vector3f right = pose.transformDirection(new Vector3f(1, 0, 0));
        Vector3f down = pose.transformDirection(new Vector3f(0, 1, 0));
        Vector3f out = pose.transformDirection(new Vector3f(0, 0, 1));
        assertEquals(new Vector3f((float) f[3], (float) f[4], (float) f[5]).distance(right), 0, EPS);
        assertEquals(new Vector3f((float) f[6], (float) f[7], (float) f[8]).distance(down), 0, EPS);
        assertEquals(0, out.distance(0, 1, 0), EPS, "z points out of the glass's front");
        // x right, y down, z towards the viewer: the mirrored frame a sign's or an
        // alphanumeric display's lettering is drawn in
        assertTrue(pose.determinant3x3() < 0);
    }

    @Test
    void aSingleFlipMirrorsTheContentButKeepsItInFrontOfTheGlass() {
        for (boolean[] flips : new boolean[][] { { true, false }, { false, true }, { true, true } }) {
            ScreenSurface surface = new ScreenSurface("", null, null, flips[0], flips[1], null,
                    ScreenSurface.DEFAULT_SUPPLY, null);
            double[] f = surface.viewportFrame();
            PoseStack ms = new PoseStack();
            assertTrue(DesignScreenRenderer.toViewport(ms, surface));
            Matrix4f pose = ms.last().pose();

            Vector3f corner = pose.transformPosition(new Vector3f());
            assertEquals((float) f[1] + DesignScreenRenderer.LIFT, corner.y, EPS, "lifted out of the front");
            Vector3f out = pose.transformDirection(new Vector3f(0, 0, 1));
            assertEquals(0, out.distance(0, 1, 0), EPS, "z still out of the front");
            Vector3f right = pose.transformDirection(new Vector3f(1, 0, 0));
            assertEquals(new Vector3f((float) f[3], (float) f[4], (float) f[5]).distance(right), 0, EPS);
            // One flip mirrors the frame; two are a half turn and keep the plain one's handedness
            boolean mirrored = flips[0] != flips[1];
            assertEquals(mirrored, pose.determinant3x3() > 0);
        }
    }
}
