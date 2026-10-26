package com.nectariich.create_flippies.client.design.screen;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;

import net.minecraft.util.GsonHelper;

/**
 * Where a screen's UV space is in the model: the plane P = O + u·A + v·B through one
 * submesh, fitted to its faces, so a screen's elements can be drawn in front of that
 * submesh at the place its texture coordinates name ({@code ScreenSurface}'s frame).
 *
 * Hypergraphical keeps neither positions nor UVs it can be asked for, so this reads
 * the {@code .obj} the way its reader does, as {@code ObjUvBounds} does for a gauge's
 * face, and also reads the import settings beside it ({@code <obj>.json}, the keys
 * {@code scale} and {@code flip_v}), so the frame and the drawn mesh agree: positions
 * are scaled by the import scale, and V is flipped unless the settings say not to.
 * Faces are fans about their first corner, as Hypergraphical triangulates them; a
 * corner with no texture coordinate counts as (0, 0).
 *
 * <h2>Only the front</h2>
 *
 * A screen material is often on more than the glass: on every face of a box, say, of
 * which only the front is the screen. So the faces are grouped by which way they face
 * (within {@value #SAME_FACING_DEGREES} degrees of a group's largest face), and only
 * the group with the most area is fitted, the one facing up out of the panel when two
 * are as large; its faces also give the UV rectangle. A gently curved screen stays one
 * group.
 *
 * <h2>Curved surfaces</h2>
 *
 * The fit is least squares, weighted by area, so it holds for any mesh, and the
 * largest distance of a fitted vertex from the plane is reported as the residual. A
 * flat face whose texture is mapped without distortion has none; a curved one, or one
 * whose texture is stretched unevenly, has some, and the workbench warns that the
 * screen's content is then only approximately on it.
 */
public final class ObjUvFrame {

    /** How far apart in angle two faces may face and still be one surface. */
    static final double SAME_FACING_DEGREES = 60;

    private static final double SAME_FACING = Math.cos(Math.toRadians(SAME_FACING_DEGREES));

    /** Two groups whose areas are this close count as equally large. */
    private static final double TIE = 0.01;

    private static final String DEFAULT = "default";

    /**
     * A fitted plane.
     *
     * @param uv       the UV rectangle the fitted faces cover, {u0, v0, u1, v1}
     * @param frame    {origin, dP/du, dP/dv}, each {x, y, z}, in the model's units after its import scale
     * @param residual the farthest a fitted vertex is from the plane, in the same units
     */
    public record Fit(float[] uv, double[] frame, double residual) {}

    private ObjUvFrame() {}

    /** The plane of one submesh of a bundled model, or null when it has no faces with any extent in UV. */
    public static Fit of(DesignBundle bundle, String part, String material) throws IOException {
        return of(bundle.obj(), bundle.files().get(bundle.objName() + ".json"), part, material);
    }

    /**
     * The plane of the faces of {@code part} drawn with {@code material}.
     *
     * @param settings the import settings file's bytes, or null for none
     */
    public static Fit of(byte[] obj, byte[] settings, String part, String material) throws IOException {
        float scale = 1;
        boolean flipV = true;
        if (settings != null) {
            // Read as Hypergraphical reads it, and like it, a file it cannot read is no settings at all
            try {
                JsonObject json = JsonParser
                        .parseString(new String(settings, StandardCharsets.UTF_8)).getAsJsonObject();
                scale = GsonHelper.getAsFloat(json, "scale", 1);
                flipV = GsonHelper.getAsBoolean(json, "flip_v", true);
            } catch (RuntimeException e) {
                scale = 1;
                flipV = true;
            }
        }
        List<Triangle> triangles = triangles(obj, scale, flipV, part, material);
        List<Triangle> front = front(triangles);
        return front.isEmpty() ? null : fit(front);
    }

    // ------------------------------------------------------------ reading

    /** One triangle: three corners, each {x, y, z, u, v}, with its area and unit normal. */
    record Triangle(double[][] corners, double area, double[] normal) {}

