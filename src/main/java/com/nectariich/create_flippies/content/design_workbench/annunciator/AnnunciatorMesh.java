package com.nectariich.create_flippies.content.design_workbench.annunciator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.nectariich.create_flippies.content.design_workbench.DesignBundle;

/**
 * Builds the model of an annunciator rack out of its definition, as the files of a
 * design: an {@code .obj} with the frame (a socket cut for every window) and one lens
 * per window, and an {@code .mtl} with the frame's material and a lens material per
 * colour the rack uses. Java port of the Unity {@code AnnunciatorRackBuilder}'s
 * meshes and materials; the legends are text, laid out by {@link AnnunciatorRack}.
 *
 * The result goes through the same store, preview and renderer as an imported
 * model, so a rack is a design like any other once built. It is written in authored
 * pixels with its origin at the cell's min corner, so a design carries it at scale 1
 * and no offset. Numbers are written with a fixed precision and parts in a fixed
 * order, so the same rack always makes the same bytes and so the same hash: a rack
 * rebuilt unchanged is not stored or sent twice.
 *
 * Parts: {@code frame}, and {@code w_<row>_<column>} (counted from 1) for each window
 * that is not blanked off, each lens using {@code lens_<colour>}. A lit window is
 * meant to be lit by part, which is why every window is its own.
 */
public final class AnnunciatorMesh {

    public static final String OBJ = "rack.obj", MTL = "rack.mtl";

    public static final String FRAME_PART = "frame";

    /** Faces thinner than this are dropped rather than drawn as slivers. */
    private static final float EPSILON = 1e-4f;

