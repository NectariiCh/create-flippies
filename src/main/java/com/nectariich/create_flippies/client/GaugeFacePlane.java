package com.nectariich.create_flippies.client;

/**
 * Where a gauge face lies in whatever space it is being drawn in: the face's top
 * left corner, the vectors across it and down it (each the face's full width and
 * height), and the unit direction out of it towards the viewer. Points on the face
 * are named by fractions, 0 to 1 across from the left and down from the top, which
 * is how the face definitions and their layouts measure everything.
 *
 * Lets the same face and pointer drawing serve a gauge block, whose model is
 * authored facing north, and a designed gauge, which is authored plate-down.
 */
public record GaugeFacePlane(float[] origin, float[] across, float[] down, float[] out) {

    /** The point {@code u} across and {@code v} down the face, lifted {@code lift} towards the viewer. */
    public float[] point(float u, float v, float lift) {
        return new float[] { origin[0] + across[0] * u + down[0] * v + out[0] * lift,
                origin[1] + across[1] * u + down[1] * v + out[1] * lift,
                origin[2] + across[2] * u + down[2] * v + out[2] * lift };
    }

    /** The face's width, in the units of the space it is in. */
    public float width() {
        return (float) Math.sqrt(across[0] * across[0] + across[1] * across[1] + across[2] * across[2]);
    }

    /** The face's height, likewise. */
    public float height() {
        return (float) Math.sqrt(down[0] * down[0] + down[1] * down[1] + down[2] * down[2]);
    }
}