    private static List<Triangle> triangles(byte[] obj, float scale, boolean flipV, String part, String material)
            throws IOException {
        List<double[]> positions = new ArrayList<>();
        List<double[]> uvs = new ArrayList<>();
        List<Triangle> out = new ArrayList<>();
        String currentPart = DEFAULT, currentMaterial = DEFAULT;
        boolean sawObject = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(obj), StandardCharsets.UTF_8))) {
            String[] line;
            while ((line = next(reader)) != null) {
                switch (line[0]) {
                case "v" -> positions.add(new double[] { number(line, 1) * scale, number(line, 2) * scale,
                        number(line, 3) * scale });
                case "vt" -> {
                    double v = number(line, 2);
                    uvs.add(new double[] { number(line, 1), flipV ? 1 - v : v });
                }
                case "usemtl" -> currentMaterial = line.length > 1 ? line[1] : DEFAULT;
                case "o" -> {
                    sawObject = true;
                    currentPart = name(line);
                }
                case "g" -> {
                    if (!sawObject)
                        currentPart = name(line);
                }
                case "f" -> {
                    if (line.length < 4 || !currentPart.equals(part) || !currentMaterial.equals(material))
                        break;
                    double[][] corners = new double[line.length - 1][];
                    boolean usable = true;
                    for (int i = 1; i < line.length && usable; i++) {
                        corners[i - 1] = corner(line[i], positions, uvs);
                        usable = corners[i - 1] != null;
                    }
                    if (!usable)
                        break;
                    for (int i = 2; i < corners.length; i++) {
                        Triangle triangle = triangle(corners[0], corners[i - 1], corners[i]);
                        if (triangle != null)
                            out.add(triangle);
                    }
                }
                default -> {
                }
                }
            }
        }
        return out;
    }

    /** One {@code v/vt/vn} corner as {x, y, z, u, v}, or null when its position is missing. */
    private static double[] corner(String token, List<double[]> positions, List<double[]> uvs) {
        String[] fields = token.split("/", -1);
        double[] position = at(positions, fields[0]);
        if (position == null)
            return null;
        double[] uv = fields.length >= 2 && !fields[1].isEmpty() ? at(uvs, fields[1]) : null;
        if (uv == null)
            uv = new double[] { 0, 0 };
        return new double[] { position[0], position[1], position[2], uv[0], uv[1] };
    }

    /** An OBJ index into a list: from 1, or from the end when negative. */
    private static double[] at(List<double[]> list, String index) {
        try {
            int i = Integer.parseInt(index.trim());
            i = i < 0 ? list.size() + i : i - 1;
            return i >= 0 && i < list.size() ? list.get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Triangle triangle(double[] a, double[] b, double[] c) {
        double ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2];
        double fx = c[0] - a[0], fy = c[1] - a[1], fz = c[2] - a[2];
        double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1e-12))
            return null;
        return new Triangle(new double[][] { a, b, c }, length / 2,
                new double[] { nx / length, ny / length, nz / length });
    }

    // ------------------------------------------------------------ fitting

    /** The triangles of the largest group facing one way; see the class notes. */
    static List<Triangle> front(List<Triangle> triangles) {
        List<Triangle> sorted = new ArrayList<>(triangles);
        sorted.sort(Comparator.comparingDouble(Triangle::area).reversed());
        List<List<Triangle>> groups = new ArrayList<>();
        List<double[]> facings = new ArrayList<>();
        for (Triangle triangle : sorted) {
            int joined = -1;
            for (int g = 0; g < groups.size() && joined < 0; g++)
                if (dot(facings.get(g), triangle.normal()) >= SAME_FACING)
                    joined = g;
            if (joined < 0) {
                groups.add(new ArrayList<>());
                facings.add(triangle.normal());
                joined = groups.size() - 1;
            }
            groups.get(joined).add(triangle);
        }
        List<Triangle> best = List.of();
        double bestArea = 0, bestUp = Double.NEGATIVE_INFINITY;
        for (int g = 0; g < groups.size(); g++) {
            double area = 0;
            for (Triangle triangle : groups.get(g))
                area += triangle.area();
            double up = facings.get(g)[1];
            boolean larger = area > bestArea * (1 + TIE);
            boolean asLarge = area >= bestArea * (1 - TIE);
            if (larger || asLarge && up > bestUp) {
                best = groups.get(g);
                bestArea = area;
                bestUp = up;
            }
        }
        return best;
    }

    /** Least squares P = O + u·A + v·B over the triangles' corners, each weighted by its triangle's area. */
    static Fit fit(List<Triangle> triangles) {
        double[][] m = new double[3][3];
        double[][] rhs = new double[3][3];
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (Triangle triangle : triangles)
            for (double[] c : triangle.corners()) {
                double w = triangle.area();
                double[] basis = { 1, c[3], c[4] };
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++)
                        m[i][j] += w * basis[i] * basis[j];
                    for (int axis = 0; axis < 3; axis++)
                        rhs[axis][i] += w * basis[i] * c[axis];
                }
                u0 = Math.min(u0, (float) c[3]);
                v0 = Math.min(v0, (float) c[4]);
                u1 = Math.max(u1, (float) c[3]);
                v1 = Math.max(v1, (float) c[4]);
            }
        double[] frame = new double[9];
        for (int axis = 0; axis < 3; axis++) {
            double[] solved = solve(m, rhs[axis]);
            // Every corner at one UV, or all on one line of it: no plane is named
            if (solved == null)
                return null;
            frame[axis] = solved[0];
            frame[3 + axis] = solved[1];
            frame[6 + axis] = solved[2];
        }
        double residual = 0;
        for (Triangle triangle : triangles)
            for (double[] c : triangle.corners()) {
                double dx = frame[0] + c[3] * frame[3] + c[4] * frame[6] - c[0];
                double dy = frame[1] + c[3] * frame[4] + c[4] * frame[7] - c[1];
                double dz = frame[2] + c[3] * frame[5] + c[4] * frame[8] - c[2];
                residual = Math.max(residual, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        return new Fit(new float[] { u0, v0, u1, v1 }, frame, residual);
    }

    /** {@code m x = b} for a symmetric 3x3 by Cramer's rule, or null when {@code m} is (nearly) singular. */
    private static double[] solve(double[][] m, double[] b) {
        double det = det(m[0][0], m[0][1], m[0][2], m[1][0], m[1][1], m[1][2], m[2][0], m[2][1], m[2][2]);
        double scale = 0;
        for (double[] row : m)
            for (double value : row)
                scale = Math.max(scale, Math.abs(value));
        if (!(Math.abs(det) > 1e-12 * scale * scale * scale))
            return null;
        double x = det(b[0], m[0][1], m[0][2], b[1], m[1][1], m[1][2], b[2], m[2][1], m[2][2]) / det;
        double y = det(m[0][0], b[0], m[0][2], m[1][0], b[1], m[1][2], m[2][0], b[2], m[2][2]) / det;
        double z = det(m[0][0], m[0][1], b[0], m[1][0], m[1][1], b[1], m[2][0], m[2][1], b[2]) / det;
        return new double[] { x, y, z };
    }

    private static double det(double a, double b, double c, double d, double e, double f, double g, double h,
            double i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    // ------------------------------------------------------------ lines

    private static String name(String[] line) {
        return line.length < 2 ? DEFAULT : String.join(" ", Arrays.copyOfRange(line, 1, line.length));
    }

    private static double number(String[] line, int index) {
        if (index >= line.length)
            return 0;
        try {
            return Double.parseDouble(line[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The next logical line, split on whitespace, skipping blank and comment-only ones;
     * null at the end. The same rules as {@code ObjUvBounds}: a {@code #} starts a
     * comment and a trailing backslash continues the line.
     */
    private static String[] next(BufferedReader reader) throws IOException {
        StringBuilder logical = new StringBuilder();
        String raw;
        while ((raw = reader.readLine()) != null) {
            int hash = raw.indexOf('#');
            if (hash >= 0)
                raw = raw.substring(0, hash);
            raw = raw.trim();
            if (raw.endsWith("\\")) {
                logical.append(raw, 0, raw.length() - 1).append(' ');
                continue;
            }
            logical.append(raw);
            String line = logical.toString().trim();
            logical.setLength(0);
            if (!line.isEmpty())
                return line.split("\\s+");
        }
        String tail = logical.toString().trim();
        return tail.isEmpty() ? null : tail.split("\\s+");
    }
}
