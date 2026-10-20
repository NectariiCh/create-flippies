package com.nectariich.create_flippies.client.schematic_arrow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.client.decal.Earcut;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.decal.PanelDecal;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowStyle;
import com.nectariich.create_flippies.content.schematic_arrow.PanelArrow;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

/**
 * A schematic arrow's flat shape ready to be raised: the triangles of its top and
 * the edges its walls stand on, in the surface's own u and v, in blocks.
 *
 * Built once per arrow and kept (see {@link ArrowMeshes}), because it is the same
 * for every cell the arrow crosses and every time its chunk is rebuilt; a cell then
 * only has to cut it to its own walls and lift it.
 *
 * <h2>Walls</h2>
 *
 * Every wall carries the outward normal of the edge it stands on, in u and v. For a
 * drawn arrow the edges are the outline's rings, which run counter-clockwise, so
 * outward is to the right of each edge. For an imported picture there are no rings,
 * only the importer's triangles, and the edges are found from those instead: an edge
 * belongs to the outline exactly when no other triangle has it running the other
 * way. That finds the outside of every shape and the inside of every hole without
 * caring how the importer cut them, and where two shapes meet with a vertex on one
 * side only it finds a wall that is not there; such a wall stands inside the raised
 * shape, under its top, where it costs a few triangles and cannot be seen.
 */
public final class ArrowMesh {

    /** The importer's lattice, which welds the corners triangles share. See {@link DecalArt}. */
    private static final double WELD = 0xFFFF;

    private final double[] top;
    private final int[] topColours;
    private final double[] walls;
    private final int[] wallColours;

    private ArrowMesh(double[] top, int[] topColours, double[] walls, int[] wallColours) {
        this.top = top;
        this.topColours = topColours;
        this.walls = walls;
        this.wallColours = wallColours;
    }

    /** Three corners per triangle, u and v of each, in blocks. */
    public double[] top() {
        return top;
    }

    /** The colour of each triangle of the top. */
    public int[] topColours() {
        return topColours;
    }

    /** Per wall: u and v of its two ends, in blocks, then its outward normal's u and v. */
    public double[] walls() {
        return walls;
    }

    public int[] wallColours() {
        return wallColours;
    }

    public int triangleCount() {
        return top.length / 6;
    }

    public int wallCount() {
        return walls.length / 6;
    }

    /** The mesh of an arrow, or null for a stamped one whose picture is not here. */
    public static ArrowMesh of(PanelArrow arrow, DecalArt art) {
        if (!arrow.isStamped())
            return ofRings(arrow.outline().rings(), PanelPlane.UNITS_PER_BLOCK, arrow.style());
        return art == null ? null : ofArt(art, arrow);
    }

