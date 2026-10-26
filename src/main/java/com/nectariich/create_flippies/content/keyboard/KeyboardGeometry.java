package com.nectariich.create_flippies.content.keyboard;

import com.nectariich.create_flippies.content.pc.PcCaseGeometry;

/**
 * The keyboard's and the keyboard and mouse's cubes from their models (spec
 * 11.2.12, 11.2.15), px. Pure, so the tests check them against the model files
 * without loading the entities.
 */
public final class KeyboardGeometry {

    /** The body, px. */
    public static final double[] BODY = {0, 0, 0, 7, 1, 16};
    /** The model's `ps/2` cube: the keyboard's port. */
    public static final double[] PORT = {7, 0.25, 0.25, 7.25, 0.75, 0.75};
    /** The keyboard and mouse model's `ps/2 2` cube: the mouse's port. */
    public static final double[] MOUSE_PORT = {7, 0.25, 1.25, 7.25, 0.75, 1.75};

    private KeyboardGeometry() {}

    /** A port's cube grown 0.375 px outward, as the case's ports are. */
    public static double[] portBox(double[] cube) {
        double[] b = cube.clone();
        b[3] += PcCaseGeometry.PORT_GROW;
        return b;
    }
}
