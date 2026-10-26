package com.nectariich.create_flippies.content.display.element;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * How a filled element is painted: one colour (the element's own), a linear or radial
 * gradient across its box, or for a bar a colour picked by its value from a set of
 * bands.
 *
 * <h2>Gradients</h2>
 *
 * A gradient is measured against the element's box, so it stretches with it. A
 * linear one runs at {@link #angle} degrees (0 left to right, 90 top to bottom, the
 * viewport counting y downwards), from where the box's first corner projects onto
 * that direction to where its last one does, so the whole box is always covered from
 * stop to stop. A radial one is centred at ({@link #cx}, {@link #cy}) and reaches
 * {@link #radius} across, all in thousandths of the box (so a wide box gives an
 * elliptical gradient). Past the first and last stop the end colours carry on.
 *
 * <h2>Bands</h2>
 *
 * Only a bar has them. Each is a colour and the levels between which the bar takes
 * it, in the bar's own value units or in percent of its range ({@link #percent}).
 * They are kept sorted and never overlapping, so the bar's colour is a function of
 * its value: inside a band its colour, between two bands that do not touch a fade
 * from the lower one's colour to the upper one's across the gap. The first band's
 * lower level and the last one's upper level are not used: the first band reaches
 * down to the bar's minimum and the last up to its maximum, whatever they say.
 *
 * Both lists are kept whatever the type, so switching a fill from a gradient to solid
 * and back loses nothing. Everything is sanitised on construction: at most
 * {@link #MAX_STOPS} of each, positions held to the box, stops in order.
 */
public record ElementPaint(Type type, int angle, int cx, int cy, int radius, List<Stop> stops, List<Band> bands,
        boolean percent) {

    public enum Type {
        SOLID, LINEAR, RADIAL, BANDS;

        static Type byOrdinal(int ordinal) {
            Type[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : SOLID;
        }
    }

    /** One colour of a gradient and where along it it sits, in thousandths. */
    public record Stop(int colour, int at) {
        public Stop {
            colour |= 0xFF000000;
            at = Mth.clamp(at, 0, ONE);
        }
    }

    /** One colour of a bar and the levels it covers. */
    public record Band(int colour, float lower, float upper) {
        public Band {
            colour |= 0xFF000000;
            lower = finite(lower);
            upper = Math.max(lower, finite(upper));
        }
    }

    /** The most stops a gradient holds, and the most bands a bar holds. */
    public static final int MAX_STOPS = 8;

    /** A fraction of the box, as gradients store them: one thousand thousandths. */
    public static final int ONE = 1000;

    /** Levels as large as this are far past anything a link sends and far inside what a float holds exactly enough. */
    private static final float MAX_LEVEL = 1e9f;

    public static final ElementPaint SOLID = new ElementPaint(Type.SOLID, 0, ONE / 2, ONE / 2, ONE / 2, List.of(),
            List.of(), false);

    public ElementPaint {
        type = type == null ? Type.SOLID : type;
        angle = Math.floorMod(angle, 360);
        cx = Mth.clamp(cx, 0, ONE);
        cy = Mth.clamp(cy, 0, ONE);
        radius = Mth.clamp(radius, 1, 4 * ONE);
        stops = sortedStops(stops);
        bands = separatedBands(bands);
    }

    /** This paint as a shape that is not a bar may have it: bands are a bar's alone. */
    public ElementPaint forShape() {
        return type == Type.BANDS ? withType(Type.SOLID) : this;
    }

    public ElementPaint withType(Type type) {
        return new ElementPaint(type, angle, cx, cy, radius, stops, bands, percent);
    }

    public ElementPaint withStops(List<Stop> stops) {
        return new ElementPaint(type, angle, cx, cy, radius, stops, bands, percent);
    }

    public ElementPaint withBands(List<Band> bands) {
        return new ElementPaint(type, angle, cx, cy, radius, stops, bands, percent);
    }

    public ElementPaint withAngle(int angle) {
        return new ElementPaint(type, angle, cx, cy, radius, stops, bands, percent);
    }

    public ElementPaint withCentre(int cx, int cy, int radius) {
        return new ElementPaint(type, angle, cx, cy, radius, stops, bands, percent);
    }

    public ElementPaint withPercent(boolean percent) {
        return new ElementPaint(type, angle, cx, cy, radius, stops, bands, percent);
    }

    /** Whether this paints a gradient, which is drawn with a colour at every vertex. */
    public boolean gradient() {
        return (type == Type.LINEAR || type == Type.RADIAL) && !stops.isEmpty();
    }

    /**
     * Where a point of the box lies along the gradient, 0 to 1 and past either end;
     * {@code u} and {@code v} are fractions of the box, {@code aspect} its width over
     * its height (a linear gradient's angle is an angle on the glass, not in the
     * stretched unit box).
     */
    public double position(double u, double v, double aspect) {
        if (type == Type.RADIAL) {
            double r = radius / (double) ONE;
            double dx = (u - cx / (double) ONE) / r, dy = (v - cy / (double) ONE) / r;
            return Math.sqrt(dx * dx + dy * dy);
        }
        double radians = Math.toRadians(angle);
        double dx = Math.cos(radians), dy = Math.sin(radians);
        // In glass units, the box aspect across and one down: the gradient runs from
        // the corner lowest along the direction to the highest
        double low = Math.min(0, dx * aspect) + Math.min(0, dy);
        double high = Math.max(0, dx * aspect) + Math.max(0, dy);
        return high <= low ? 0 : (u * aspect * dx + v * dy - low) / (high - low);
    }

    /** The gradient's colour at a position along it, past the ends the end colours. */
    public int colourAt(double position) {
        if (stops.isEmpty())
            return 0xFFFFFFFF;
        double at = position * ONE;
        Stop first = stops.get(0);
        if (at <= first.at())
            return first.colour();
        for (int i = 1; i < stops.size(); i++) {
            Stop next = stops.get(i);
            if (at <= next.at()) {
                Stop previous = stops.get(i - 1);
                int span = next.at() - previous.at();
                return span <= 0 ? next.colour() : lerp(previous.colour(), next.colour(),
                        (float) ((at - previous.at()) / span));
            }
        }
        return stops.get(stops.size() - 1).colour();
    }

    /**
     * A bar's colour at {@code value} on a range from {@code min} to {@code max}; see
     * the class comment. {@code fallback} when there are no bands.
     */
    public int bandColour(double value, double min, double max, int fallback) {
        if (bands.isEmpty())
            return fallback;
        // Everything in percent of the range when the bands are, so a band's levels
        // and the value are compared in the same units
        double level = percent ? (max == min ? 0 : (value - min) / (max - min) * 100) : value;
        for (int i = 0; i < bands.size(); i++) {
            Band band = bands.get(i);
            boolean last = i == bands.size() - 1;
            if (last || level <= band.upper()) {
                if (i == 0 || level >= band.lower())
                    return band.colour();
                // Between the band below and this one
                Band below = bands.get(i - 1);
                double gap = band.lower() - below.upper();
                return gap <= 0 ? band.colour()
                        : lerp(below.colour(), band.colour(), (float) ((level - below.upper()) / gap));
            }
        }
        return bands.get(bands.size() - 1).colour();
    }

    public static int lerp(int from, int to, float t) {
        t = Mth.clamp(t, 0, 1);
        int a = Math.round(Mth.lerp(t, from >>> 24, to >>> 24));
        int r = Math.round(Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF));
        int g = Math.round(Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF));
        int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** At most {@link #MAX_STOPS}, in order of position. */
    private static List<Stop> sortedStops(List<Stop> stops) {
        if (stops == null || stops.isEmpty())
            return List.of();
        List<Stop> sorted = new ArrayList<>(stops.subList(0, Math.min(MAX_STOPS, stops.size())));
        sorted.removeIf(stop -> stop == null);
        sorted.sort((a, b) -> Integer.compare(a.at(), b.at()));
        return List.copyOf(sorted);
    }

    /**
     * At most {@link #MAX_STOPS}, each starting no lower than the one before it ends,
     * so the bands are in order and never overlap. A band that would have to start
     * above where it ends is pushed up whole.
     */
    private static List<Band> separatedBands(List<Band> bands) {
        if (bands == null || bands.isEmpty())
            return List.of();
        List<Band> out = new ArrayList<>();
        float floor = -Float.MAX_VALUE;
        for (Band band : bands.subList(0, Math.min(MAX_STOPS, bands.size()))) {
            if (band == null)
                continue;
            float lower = Math.max(band.lower(), floor);
            float upper = Math.max(band.upper(), lower);
            out.add(new Band(band.colour(), lower, upper));
            floor = upper;
        }
        return List.copyOf(out);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? Mth.clamp(value, -MAX_LEVEL, MAX_LEVEL) : 0;
    }

    /** The paint as a save holds it: nothing at all for plain solid. */
    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        if (type != Type.SOLID)
            tag.putByte("T", (byte) type.ordinal());
        if (angle != 0)
            tag.putShort("A", (short) angle);
        if (cx != ONE / 2)
            tag.putShort("X", (short) cx);
        if (cy != ONE / 2)
            tag.putShort("Y", (short) cy);
        if (radius != ONE / 2)
            tag.putShort("R", (short) radius);
        if (!stops.isEmpty()) {
            int[] packed = new int[stops.size() * 2];
            for (int i = 0; i < stops.size(); i++) {
                packed[2 * i] = stops.get(i).colour();
                packed[2 * i + 1] = stops.get(i).at();
            }
            tag.putIntArray("S", packed);
        }
        if (!bands.isEmpty()) {
            ListTag list = new ListTag();
            for (Band band : bands) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("C", band.colour());
                entry.putFloat("L", band.lower());
                entry.putFloat("U", band.upper());
                list.add(entry);
            }
            tag.put("B", list);
        }
        if (percent)
            tag.putBoolean("P", true);
        return tag;
    }

    public boolean isDefault() {
        return equals(SOLID);
    }

    public static ElementPaint read(CompoundTag tag) {
        if (tag == null || tag.isEmpty())
            return SOLID;
        List<Stop> stops = new ArrayList<>();
        int[] packed = tag.getIntArray("S");
        for (int i = 0; i + 1 < packed.length && stops.size() < MAX_STOPS; i += 2)
            stops.add(new Stop(packed[i], packed[i + 1]));
        List<Band> bands = new ArrayList<>();
        ListTag list = tag.getList("B", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && bands.size() < MAX_STOPS; i++) {
            CompoundTag entry = list.getCompound(i);
            bands.add(new Band(entry.getInt("C"), entry.getFloat("L"), entry.getFloat("U")));
        }
        return new ElementPaint(Type.byOrdinal(tag.getByte("T")), tag.getShort("A"),
                tag.contains("X") ? tag.getShort("X") : ONE / 2, tag.contains("Y") ? tag.getShort("Y") : ONE / 2,
                tag.contains("R") ? tag.getShort("R") : ONE / 2, stops, bands, tag.getBoolean("P"));
    }
}
