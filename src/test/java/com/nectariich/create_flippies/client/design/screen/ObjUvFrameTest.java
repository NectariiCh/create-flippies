package com.nectariich.create_flippies.client.design.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The plane a screen is fitted to: that it maps every corner's UV back to that
 * corner, whichever way the UVs run and however the face is tilted, that a material
 * on every face of a box fits only the front, that the import settings beside the
 * model scale it and flip its V as Hypergraphical does, and that a curved surface is
 * told apart from a flat one.
 */
class ObjUvFrameTest {

    private static final double EPS = 1e-5;

    /** A little OBJ writer: quads of one part and material, each corner {x, y, z, u, v} as written in the file. */
    private static final class Obj {
        final StringBuilder positions = new StringBuilder(), uvs = new StringBuilder(), faces = new StringBuilder();
        int count;
        String material = "screen";

        Obj material(String name) {
            faces.append("usemtl ").append(name).append('\n');
            material = name;
            return this;
        }

        Obj quad(double[]... corners) {
            StringBuilder face = new StringBuilder("f");
            for (double[] c : corners) {
                positions.append("v ").append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append('\n');
                uvs.append("vt ").append(c[3]).append(' ').append(c[4]).append('\n');
                count++;
                face.append(' ').append(count).append('/').append(count);
            }
            faces.append(face).append('\n');
            return this;
        }