    /**
     * Rings that run counter-clockwise, in units {@code scale} to the block, filled and
     * walled in the style's colour.
     */
    public static ArrowMesh ofRings(List<double[]> rings, double scale, ArrowStyle style) {
        int topColour = style.topColour(ArrowStyle.PLAIN);
        int sideColour = style.sideColour(topColour);
        Builder builder = new Builder();
        for (double[] ring : rings) {
            List<Integer> triangles = Earcut.triangulate(ring, null);
            for (int t = 0; t + 2 < triangles.size(); t += 3) {
                int a = triangles.get(t);
                int b = triangles.get(t + 1);
                int c = triangles.get(t + 2);
                builder.triangle(ring[2 * a] / scale, ring[2 * a + 1] / scale, ring[2 * b] / scale,
                        ring[2 * b + 1] / scale, ring[2 * c] / scale, ring[2 * c + 1] / scale, topColour);
            }
            int m = ring.length / 2;
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                builder.wall(ring[2 * i] / scale, ring[2 * i + 1] / scale, ring[2 * j] / scale,
                        ring[2 * j + 1] / scale, sideColour);
            }
        }
        return builder.build();
    }

    /**
     * An imported picture laid where a stamped arrow says, every part of it raised
     * except the raster pictures it embeds ({@link DecalArt#pixels}), which a stamp
     * leaves out: raised cell by cell they would be thousands of walls and no shape.
     */
    public static ArrowMesh ofArt(DecalArt art, PanelArrow arrow) {
        PanelDecal laid = arrow.picture();
        ArrowStyle style = arrow.style();
        Builder builder = new Builder();
        Map<Edge, double[]> edges = new HashMap<>();
        double[] uv = new double[2];
        long[] keys = new long[3];
        double[] corners = new double[6];
        for (DecalArt.Layer layer : art.layers()) {
            int topColour = style.topColour(layer.colour());
            int sideColour = style.sideColour(topColour);
            float[] points = layer.triangles();
            for (int t = 0; t + 5 < points.length; t += 6) {
                for (int k = 0; k < 3; k++) {
                    double x = points[t + 2 * k];
                    double y = points[t + 2 * k + 1];
                    keys[k] = Math.round(x * WELD) << 32 | Math.round(y * WELD);
                    laid.map(x, y, uv);
                    corners[2 * k] = uv[0];
                    corners[2 * k + 1] = uv[1];
                }
                double area = (corners[2] - corners[0]) * (corners[5] - corners[1])
                        - (corners[4] - corners[0]) * (corners[3] - corners[1]);
                if (area == 0 || keys[0] == keys[1] || keys[1] == keys[2] || keys[2] == keys[0])
                    continue;
                // Counter-clockwise in u and v, whatever the picture's turn and handedness
                // did to it, so that every edge has its outside on its right
                int second = area > 0 ? 1 : 2;
                int third = area > 0 ? 2 : 1;
                builder.triangle(corners[0], corners[1], corners[2 * second], corners[2 * second + 1],
                        corners[2 * third], corners[2 * third + 1], topColour);
                int[] order = { 0, second, third };
                for (int k = 0; k < 3; k++) {
                    int from = order[k];
                    int to = order[(k + 1) % 3];
                    edges.putIfAbsent(new Edge(keys[from], keys[to]), new double[] { corners[2 * from],
                            corners[2 * from + 1], corners[2 * to], corners[2 * to + 1], sideColour });
                }
            }
        }
        for (Map.Entry<Edge, double[]> entry : edges.entrySet()) {
            Edge edge = entry.getKey();
            if (edges.containsKey(new Edge(edge.to, edge.from)))
                continue;
            double[] e = entry.getValue();
            builder.wall(e[0], e[1], e[2], e[3], (int) e[4]);
        }
        return builder.build();
    }

    private record Edge(long from, long to) {}

    private static final class Builder {

        private double[] top = new double[96];
        private int[] topColours = new int[16];
        private int triangles;
        private double[] walls = new double[96];
        private int[] wallColours = new int[16];
        private int wallCount;

        void triangle(double au, double av, double bu, double bv, double cu, double cv, int colour) {
            if (triangles * 6 + 6 > top.length) {
                top = java.util.Arrays.copyOf(top, top.length * 2);
                topColours = java.util.Arrays.copyOf(topColours, topColours.length * 2);
            }
            int at = triangles * 6;
            top[at] = au;
            top[at + 1] = av;
            top[at + 2] = bu;
            top[at + 3] = bv;
            top[at + 4] = cu;
            top[at + 5] = cv;
            topColours[triangles++] = colour;
        }

        void wall(double au, double av, double bu, double bv, int colour) {
            double du = bu - au;
            double dv = bv - av;
            double length = Math.hypot(du, dv);
            if (length < 1e-9)
                return;
            if (wallCount * 6 + 6 > walls.length) {
                walls = java.util.Arrays.copyOf(walls, walls.length * 2);
                wallColours = java.util.Arrays.copyOf(wallColours, wallColours.length * 2);
            }
            int at = wallCount * 6;
            walls[at] = au;
            walls[at + 1] = av;
            walls[at + 2] = bu;
            walls[at + 3] = bv;
            // Outside is on the right of an edge of a counter-clockwise ring
            walls[at + 4] = (dv / length);
            walls[at + 5] = (-du / length);
            wallColours[wallCount++] = colour;
        }

        ArrowMesh build() {
            return new ArrowMesh(java.util.Arrays.copyOf(top, triangles * 6),
                    java.util.Arrays.copyOf(topColours, triangles), java.util.Arrays.copyOf(walls, wallCount * 6),
                    java.util.Arrays.copyOf(wallColours, wallCount));
        }
    }
}
