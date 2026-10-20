package com.nectariich.create_flippies.client.decal;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * SVG path data and number lists, read into Java2D paths.
 *
 * The grammar is the one the SVG specification gives, including the parts real
 * exporters lean on: numbers run together wherever a sign or a second decimal
 * point makes the break unambiguous ({@code 1.5.5} is two numbers, {@code 3-4} is
 * two), a command letter may be left out after its first use, and the flags of an
 * arc may be written with no separator at all. A path that goes wrong partway is
 * kept up to the last command that made sense, which is what browsers do.
 */
final class SvgPath {

    private final String text;
    private int at;

    private SvgPath(String text) {
        this.text = text;
    }

    /** Every number in a list such as {@code points} or {@code viewBox}. */
    static double[] numbers(String text) {
        if (text == null)
            return new double[0];
        SvgPath reader = new SvgPath(text);
        List<Double> found = new ArrayList<>();
        while (true) {
            reader.skipSeparators();
            if (reader.at >= text.length())
                break;
            Double number = reader.number();
            if (number == null)
                break;
            found.add(number);
        }
        double[] out = new double[found.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = found.get(i);
        return out;
    }

    /** The path a {@code d} attribute describes. */
    static Path2D.Double parse(String d) {
        Path2D.Double path = new Path2D.Double();
        if (d == null)
            return path;
        new SvgPath(d).read(path);
        return path;
    }

    private void read(Path2D.Double path) {
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        // The reflected control point S and T take from the previous segment
        double controlX = 0;
        double controlY = 0;
        char previous = 0;
        char command = 0;
        boolean open = false;

        while (true) {
            skipSeparators();
            if (at >= text.length())
                return;
            char c = text.charAt(at);
            if (Character.isLetter(c)) {
                command = c;
                at++;
            } else if (command == 0 || command == 'Z' || command == 'z') {
                // Numbers after a closepath have nothing to belong to
                return;
            } else if (command == 'M') {
                // Coordinates after a moveto are implied linetos
                command = 'L';
            } else if (command == 'm') {
                command = 'l';
            }

            boolean relative = Character.isLowerCase(command);
            double baseX = relative ? x : 0;
            double baseY = relative ? y : 0;
            switch (Character.toUpperCase(command)) {
                case 'M' -> {
                    double[] p = read(2);
                    if (p == null)
                        return;
                    x = baseX + p[0];
                    y = baseY + p[1];
                    path.moveTo(x, y);
                    open = true;
                    startX = x;
                    startY = y;
                }
                case 'L' -> {
                    double[] p = read(2);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    x = baseX + p[0];
                    y = baseY + p[1];
                    path.lineTo(x, y);
                }
                case 'H' -> {
                    double[] p = read(1);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    x = baseX + p[0];
                    path.lineTo(x, y);
                }
                case 'V' -> {
                    double[] p = read(1);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    y = baseY + p[0];
                    path.lineTo(x, y);
                }
                case 'C' -> {
                    double[] p = read(6);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    path.curveTo(baseX + p[0], baseY + p[1], baseX + p[2], baseY + p[3], baseX + p[4], baseY + p[5]);
                    controlX = baseX + p[2];
                    controlY = baseY + p[3];
                    x = baseX + p[4];
                    y = baseY + p[5];
                }
                case 'S' -> {
                    double[] p = read(4);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    boolean follows = "CcSs".indexOf(previous) >= 0;
                    double firstX = follows ? 2 * x - controlX : x;
                    double firstY = follows ? 2 * y - controlY : y;
                    path.curveTo(firstX, firstY, baseX + p[0], baseY + p[1], baseX + p[2], baseY + p[3]);
                    controlX = baseX + p[0];
                    controlY = baseY + p[1];
                    x = baseX + p[2];
                    y = baseY + p[3];
                }
                case 'Q' -> {
                    double[] p = read(4);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    path.quadTo(baseX + p[0], baseY + p[1], baseX + p[2], baseY + p[3]);
                    controlX = baseX + p[0];
                    controlY = baseY + p[1];
                    x = baseX + p[2];
                    y = baseY + p[3];
                }
                case 'T' -> {
                    double[] p = read(2);
                    if (p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    boolean follows = "QqTt".indexOf(previous) >= 0;
                    controlX = follows ? 2 * x - controlX : x;
                    controlY = follows ? 2 * y - controlY : y;
                    x = baseX + p[0];
                    y = baseY + p[1];
                    path.quadTo(controlX, controlY, x, y);
                }
                case 'A' -> {
                    double[] radii = read(3);
                    if (radii == null)
                        return;
                    Boolean large = flag();
                    Boolean sweep = flag();
                    double[] p = read(2);
                    if (large == null || sweep == null || p == null)
                        return;
                    open = ensureOpen(path, open, x, y);
                    double endX = baseX + p[0];
                    double endY = baseY + p[1];
                    arc(path, x, y, radii[0], radii[1], radii[2], large, sweep, endX, endY);
                    x = endX;
                    y = endY;
                }
                case 'Z' -> {
                    if (open)
                        path.closePath();
                    open = false;
                    x = startX;
                    y = startY;
                }
                default -> {
                    return;
                }
            }
            previous = command;
        }
    }

    /**
     * Starts a subpath where the pen is if the last one was closed. A drawing command
     * straight after {@code Z} continues from the closed subpath's start, which Java2D
     * has no notion of without a moveto.
     */
    private static boolean ensureOpen(Path2D.Double path, boolean open, double x, double y) {
        if (!open)
            path.moveTo(x, y);
        return true;
    }

    private double[] read(int count) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            skipSeparators();
            Double number = number();
            if (number == null)
                return null;
            values[i] = number;
        }
        return values;
    }

