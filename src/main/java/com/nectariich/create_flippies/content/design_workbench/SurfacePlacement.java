package com.nectariich.create_flippies.content.design_workbench;

import net.minecraft.util.Mth;

/**
 * How something drawn over a submesh sits on it: the rectangle of the submesh's own
 * UV space it is stretched over, and where within that rectangle it is moved,
 * scaled, turned and mirrored to. Shared by the two things a design draws over a
 * submesh, a gauge's printed face
 * ({@link com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial})
 * and a screen's viewport
 * ({@link com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface}),
 * so the workbench sets both with the same controls and both read the same numbers
 * the same way.
 *
 * A UV rectangle is {u0, v0, u1, v1}, its top the smallest V as an image is read
 * (the OBJ's V flipped, which Hypergraphical does on import). A placement is
 * {offsetX, offsetY, scaleX, scaleY, rotation}: the offsets are fractions of the
 * rectangle, right and up; a scale of 2 draws the thing twice as large about the
 * rectangle's centre; the rotation is in degrees clockwise about the thing's own
 * centre.
 *
 * Pure and safe on both sides; everything here holds whatever a client sent to
 * something a material or a renderer can use.
 */
public final class SurfacePlacement {

    /** The least a placement may shrink what it places to, so it is never scaled to nothing. */
    public static final float MIN_SCALE = 0.001f;

    /** No placement at all: centred, the rectangle's own size, unturned. */
    public static final float[] IDENTITY = { 0, 0, 1, 1, 0 };

    private SurfacePlacement() {}

    /**
     * A UV rectangle held to something a material can be made from: finite, ordered,
     * not degenerate, and within a few texture squares of the origin. The whole
     * square when there is nothing usable.
     */
    public static float[] sanitiseUv(float[] uv) {
        if (uv == null || uv.length != 4)
            return new float[] { 0, 0, 1, 1 };
        float u0 = clampUv(Math.min(uv[0], uv[2])), u1 = clampUv(Math.max(uv[0], uv[2]));
        float v0 = clampUv(Math.min(uv[1], uv[3])), v1 = clampUv(Math.max(uv[1], uv[3]));
        if (!(u1 - u0 >= 1e-4f) || !(v1 - v0 >= 1e-4f))
            return new float[] { 0, 0, 1, 1 };
        return new float[] { u0, v0, u1, v1 };
    }

    /**
     * A placement held to something sensible: any finite offset, scales above nothing
     * however large, and a turn of at most one either way, nothing
     * at all for anything unusable.
     */
    public static float[] sanitisePlacement(float[] placement) {
        if (placement == null || placement.length != 5)
            return IDENTITY.clone();
        float[] held = new float[5];
        for (int i = 0; i < 5; i++) {
            float value = placement[i];
            boolean scale = i == 2 || i == 3;
            if (!Float.isFinite(value))
                held[i] = scale ? 1 : 0;
            else if (scale)
                held[i] = Math.max(value, MIN_SCALE);
            else
                held[i] = i == 4 ? Mth.clamp(value, -360, 360) : value;
        }
        return held;
    }

    /**
     * The material UV transform that stretches a texture over {@code uv}, then moves
     * and scales it there by {@code placement}, mirroring it about its own centre
     * when it is flipped (a negative tiling): the shader samples
     * {@code uv * tiling + offset}, so with no placement the rectangle's corners land
     * on the texture's. V runs down the image, so moving the face up samples further
     * down. Returned as {tilingU, tilingV, offsetU, offsetV}. The rotation is not in
     * it: a material can only be moved and scaled, so a gauge bakes its turn into the
     * texture.
     */
    public static float[] uvTransform(float[] uv, float[] placement, boolean flipX, boolean flipY) {
        float du = uv[2] - uv[0], dv = uv[3] - uv[1];
        float dx = placement[0], dy = placement[1];
        float sx = flipX ? -placement[2] : placement[2], sy = flipY ? -placement[3] : placement[3];
        return new float[] { 1 / (du * sx), 1 / (dv * sy), (-uv[0] / du - 0.5f - dx) / sx + 0.5f,
                (-uv[1] / dv - 0.5f + dy) / sy + 0.5f };
    }

    /** NaN stays NaN, so the rectangle it is in is refused. */
    private static float clampUv(float value) {
        return Float.isNaN(value) ? value : Mth.clamp(value, -64, 64);
    }
}
