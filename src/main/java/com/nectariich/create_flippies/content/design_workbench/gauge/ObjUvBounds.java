package com.nectariich.create_flippies.content.design_workbench.gauge;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The bounds of one submesh's texture coordinates, read straight from its
 * {@code .obj}: what a gauge's printed face is stretched over by default
 * ({@link GaugeDial#uv}), so the whole face shows on a submesh whose UVs fill only
 * part of the texture square.
 *
 * Hypergraphical keeps no UVs it can be asked for, so this reads the file the way
 * its OBJ reader does, to name the same submeshes: {@code o} starts a part, a
 * {@code g} starts one only in a file without {@code o}, both named by the rest of
 * the line; {@code usemtl} names the material by its first word; both default to
 * {@code default}. A {@code #} starts a comment and a trailing backslash continues
 * the line. V is flipped, as Hypergraphical flips it on import, so the bounds are in
 * the space the material's UV transform works in. A corner with no texture
 * coordinate counts as (0, 0), which is what Hypergraphical gives it.
 */
public final class ObjUvBounds {

    private static final String DEFAULT = "default";

    private ObjUvBounds() {}

    /**
     * The UV bounds of the faces of {@code part} drawn with {@code material}, as
     * {u0, v0, u1, v1} with V flipped, or null when the file has no such faces.
     */
    public static float[] of(byte[] obj, String part, String material) throws IOException {
        List<float[]> uvs = new ArrayList<>();
        String currentPart = DEFAULT, currentMaterial = DEFAULT;
        boolean sawObject = false;
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        boolean any = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(obj), StandardCharsets.UTF_8))) {
            String[] line;
            while ((line = next(reader)) != null) {
                switch (line[0]) {
                case "vt" -> {
                    float u = number(line, 1), v = number(line, 2);
                    uvs.add(new float[] { u, 1 - v });
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
                    for (int i = 1; i < line.length; i++) {
                        float[] uv = uv(line[i], uvs);
                        u0 = Math.min(u0, uv[0]);
                        v0 = Math.min(v0, uv[1]);
                        u1 = Math.max(u1, uv[0]);
                        v1 = Math.max(v1, uv[1]);
                        any = true;
                    }
                }
                default -> {
                }
                }
            }
        }
        return any ? new float[] { u0, v0, u1, v1 } : null;
    }

    /** The texture coordinate of one {@code v/vt/vn} corner, (0, 0) when it has none or names none. */
    private static float[] uv(String corner, List<float[]> uvs) {
        String[] fields = corner.split("/", -1);
        if (fields.length < 2 || fields[1].isEmpty())
            return new float[] { 0, 0 };
        try {
            int index = Integer.parseInt(fields[1].trim());
            index = index < 0 ? uvs.size() + index : index - 1;
            return index >= 0 && index < uvs.size() ? uvs.get(index) : new float[] { 0, 0 };
        } catch (NumberFormatException e) {
            return new float[] { 0, 0 };
        }
    }

    private static String name(String[] line) {
        return line.length < 2 ? DEFAULT : String.join(" ", Arrays.copyOfRange(line, 1, line.length));
    }

    private static float number(String[] line, int index) {
        if (index >= line.length)
            return 0;
        try {
            return Float.parseFloat(line[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The next logical line, split on whitespace, skipping blank and comment-only ones; null at the end. */
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