    /** An arc flag, which is a single digit that need not be separated from what follows. */
    private Boolean flag() {
        skipSeparators();
        if (at >= text.length())
            return null;
        char c = text.charAt(at);
        if (c != '0' && c != '1')
            return null;
        at++;
        return c == '1';
    }

    private void skipSeparators() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ',' || Character.isWhitespace(c))
                at++;
            else
                break;
        }
    }

    private Double number() {
        int start = at;
        int length = text.length();
        if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-'))
            at++;
        boolean digits = false;
        while (at < length && Character.isDigit(text.charAt(at))) {
            at++;
            digits = true;
        }
        if (at < length && text.charAt(at) == '.') {
            at++;
            while (at < length && Character.isDigit(text.charAt(at))) {
                at++;
                digits = true;
            }
        }
        if (!digits) {
            at = start;
            return null;
        }
        if (at < length && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            int mark = at;
            at++;
            if (at < length && (text.charAt(at) == '+' || text.charAt(at) == '-'))
                at++;
            int exponent = at;
            while (at < length && Character.isDigit(text.charAt(at)))
                at++;
            if (at == exponent)
                at = mark;
        }
        try {
            return Double.parseDouble(text.substring(start, at));
        } catch (NumberFormatException e) {
            at = start;
            return null;
        }
    }

    /**
     * An elliptical arc as cubic curves, by the endpoint-to-centre conversion in the
     * SVG specification's implementation notes, a quarter turn or less per curve.
     */
    static void arc(Path2D.Double path, double x0, double y0, double rx, double ry, double rotation, boolean large,
            boolean sweep, double x, double y) {
        if (x0 == x && y0 == y)
            return;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        double phi = Math.toRadians(rotation % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x) / 2;
        double dy = (y0 - y) / 2;
        double x1 = cos * dx + sin * dy;
        double y1 = -sin * dx + cos * dy;

        // Radii too small to reach are scaled up until they just do
        double lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry);
        if (lambda > 1) {
            double scale = Math.sqrt(lambda);
            rx *= scale;
            ry *= scale;
        }
        double numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double coefficient = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, numerator / denominator));
        double cx1 = coefficient * rx * y1 / ry;
        double cy1 = -coefficient * ry * x1 / rx;
        double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2;
        double cy = sin * cx1 + cos * cy1 + (y0 + y) / 2;

        double ux = (x1 - cx1) / rx;
        double uy = (y1 - cy1) / ry;
        double vx = (-x1 - cx1) / rx;
        double vy = (-y1 - cy1) / ry;
        double start = Math.atan2(uy, ux);
        double extent = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && extent > 0)
            extent -= 2 * Math.PI;
        else if (sweep && extent < 0)
            extent += 2 * Math.PI;

        int segments = Math.max(1, (int) Math.ceil(Math.abs(extent) / (Math.PI / 2) - 1e-9));
        double delta = extent / segments;
        double t = 4.0 / 3.0 * Math.tan(delta / 4);
        double angle = start;
        for (int i = 0; i < segments; i++) {
            double cos1 = Math.cos(angle);
            double sin1 = Math.sin(angle);
            double cos2 = Math.cos(angle + delta);
            double sin2 = Math.sin(angle + delta);
            double[] p1 = onEllipse(cos1 - t * sin1, sin1 + t * cos1, cx, cy, rx, ry, cos, sin);
            double[] p2 = onEllipse(cos2 + t * sin2, sin2 - t * cos2, cx, cy, rx, ry, cos, sin);
            double[] p3 = i == segments - 1 ? new double[] { x, y }
                    : onEllipse(cos2, sin2, cx, cy, rx, ry, cos, sin);
            path.curveTo(p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]);
            angle += delta;
        }
    }

    private static double[] onEllipse(double ux, double uy, double cx, double cy, double rx, double ry, double cos,
            double sin) {
        double x = rx * ux;
        double y = ry * uy;
        return new double[] { cx + cos * x - sin * y, cy + sin * x + cos * y };
    }
}
