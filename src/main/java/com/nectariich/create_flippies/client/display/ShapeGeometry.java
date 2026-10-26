package com.nectariich.create_flippies.client.display;

/**
 * The outlines the display renderer fills: rounded rectangles and ellipses as convex
 * polygons, and the slab of one cut between two levels of an affine function, which
 * is how a linear gradient is drawn exactly (within a slab the colour is linear, so
 * blending between the slab's vertices is the gradient itself).
 *
 * Polygons are flat arrays of x and y, in order round the outline. Plain arithmetic,
 * with nothing of the game's, so it can be checked on its own.
 */
public final class ShapeGeometry {

    private ShapeGeometry() {}

    /**
     * How many straight pieces a quarter circle of that radius, in pixels, is drawn
     * with: enough to look round on a panel at arm's length, few enough that a wall
     * of rounded boxes costs nothing.
     */
    public static int cornerSegments(float radius) {
        return Math.max(2, Math.min(12, (int) Math.ceil(radius * 0.75f) + 1));
    }

    /** How many pieces a whole ellipse of those radii, in pixels, is drawn with. */
    public static int ellipseSegments(float rx, float ry) {
        double perimeter = Math.PI * (rx + ry);
        return Math.max(16, Math.min(128, (int) Math.ceil(perimeter / 1.5)));
    }

    /**
     * A rectangle with its corners rounded to {@code radius}, each corner
     * {@code segments} pieces long whatever the radius, so two of them with the same
     * count (a frame's outside and inside) have their vertices in step. A radius of
     * nothing gives every corner's points at the corner itself, which draws as a
     * plain rectangle.
     */
    public static float[] roundedRect(float x, float y, float w, float h, float radius, int segments) {
        float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        float[] out = new float[4 * (segments + 1) * 2];
        int at = 0;
        // Corner centres clockwise from the top left, on screen with y down, and the
        // angle each corner's arc starts at
        float[][] centres = { { x + r, y + r }, { x + w - r, y + r }, { x + w - r, y + h - r }, { x + r, y + h - r } };
        double[] starts = { Math.PI, 1.5 * Math.PI, 0, 0.5 * Math.PI };
        for (int corner = 0; corner < 4; corner++)
            for (int i = 0; i <= segments; i++) {
                double angle = starts[corner] + 0.5 * Math.PI * i / segments;
                out[at++] = centres[corner][0] + (float) (Math.cos(angle) * r);
                out[at++] = centres[corner][1] + (float) (Math.sin(angle) * r);
            }
        return out;
    }

    /** An ellipse round (cx, cy) with those radii, in {@code segments} pieces. */
    public static float[] ellipse(float cx, float cy, float rx, float ry, int segments) {
        float[] out = new float[segments * 2];
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            out[2 * i] = cx + (float) (Math.cos(angle) * rx);
            out[2 * i + 1] = cy + (float) (Math.sin(angle) * ry);
        }
        return out;
    }

    /**
     * The part of a convex polygon where {@code a x + b y + c} lies between
     * {@code low} and {@code high}: a convex polygon again, perhaps with no points.
     */
    public static float[] slab(float[] polygon, double a, double b, double c, double low, double high) {
        float[] above = clip(polygon, a, b, c - low);
        return clip(above, -a, -b, high - c);
    }

    /** The part of a convex polygon where {@code a x + b y + c >= 0} (Sutherland and Hodgman). */
    static float[] clip(float[] polygon, double a, double b, double c) {
        int n = polygon.length / 2;
        if (n == 0)
            return polygon;
        float[] out = new float[polygon.length * 2];
        int at = 0;
        for (int i = 0; i < n; i++) {
            float px = polygon[2 * i], py = polygon[2 * i + 1];
            float qx = polygon[2 * ((i + 1) % n)], qy = polygon[2 * ((i + 1) % n) + 1];
            double p = a * px + b * py + c, q = a * qx + b * qy + c;
            if (p >= 0) {
                out[at++] = px;
                out[at++] = py;
            }
            if (p >= 0 != q >= 0) {
                double t = p / (p - q);
                out[at++] = (float) (px + (qx - px) * t);
                out[at++] = (float) (py + (qy - py) * t);
            }
        }
        float[] trimmed = new float[at];
        System.arraycopy(out, 0, trimmed, 0, at);
        return trimmed;
    }

    /** The mean of a polygon's points, inside it when it is convex. */
    public static float[] centroid(float[] polygon) {
        float x = 0, y = 0;
        int n = polygon.length / 2;
        for (int i = 0; i < n; i++) {
            x += polygon[2 * i];
            y += polygon[2 * i + 1];
        }
        return n == 0 ? new float[] { 0, 0 } : new float[] { x / n, y / n };
    }
}