    /** The six axis normals, in the order they are written: +x, -x, +y, -y, +z, -z. */
    private static final float[][] NORMALS = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 },
            { 0, 0, -1 } };
    private static final int PX = 0, NX = 1, PY = 2, PZ = 4, NZ = 5;

    private AnnunciatorMesh() {}

    /** The window part for a cell. */
    public static String windowPart(int row, int column) {
        return "w_" + (row + 1) + "_" + (column + 1);
    }

    public static String lensMaterial(LensColor color) {
        return "lens_" + color.wireName();
    }

    /** Whether an element of a rack's model is a window's lens: a window part drawn with a lens material. */
    public static boolean isLens(String part, String material) {
        return part.startsWith("w_") && material.startsWith("lens_");
    }

    /** The rack's files. The rack must be valid ({@link AnnunciatorRack#error} null). */
    public static DesignBundle build(AnnunciatorRack rack) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(OBJ, obj(rack).getBytes(StandardCharsets.US_ASCII));
        files.put(MTL, mtl(rack).getBytes(StandardCharsets.US_ASCII));
        return new DesignBundle(OBJ, files);
    }

    // ----------------------------------------------------------------- model

    private static String obj(AnnunciatorRack rack) {
        Obj obj = new Obj();
        float[] box = toFloats(rack.box());
        float left = box[0], top = box[2], right = box[3], bottom = box[5];
        float face = rack.depth, lens = rack.lensY();
        float cellWidth = rack.cellWidth(), cellHeight = rack.cellHeight();

        // Boundaries along each axis, alternating frame span and window span, so the
        // face is a grid of rectangles and a window is the one that is not drawn
        float[] xs = new float[2 * rack.cellsX + 2];
        xs[0] = left;
        for (int column = 0; column < rack.cellsX; column++) {
            xs[2 * column + 1] = rack.cellLeft(column);
            xs[2 * column + 2] = rack.cellLeft(column) + cellWidth;
        }
        xs[xs.length - 1] = right;
        float[] zs = new float[2 * rack.cellsY + 2];
        zs[0] = top;
        for (int row = 0; row < rack.cellsY; row++) {
            zs[2 * row + 1] = rack.cellTop(row);
            zs[2 * row + 2] = rack.cellTop(row) + cellHeight;
        }
        zs[zs.length - 1] = bottom;

        obj.part(FRAME_PART, "frame");
        for (int i = 0; i < xs.length - 1; i++)
            for (int j = 0; j < zs.length - 1; j++) {
                if (i % 2 == 1 && j % 2 == 1 && open(rack, (j - 1) / 2, (i - 1) / 2))
                    continue; // a window opening: the lens shows through here
                obj.flat(xs[i], xs[i + 1], zs[j], zs[j + 1], face, PY);
            }

        // Sockets: the four walls between the face and the lens, facing into the socket
        for (int row = 0; row < rack.cellsY; row++)
            for (int column = 0; column < rack.cellsX; column++) {
                if (!open(rack, row, column))
                    continue;
                float x0 = xs[2 * column + 1], x1 = xs[2 * column + 2];
                float z0 = zs[2 * row + 1], z1 = zs[2 * row + 2];
                obj.wallX(x0, z0, z1, lens, face, PX);
                obj.wallX(x1, z0, z1, lens, face, NX);
                obj.wallZ(z0, x0, x1, lens, face, PZ);
                obj.wallZ(z1, x0, x1, lens, face, NZ);
            }

        // The sides of the box. Its back is against the panel and never seen
        obj.wallX(left, top, bottom, 0, face, NX);
        obj.wallX(right, top, bottom, 0, face, PX);
        obj.wallZ(top, left, right, 0, face, NZ);
        obj.wallZ(bottom, left, right, 0, face, PZ);

        for (int row = 0; row < rack.cellsY; row++)
            for (int column = 0; column < rack.cellsX; column++) {
                if (!open(rack, row, column))
                    continue;
                obj.part(windowPart(row, column), lensMaterial(rack.tile(row, column).color()));
                obj.flat(xs[2 * column + 1], xs[2 * column + 2], zs[2 * row + 1], zs[2 * row + 2], lens, PY);
            }
        return obj.text();
    }

    private static boolean open(AnnunciatorRack rack, int row, int column) {
        return !rack.tile(row, column).blank();
    }

    // -------------------------------------------------------------- materials

    /**
     * The frame, and one lens material per colour the rack uses, coloured as the
     * window looks dark: grey for a plain colour, its own dark tint for an x one. The
     * plain colours are kept apart even though they look alike unlit, so lighting
     * one later only has to know its material.
     */
    private static String mtl(AnnunciatorRack rack) {
        StringBuilder out = new StringBuilder();
        material(out, "frame", rack.frameColor, 0.55f, 0.15f);
        Set<LensColor> used = EnumSet.noneOf(LensColor.class);
        for (AnnunciatorRack.Tile tile : rack.tiles)
            if (!tile.blank())
                used.add(tile.color());
        for (LensColor color : used)
            material(out, lensMaterial(color), rack.unlitRgb(color), 0.25f, 0);
        return out.toString();
    }

    private static void material(StringBuilder out, String name, int rgb, float roughness, float metallic) {
        out.append("newmtl ").append(name).append('\n');
        out.append(String.format(Locale.ROOT, "Kd %.4f %.4f %.4f\n", (rgb >> 16 & 0xFF) / 255f,
                (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f));
        out.append(String.format(Locale.ROOT, "Pr %.2f\nPm %.2f\n\n", roughness, metallic));
    }

    private static float[] toFloats(double[] box) {
        float[] out = new float[box.length];
        for (int i = 0; i < box.length; i++)
            out[i] = (float) box[i];
        return out;
    }

    /**
     * An {@code .obj} being written: vertices welded by position, the six normals
     * and one texture coordinate shared by everything, and faces grouped by part in
     * the order the parts were started.
     */
    private static final class Obj {
        private final List<String> vertices = new ArrayList<>();
        private final Map<String, Integer> index = new LinkedHashMap<>();
        private final StringBuilder faces = new StringBuilder();

        void part(String name, String material) {
            faces.append("o ").append(name).append('\n').append("usemtl ").append(material).append('\n');
        }

        /** A rectangle in a plane of constant y, facing up or down. */
        void flat(float x0, float x1, float z0, float z1, float y, int normal) {
            if (x1 - x0 <= EPSILON || z1 - z0 <= EPSILON)
                return; // no bezel or no rib: nothing to draw between the windows
            quad(new float[][] { { x0, y, z0 }, { x1, y, z0 }, { x1, y, z1 }, { x0, y, z1 } }, normal);
        }

        /** A wall in a plane of constant x, from {@code y0} up to {@code y1}. */
        void wallX(float x, float z0, float z1, float y0, float y1, int normal) {
            if (z1 - z0 <= EPSILON || y1 - y0 <= EPSILON)
                return;
            quad(new float[][] { { x, y0, z0 }, { x, y0, z1 }, { x, y1, z1 }, { x, y1, z0 } }, normal);
        }

        /** A wall in a plane of constant z, from {@code y0} up to {@code y1}. */
        void wallZ(float z, float x0, float x1, float y0, float y1, int normal) {
            if (x1 - x0 <= EPSILON || y1 - y0 <= EPSILON)
                return;
            quad(new float[][] { { x0, y0, z }, { x1, y0, z }, { x1, y1, z }, { x0, y1, z } }, normal);
        }

        /**
         * Four corners in ring order and the normal the face should have. The winding
         * is turned to match, counter-clockwise seen from outside, so callers only
         * have to get the ring right; which side is out is stated, not derived.
         */
        private void quad(float[][] corners, int normal) {
            float[] a = corners[0], b = corners[1], c = corners[2];
            float[] n = NORMALS[normal];
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float dot = (uy * vz - uz * vy) * n[0] + (uz * vx - ux * vz) * n[1] + (ux * vy - uy * vx) * n[2];
            int[] order = dot >= 0 ? new int[] { 0, 1, 2, 3 } : new int[] { 0, 3, 2, 1 };
            faces.append('f');
            for (int corner : order)
                faces.append(' ').append(vertex(corners[corner])).append("/1/").append(normal + 1);
            faces.append('\n');
        }

        private int vertex(float[] position) {
            String line = String.format(Locale.ROOT, "v %.4f %.4f %.4f", position[0], position[1], position[2]);
            return index.computeIfAbsent(line, key -> {
                vertices.add(key);
                return vertices.size();
            });
        }

        String text() {
            StringBuilder out = new StringBuilder("# Annunciator rack, generated by Create: Flippies\n");
            out.append("mtllib ").append(MTL).append('\n');
            for (String vertex : vertices)
                out.append(vertex).append('\n');
            out.append("vt 0 0\n");
            for (float[] n : NORMALS)
                out.append(String.format(Locale.ROOT, "vn %d %d %d\n", (int) n[0], (int) n[1], (int) n[2]));
            return out.append(faces).toString();
        }
    }
}
