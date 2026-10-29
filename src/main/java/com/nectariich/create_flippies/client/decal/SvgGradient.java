package com.nectariich.create_flippies.client.decal;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

/**
 * One SVG gradient as it paints one shape: where each point of the drawing falls along
 * it, and the colour there, stop opacity included.
 *
 * Built by the importer once the gradient's attributes (its own and those it inherits
 * through {@code href}), its units and its transform have been resolved, so that
 * {@link #toGradient} takes a point of the flattened drawing (the space the importer's
 * areas are in) straight into the gradient's own space: the unit box of the shape's
 * bounds for {@code objectBoundingBox}, the shape's user space otherwise, either way
 * with {@code gradientTransform} undone.
 *
 * A radial gradient is measured from its focal point, as SVG does: a point's place is
 * how far along the ray from the focus through it the point is, as a fraction of the
 * way to the circle. A focus outside the circle is moved onto it, as SVG 1.1 says.
 *
 * Runs on any thread and touches nothing of the game's.
 */
record SvgGradient(boolean radial, double x1, double y1, double x2, double y2, double r, double fx, double fy,
        int spread, double[] offsets, int[] colours, AffineTransform toGradient) {

    static final int PAD = 0, REFLECT = 1, REPEAT = 2;

    /** The colour at a point of the flattened drawing. */
    int colourAt(double x, double y) {
        Point2D p = toGradient.transform(new Point2D.Double(x, y), null);
        return colourAlong(spread(radial ? radialT(p.getX(), p.getY()) : linearT(p.getX(), p.getY())));
    }

    /** The plain average of the stops, which is how a gradient is painted where it cannot be shaded. */
    int average() {
        long a = 0, r = 0, g = 0, b = 0;
        for (int colour : colours) {
            a += colour >>> 24;
            r += (colour >> 16) & 0xFF;
            g += (colour >> 8) & 0xFF;
            b += colour & 0xFF;
        }
        int n = Math.max(1, colours.length);
        return (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private double linearT(double x, double y) {
        double dx = x2 - x1, dy = y2 - y1;
        double length = dx * dx + dy * dy;
        return length == 0 ? 1 : ((x - x1) * dx + (y - y1) * dy) / length;
    }

    /** x1 and y1 are the centre here. */
    private double radialT(double x, double y) {
        if (r <= 0)
            return 1;
        double focusX = fx, focusY = fy;
        double fromCentre = Math.hypot(focusX - x1, focusY - y1);
        if (fromCentre > r * 0.999) {
            double scale = r * 0.999 / fromCentre;
            focusX = x1 + (focusX - x1) * scale;
            focusY = y1 + (focusY - y1) * scale;
        }
        double dx = x - focusX, dy = y - focusY;
        double a = dx * dx + dy * dy;
        if (a == 0)
            return 0;
        double ox = focusX - x1, oy = focusY - y1;
        double b = 2 * (dx * ox + dy * oy);
        double c = ox * ox + oy * oy - r * r;
        double s = (-b + Math.sqrt(Math.max(0, b * b - 4 * a * c))) / (2 * a);
        return s <= 0 ? 1 : 1 / s;
    }

    private double spread(double t) {
        return switch (spread) {
            case REFLECT -> {
                double m = t % 2;
                if (m < 0)
                    m += 2;
                yield m > 1 ? 2 - m : m;
            }
            case REPEAT -> t - Math.floor(t);
            default -> t < 0 ? 0 : Math.min(t, 1);
        };
    }

    private int colourAlong(double t) {
        if (t <= offsets[0])
            return colours[0];
        for (int i = 1; i < offsets.length; i++)
            if (t <= offsets[i]) {
                double span = offsets[i] - offsets[i - 1];
                return span <= 0 ? colours[i] : lerp(colours[i - 1], colours[i], (t - offsets[i - 1]) / span);
            }
        return colours[colours.length - 1];
    }

    private static int lerp(int from, int to, double t) {
        int a = (int) Math.round((from >>> 24) + ((to >>> 24) - (from >>> 24)) * t);
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }
}
