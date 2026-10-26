package com.nectariich.create_flippies.content.crt;

import net.minecraft.world.phys.Vec3;

/**
 * Where everything on the CRT is, from the model `crt-1.json` (spec 11.2.14), in
 * model pixels as authored.
 *
 * The screen is on the model's +x side and the two ports on its -x side, so the
 * front is +x; {@link CrtEntity} turns the model so that side faces the way it was
 * placed.
 */
public final class CrtGeometry {

    /** The data port and its index: one VGA input. */
    public static final int VGA_IN = 0, PORTS = 1;

    /** The cabinet and the frame round the screen, as one box. */
    static final double[] BODY = {0.5, 0, 1, 12, 12, 15};
    /** The `screen` cube; its +x face (x = 11.75) shows the picture. */
    public static final double[] SCREEN = {11, 1.5, 2.5, 11.75, 10.5, 13.5};
    /** The `vga in` and `power in` cubes, on the back (their -x faces at x = 0, the cell's edge). */
    static final double[] VGA_PORT = {0, 0.5, 1.5, 0.5, 1.5, 3.25};
    static final double[] SOCKET = {0, 0.5, 4, 0.5, 1.5, 5.75};
    /** The plug synthesised behind the socket (tools/gen_crt.py), drawn while a cord is in. */
    static final double[] POWER_PLUG = {-1.25, 0.75, 4.25, 0, 1.25, 5.5};
    /** Where Power Grid is told the socket terminals are: inside the cabinet (as the case's). */
    public static final double[] HIDDEN_TERMINAL = {5.75, 5.75, 7.75, 6.25, 6.25, 8.25};

    private CrtGeometry() {}

    /** The middle of a back cube's outer (-x) face, px. */
    static Vec3 backFaceCentre(double[] b) {
        return new Vec3(b[0], (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    /** Whether a model point, px, is on the socket (with a little slack for a point on its face). */
    static boolean onSocket(Vec3 p) {
        return inside(SOCKET, p);
    }

    /** Whether a model point, px, is on the VGA port. */
    static boolean onVgaPort(Vec3 p) {
        return inside(VGA_PORT, p);
    }

    private static boolean inside(double[] b, Vec3 p) {
        double e = 1e-2;
        return p.x >= b[0] - e && p.x <= b[3] + e && p.y >= b[1] - e && p.y <= b[4] + e && p.z >= b[2] - e
                && p.z <= b[5] + e;
    }
}