        byte[] bytes() {
            return ("# test\no glass\n" + positions + uvs + faces).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static double[] c(double x, double y, double z, double u, double v) {
        return new double[] { x, y, z, u, v };
    }

    /** Where the fit puts a UV as stored (V already flipped when the settings flip it). */
    private static double[] at(ObjUvFrame.Fit fit, double u, double v) {
        double[] f = fit.frame();
        return new double[] { f[0] + u * f[3] + v * f[6], f[1] + u * f[4] + v * f[7], f[2] + u * f[5] + v * f[8] };
    }

    private static void assertMaps(ObjUvFrame.Fit fit, double scale, boolean flipV, double[]... corners) {
        for (double[] corner : corners) {
            double v = flipV ? 1 - corner[4] : corner[4];
            assertArrayEquals(new double[] { corner[0] * scale, corner[1] * scale, corner[2] * scale },
                    at(fit, corner[3], v), EPS);
        }
    }

    @Test
    void aQuadWhoseUvsRunTurnedAQuarterStillMapsEveryCorner() throws Exception {
        // u runs along z and v along x: the texture is turned on the face
        double[][] quad = { c(0, 1, 0, 0, 1), c(0, 1, 2, 1, 1), c(4, 1, 2, 1, 0), c(4, 1, 0, 0, 0) };
        ObjUvFrame.Fit fit = ObjUvFrame.of(new Obj().material("screen").quad(quad).bytes(), null, "glass", "screen");
        assertNotNull(fit);
        assertMaps(fit, 1, true, quad);
        assertArrayEquals(new float[] { 0, 0, 1, 1 }, fit.uv(), 1e-6f);
        assertEquals(0, fit.residual(), EPS);
        // dP/du is the 2-long z side, dP/dv the 4-long x side
        assertArrayEquals(new double[] { 0, 0, 2 }, new double[] { fit.frame()[3], fit.frame()[4], fit.frame()[5] },
                EPS);
        assertArrayEquals(new double[] { 4, 0, 0 }, new double[] { fit.frame()[6], fit.frame()[7], fit.frame()[8] },
                EPS);
    }

    @Test
    void aTiltedQuadOverPartOfItsTextureIsFittedWhereItIs() throws Exception {
        double cos = Math.cos(Math.toRadians(30)), sin = Math.sin(Math.toRadians(30));
        // Leaning back 30 degrees, and filling only u 0.25..0.75, v 0.5..1 of its texture
        double[][] quad = { c(1, 2, 3, 0.25, 1), c(5, 2, 3, 0.75, 1), c(5, 2 + 2 * sin, 3 - 2 * cos, 0.75, 0.5),
                c(1, 2 + 2 * sin, 3 - 2 * cos, 0.25, 0.5) };
        ObjUvFrame.Fit fit = ObjUvFrame.of(new Obj().material("screen").quad(quad).bytes(), null, "glass", "screen");
        assertMaps(fit, 1, true, quad);
        assertArrayEquals(new float[] { 0.25f, 0, 0.75f, 0.5f }, fit.uv(), 1e-6f);
        assertEquals(0, fit.residual(), EPS);
    }

    @Test
    void aMaterialOnEveryFaceOfABoxFitsOnlyTheFront() throws Exception {
        // A slab 4 wide, 1 tall and 4 deep, the screen material on all six faces. The
        // top uses the texture's right half, every other face its left half
        Obj obj = new Obj().material("screen");
        double[][] top = { c(0, 1, 4, 0.5, 0), c(4, 1, 4, 1, 0), c(4, 1, 0, 1, 1), c(0, 1, 0, 0.5, 1) };
        obj.quad(top);
        obj.quad(c(0, 0, 0, 0, 0), c(4, 0, 0, 0.5, 0), c(4, 0, 4, 0.5, 1), c(0, 0, 4, 0, 1));
        obj.quad(c(0, 0, 4, 0, 0), c(4, 0, 4, 0.5, 0), c(4, 1, 4, 0.5, 0.25), c(0, 1, 4, 0, 0.25));
        obj.quad(c(4, 0, 0, 0, 0), c(0, 0, 0, 0.5, 0), c(0, 1, 0, 0.5, 0.25), c(4, 1, 0, 0, 0.25));
        obj.quad(c(0, 0, 0, 0, 0), c(0, 0, 4, 0.5, 0), c(0, 1, 4, 0.5, 0.25), c(0, 1, 0, 0, 0.25));
        obj.quad(c(4, 0, 4, 0, 0), c(4, 0, 0, 0.5, 0), c(4, 1, 0, 0.5, 0.25), c(4, 1, 4, 0, 0.25));
        // Another material on the same part is not the screen's at all
        obj.material("bezel").quad(c(0, 5, 0, 0, 0), c(9, 5, 0, 1, 0), c(9, 5, 9, 1, 1), c(0, 5, 9, 0, 1));
        ObjUvFrame.Fit fit = ObjUvFrame.of(obj.bytes(), null, "glass", "screen");
        assertNotNull(fit);
        // Top and bottom are as large; the one facing up out of the panel wins
        assertMaps(fit, 1, true, top);
        assertArrayEquals(new float[] { 0.5f, 0, 1, 1 }, fit.uv(), 1e-6f);
        assertEquals(0, fit.residual(), EPS);
    }

    @Test
    void theImportSettingsScaleItAndCanLeaveVUnflipped() throws Exception {
        double[][] quad = { c(0, 1, 0, 0, 0), c(4, 1, 0, 1, 0), c(4, 1, 2, 1, 1), c(0, 1, 2, 0, 1) };
        byte[] obj = new Obj().material("screen").quad(quad).bytes();
        byte[] settings = "{\"scale\": 2.5, \"flip_v\": false}".getBytes(StandardCharsets.UTF_8);
        ObjUvFrame.Fit fit = ObjUvFrame.of(obj, settings, "glass", "screen");
        assertMaps(fit, 2.5, false, quad);

        // Unreadable settings are none, as Hypergraphical has it
        ObjUvFrame.Fit plain = ObjUvFrame.of(obj, "not json".getBytes(StandardCharsets.UTF_8), "glass", "screen");
        assertMaps(plain, 1, true, quad);
    }

    @Test
    void aCurvedSurfaceHasAResidualAndAFlatOneNone() throws Exception {
        // A quarter of a cylinder of radius 8 bulging up, u running round it
        Obj curved = new Obj().material("screen");
        int strips = 8;
        for (int i = 0; i < strips; i++) {
            double a0 = Math.toRadians(-45 + 90.0 * i / strips), a1 = Math.toRadians(-45 + 90.0 * (i + 1) / strips);
            double u0 = i / (double) strips, u1 = (i + 1) / (double) strips;
            curved.quad(c(8 * Math.sin(a0), 8 * Math.cos(a0), 0, u0, 0), c(8 * Math.sin(a1), 8 * Math.cos(a1), 0, u1, 0),
                    c(8 * Math.sin(a1), 8 * Math.cos(a1), 4, u1, 1), c(8 * Math.sin(a0), 8 * Math.cos(a0), 4, u0, 1));
        }
        ObjUvFrame.Fit fit = ObjUvFrame.of(curved.bytes(), null, "glass", "screen");
        assertNotNull(fit);
        assertTrue(fit.residual() > 0.5, "residual " + fit.residual());

        double[][] flat = { c(0, 1, 0, 0, 0), c(4, 1, 0, 1, 0), c(4, 1, 2, 1, 1), c(0, 1, 2, 0, 1) };
        assertTrue(ObjUvFrame.of(new Obj().material("screen").quad(flat).bytes(), null, "glass", "screen")
                .residual() < 1e-9);
    }

    @Test
    void noFacesOrNoUvExtentIsNoFit() throws Exception {
        double[][] quad = { c(0, 1, 0, 0, 0), c(4, 1, 0, 1, 0), c(4, 1, 2, 1, 1), c(0, 1, 2, 0, 1) };
        byte[] obj = new Obj().material("screen").quad(quad).bytes();
        assertNull(ObjUvFrame.of(obj, null, "glass", "other"));
        assertNull(ObjUvFrame.of(obj, null, "lens", "screen"));
        // Every corner at one UV names no plane
        List<double[]> same = new ArrayList<>();
        for (double[] corner : quad)
            same.add(c(corner[0], corner[1], corner[2], 0.5, 0.5));
        assertNull(ObjUvFrame.of(new Obj().material("screen").quad(same.toArray(new double[0][])).bytes(), null,
                "glass", "screen"));
    }
}
