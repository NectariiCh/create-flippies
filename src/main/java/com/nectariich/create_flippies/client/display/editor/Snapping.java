package com.nectariich.create_flippies.client.display.editor;

/**
 * Where the display editor's glass puts things a pointer drags: the arithmetic of
 * snapping, kept apart from the mouse so it can be tested on its own.
 *
 * Everything is in the viewport's tenths of a pixel. A coordinate snaps to a grid
 * (whole pixels, ten tenths, or single tenths while Alt is held) unless it is within
 * a threshold of one of a few candidate lines (the viewport's edges and centre lines),
 * which win: lining something up with the middle of the display matters more than
 * the pixel it lands on. The threshold is given in tenths by the caller, who knows how
 * many tenths a few screen pixels are at the current zoom.
 */
public final class Snapping {

    /** What {@link Snap#line} holds when the grid won rather than a line. */
    public static final int NO_LINE = Integer.MIN_VALUE;

    /**
     * A snapped coordinate, and the candidate line it was pulled to ({@link #NO_LINE}
     * when it fell to the grid instead), so the glass can draw a guide along it.
     */
    public record Snap(int value, int line) {
        public boolean onLine() {
            return line != NO_LINE;
        }
    }

    private Snapping() {}

    /** The candidate lines across one side of a viewport that long: both edges and the centre. */
    public static int[] lines(int extent) {
        return new int[] { 0, extent / 2, extent };
    }

    /** The nearest multiple of {@code step} (at least one tenth). */
    public static int grid(double value, int step) {
        int s = Math.max(1, step);
        return (int) Math.round(value / s) * s;
    }

    /** One coordinate: to the nearest line within {@code threshold}, else to the grid. */
    public static Snap point(double value, int step, int[] lines, double threshold) {
        int best = NO_LINE;
        double bestDistance = Double.MAX_VALUE;
        for (int line : lines) {
            double distance = Math.abs(value - line);
            if (distance <= threshold && distance < bestDistance) {
                best = line;
                bestDistance = distance;
            }
        }
        return best != NO_LINE ? new Snap(best, best) : new Snap(grid(value, step), NO_LINE);
    }

    /**
     * Where a span {@code size} long that a drag has put at {@code start} goes: its
     * start, centre or end onto whichever line is nearest one of them within the
     * threshold, or else its start onto the grid. Returns the new start.
     */
    public static Snap span(double start, int size, int step, int[] lines, double threshold) {
        double[] offsets = { 0, size / 2.0, size };
        int best = NO_LINE;
        double bestStart = 0, bestDistance = Double.MAX_VALUE;
        for (int line : lines) {
            for (double offset : offsets) {
                double distance = Math.abs(start + offset - line);
                if (distance <= threshold && distance < bestDistance) {
                    best = line;
                    bestDistance = distance;
                    bestStart = line - offset;
                }
            }
        }
        return best != NO_LINE ? new Snap((int) Math.round(bestStart), best) : new Snap(grid(start, step), NO_LINE);
    }

    /**
     * A vector turned to the nearest multiple of 45 degrees, as long as its projection
     * onto that direction (so the pointer stays as close as it can to where it is).
     */
    public static double[] diagonal(double dx, double dy) {
        if (dx == 0 && dy == 0)
            return new double[] { 0, 0 };
        double step = Math.PI / 4;
        double angle = Math.round(Math.atan2(dy, dx) / step) * step;
        double ux = Math.cos(angle), uy = Math.sin(angle);
        double length = dx * ux + dy * uy;
        // Clean zeros on the axes, so a level line is exactly level
        return new double[] { Math.abs(ux) < 1e-9 ? 0 : ux * length, Math.abs(uy) < 1e-9 ? 0 : uy * length };
    }

    /**
     * A signed width and height grown until they have that aspect (width over height):
     * whichever side is short for it is lengthened, the signs kept, so a corner dragged
     * either way stays on its side of the anchor. A zero side takes the sign of plus.
     */
    public static double[] keepAspect(double w, double h, double aspect) {
        if (!(aspect > 0) || !Double.isFinite(aspect))
            return new double[] { w, h };
        double aw = Math.abs(w), ah = Math.abs(h);
        if (aw >= ah * aspect)
            ah = aw / aspect;
        else
            aw = ah * aspect;
        return new double[] { Math.copySign(aw, w == 0 ? 1 : w), Math.copySign(ah, h == 0 ? 1 : h) };
    }

    /**
     * The largest fraction (at most 1) of the vector (dx, dy) that, starting at
     * (ax, ay), stays inside 0 to {@code width} and 0 to {@code height}; for keeping a
     * 45 degree line inside the viewport without bending it.
     */
    public static double fitFraction(double ax, double ay, double dx, double dy, double width, double height) {
        double t = 1;
        if (dx > 0)
            t = Math.min(t, (width - ax) / dx);
        else if (dx < 0)
            t = Math.min(t, -ax / dx);
        if (dy > 0)
            t = Math.min(t, (height - ay) / dy);
        else if (dy < 0)
            t = Math.min(t, -ay / dy);
        return Math.max(0, t);
    }

    /** A value held between {@code min} and {@code max} (min wins when they cross). */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
