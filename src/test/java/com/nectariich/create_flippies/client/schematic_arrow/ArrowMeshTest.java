package com.nectariich.create_flippies.client.schematic_arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.client.decal.SvgDecalImporter;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowHead;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowJoin;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowOutline;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowStyle;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

class ArrowMeshTest {

    private static final String ART = "0123456789abcdef0123456789abcdef";
    private static final PanelPlane WALL = new PanelPlane(Direction.SOUTH, 0, 0);
    private static final int BLOCK = PanelPlane.UNITS_PER_BLOCK;
    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>";

    private static double topArea(ArrowMesh mesh) {
        double[] t = mesh.top();
        double sum = 0;
        for (int i = 0; i < t.length; i += 6)
            sum += ((t[i + 2] - t[i]) * (t[i + 5] - t[i + 1]) - (t[i + 4] - t[i]) * (t[i + 3] - t[i + 1])) / 2;
        return sum;
    }

    /** Every wall end is where exactly as many walls start as end: the walls close. */
    private static void assertClosed(ArrowMesh mesh) {
        double[] w = mesh.walls();
        Map<String, Integer> balance = new HashMap<>();
        for (int i = 0; i < w.length; i += 6) {
            balance.merge(key(w[i], w[i + 1]), 1, Integer::sum);
            balance.merge(key(w[i + 2], w[i + 3]), -1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : balance.entrySet())
            assertEquals(0, entry.getValue(), "Walls open at " + entry.getKey());
    }

    private static String key(double u, double v) {
        return Math.round(u * 1e5) + "," + Math.round(v * 1e5);
    }

    /** Every wall's normal points out of the shape: a step along it from its middle is off the top. */
    private static void assertNormalsOutward(ArrowMesh mesh, double step) {
        double[] w = mesh.walls();
        for (int i = 0; i < w.length; i += 6) {
            double mu = (w[i] + w[i + 2]) / 2;
            double mv = (w[i + 1] + w[i + 3]) / 2;
            assertEquals(1, Math.hypot(w[i + 4], w[i + 5]), 1e-6);
            assertEquals(0, w[i + 4] * (w[i + 2] - w[i]) + w[i + 5] * (w[i + 3] - w[i + 1]), 1e-9);
            assertFalse(onTop(mesh, mu + w[i + 4] * step, mv + w[i + 5] * step), "Wall " + i / 6 + " faces in");
            assertTrue(onTop(mesh, mu - w[i + 4] * step, mv - w[i + 5] * step), "Wall " + i / 6 + " stands alone");
        }
    }

    private static boolean onTop(ArrowMesh mesh, double u, double v) {
        double[] t = mesh.top();
        for (int i = 0; i < t.length; i += 6) {
            double d1 = (t[i + 2] - t[i]) * (v - t[i + 1]) - (t[i + 3] - t[i + 1]) * (u - t[i]);
            double d2 = (t[i + 4] - t[i + 2]) * (v - t[i + 3]) - (t[i + 5] - t[i + 3]) * (u - t[i + 2]);
            double d3 = (t[i] - t[i + 4]) * (v - t[i + 5]) - (t[i + 1] - t[i + 5]) * (u - t[i + 4]);
            if (d1 >= 0 && d2 >= 0 && d3 >= 0)
                return true;
        }
        return false;
    }

    @Test
    void aStripIsTwoTrianglesAndFourWalls() {
        ArrowMesh mesh = ArrowMesh.ofRings(ArrowOutline.of(new double[] { 0, 0, 10, 0 }, 2, ArrowHead.NONE,
                ArrowHead.NONE, ArrowJoin.MITER).rings(), 1, ArrowStyle.DEFAULT);
        assertEquals(2, mesh.triangleCount());
        assertEquals(4, mesh.wallCount());
        assertEquals(20, topArea(mesh), 1e-9);
        assertClosed(mesh);
        assertNormalsOutward(mesh, 1e-3);
    }

    @Test
    void anArrowsTopIsItsOutlineAndItsWallsGoAllRound() {
        ArrowOutline.Shape shape = ArrowOutline.of(new double[] { 0, 0, 20, 0, 20, 20 }, 2, ArrowHead.CHEVRON,
                ArrowHead.TRIANGLE, ArrowJoin.ROUND);
        ArrowMesh mesh = ArrowMesh.ofRings(shape.rings(), 1, ArrowStyle.DEFAULT);
        int corners = shape.rings().get(0).length / 2;
        // A simple polygon of n corners is n - 2 triangles, and has n edges
        assertEquals(corners - 2, mesh.triangleCount());
        assertEquals(corners, mesh.wallCount());
        assertEquals(ArrowOutline.signedArea(shape.rings().get(0)), topArea(mesh), 1e-9);
        assertClosed(mesh);
        assertNormalsOutward(mesh, 1e-3);
    }

    @Test
    void pieceMeshesAreClosedPieceByPiece() {
        ArrowOutline.Shape shape = ArrowOutline.of(new double[] { 0, 0, 20, 0, 20, 10, 10, -10 }, 2, ArrowHead.NONE,
                ArrowHead.TRIANGLE, ArrowJoin.MITER);
        assertFalse(shape.merged());
        ArrowMesh mesh = ArrowMesh.ofRings(shape.rings(), 1, ArrowStyle.DEFAULT);
        int edges = 0;
        for (double[] ring : shape.rings())
            edges += ring.length / 2;
        assertEquals(edges, mesh.wallCount());
        assertClosed(mesh);
    }

    @Test
    void coloursFollowTheStyle() {
        ArrowStyle style = ArrowStyle.DEFAULT.withColour(0xFF3366CC);
        ArrowMesh mesh = ArrowMesh.ofRings(ArrowOutline.of(new double[] { 0, 0, 10, 0 }, 2, ArrowHead.NONE,
                ArrowHead.NONE, ArrowJoin.MITER).rings(), 1, style);
        assertEquals(0xFF3366CC, mesh.topColours()[0]);
        assertEquals(ArrowStyle.shade(0xFF3366CC, 0.72), mesh.wallColours()[0]);
        ArrowMesh flat = ArrowMesh.ofRings(ArrowOutline.of(new double[] { 0, 0, 10, 0 }, 2, ArrowHead.NONE,
                ArrowHead.NONE, ArrowJoin.MITER).rings(), 1, style.withShadedSides(false));
        assertEquals(0xFF3366CC, flat.wallColours()[0]);
    }

    @Test
    void aDrawnArrowsMeshIsInBlocks() {
        PanelArrow arrow = PanelArrow.drawn(1, WALL, 0, ArrowStyle.DEFAULT.withHeads(ArrowHead.NONE, ArrowHead.NONE),
                new int[] { 0, 0, BLOCK, 0 });
        ArrowMesh mesh = ArrowMesh.of(arrow, null);
        // A block long and two pixels wide
        assertEquals(2.0 / 16, topArea(mesh), 1e-9);
    }

    private static DecalArt svg(String body, boolean transparent) throws IOException {
        return SvgDecalImporter.parse((SVG + body + "</svg>").getBytes(StandardCharsets.UTF_8), "test", transparent)
                .art();
    }

    private static PanelArrow stamp(int quarters) {
        return PanelArrow.stamped(1, WALL, 0, ArrowStyle.DEFAULT.withColour(ArrowStyle.OWN_COLOURS), ART,
                new int[] { 0, 0, BLOCK, BLOCK }, quarters);
    }

    @Test
    void anEmbeddedPictureIsLeftOutOfTheStamp() throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                image.setRGB(x, y, (x + y) % 2 == 0 ? 0xFFFF0000 : 0xFF00FF00);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);
        DecalArt art = svg("<rect x='10' y='10' width='30' height='30' fill='blue'/><image x='50' y='50' width='40'"
                + " height='40' href='data:image/png;base64,"
                + java.util.Base64.getEncoder().encodeToString(png.toByteArray()) + "'/>", false);
        assertFalse(art.pixels().isEmpty());
        ArrowMesh mesh = ArrowMesh.ofArt(art, stamp(0));
        assertEquals(0.09, Math.abs(topArea(mesh)), 1e-3);
        assertEquals(4, mesh.wallCount());
    }

    @Test
    void anImportedRingIsRaisedWithItsHoleWalled() throws IOException {
        DecalArt art = svg("<path fill='red' fill-rule='evenodd' d='M10 10H90V90H10Z M30 30H70V70H30Z'/>", false);
        for (int quarters = 0; quarters < 4; quarters++) {
            ArrowMesh mesh = ArrowMesh.ofArt(art, stamp(quarters));
            assertEquals(0.64 - 0.16, Math.abs(topArea(mesh)), 1e-3);
            assertTrue(topArea(mesh) > 0, "Top wound clockwise at a turn of " + quarters);
            assertClosed(mesh);
            assertNormalsOutward(mesh, 1e-3);
            // Walls along the outside and the inside of the ring and nowhere else
            double outside = 0;
            double inside = 0;
            double[] w = mesh.walls();
            for (int i = 0; i < w.length; i += 6) {
                double length = Math.hypot(w[i + 2] - w[i], w[i + 3] - w[i + 1]);
                double mu = (w[i] + w[i + 2]) / 2;
                double mv = (w[i + 1] + w[i + 3]) / 2;
                if (Math.abs(mu - 0.5) > 0.35 || Math.abs(mv - 0.5) > 0.35)
                    outside += length;
                else
                    inside += length;
            }
            assertEquals(4 * 0.8, outside, 1e-3);
            assertEquals(4 * 0.4, inside, 1e-3);
            assertEquals(0xFFFF0000, mesh.topColours()[0]);
        }
    }

    @Test
    void twoColoursThatMeetHaveNoWallBetweenThem() throws IOException {
        DecalArt art = svg("<rect x='0' y='0' width='50' height='100' fill='red'/>"
                + "<rect x='50' y='0' width='50' height='100' fill='blue'/>", false);
        ArrowMesh mesh = ArrowMesh.ofArt(art, stamp(0));
        double total = 0;
        double[] w = mesh.walls();
        for (int i = 0; i < w.length; i += 6)
            total += Math.hypot(w[i + 2] - w[i], w[i + 3] - w[i + 1]);
        // The outside of the square only: a seam down the middle would add a block
        assertEquals(4, total, 1e-3);
        assertClosed(mesh);
    }

    @Test
    void aTransparentImportRaisesOnlyTheArtwork() throws IOException {
        String body = "<rect width='100' height='100' fill='white'/><rect x='25' y='25' width='50' height='50'"
                + " fill='red'/>";
        ArrowMesh kept = ArrowMesh.ofArt(svg(body, false), stamp(0));
        ArrowMesh clear = ArrowMesh.ofArt(svg(body, true), stamp(0));
        assertEquals(1, topArea(kept), 1e-3);
        assertEquals(0.25, topArea(clear), 1e-3);
        assertClosed(clear);
        assertNormalsOutward(clear, 1e-3);
    }

    @Test
    void aStampedArrowWithoutItsPictureHasNoMeshYet() {
        assertEquals(null, ArrowMesh.of(stamp(0), null));
        assertTrue(List.of(stamp(0)).get(0).isStamped());
    }
}
