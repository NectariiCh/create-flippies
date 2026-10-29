package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How the {@link InspectorMenu} writes values into its fields and reads typed text back:
 * lengths in pixels to a tenth (stored in tenths), colours as hex, levels and range ends
 * as plain decimals. Pure Java, so the parsing a player leans on every keystroke is tested
 * without a game.
 *
 * Every parse returns null (or a negative sentinel where said) for text that is not yet a
 * value, such as an empty field, a lone "." or "-", or five hex digits of six: the menu
 * then sends nothing, so a half-typed value never reaches the display.
 */
final class InspectorValues {

    /**
     * Tenths of a pixel per pixel, as {@code Viewport.UNITS_PER_PIXEL}; kept here so this
     * class needs no game classes.
     */
    static final int UNITS = 10;

    private InspectorValues() {}

    /** Pixels to a tenth, without a trailing ".0": 12 and 12.5, never 12.0. */
    static String pixels(double pixels) {
        String text = String.format(Locale.ROOT, "%.1f", pixels);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** Tenths shown as pixels. */
    static String tenths(int tenths) {
        return pixels(tenths / (double) UNITS);
    }

    /** Typed pixels as tenths, or null while the text is not a number yet. */
    static Integer parseTenths(String text) {
        if (text.isEmpty() || text.equals("."))
            return null;
        try {
            return (int) Math.round(Double.parseDouble(text) * UNITS);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Exactly {@code digits} hex digits, a leading "#" allowed; null otherwise. */
    static Integer hex(String text, int digits) {
        String cleaned = text.startsWith("#") ? text.substring(1) : text;
        if (cleaned.length() != digits)
            return null;
        try {
            return (int) Long.parseLong(cleaned, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** An opaque colour typed as RRGGBB, alpha set; null until six digits are there. */
    static Integer opaque(String text) {
        Integer parsed = hex(text, 6);
        return parsed == null ? null : parsed | 0xFF000000;
    }

    /**
     * A colour that may see through, as the old editor's highlight took it: empty for
     * none (0), RRGGBB opaque, AARRGGBB with its own alpha; null for anything else.
     */
    static Integer translucent(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (digits.isEmpty())
            return 0;
        if (digits.length() == 6) {
            Integer parsed = hex(digits, 6);
            return parsed == null ? null : parsed | 0xFF000000;
        }
        return digits.length() == 8 ? hex(digits, 8) : null;
    }

    static String rgb(int colour) {
        return String.format("%06X", colour & 0xFFFFFF);
    }

    /** A translucent colour as typed back: empty for none, else all eight digits. */
    static String argb(int colour) {
        return colour == 0 ? "" : String.format("%08X", colour);
    }

    /** A decimal without trailing zeros: 100, 2.5, -0.125. */
    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15)
            return Long.toString((long) value);
        String text = String.format(Locale.ROOT, "%.4f", value);
        if (text.indexOf('.') >= 0) {
            int end = text.length();
            while (text.charAt(end - 1) == '0')
                end--;
            if (text.charAt(end - 1) == '.')
                end--;
            text = text.substring(0, end);
        }
        return text.equals("-0") ? "0" : text;
    }

    /** A typed decimal, sign allowed, or null while it is not one yet. */
    static Float parseNumber(String text) {
        if (text.isEmpty() || text.equals("-") || text.equals(".") || text.equals("-."))
            return null;
        try {
            float value = Float.parseFloat(text);
            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A typed whole number, sign allowed, or null. */
    static Integer parseInt(String text) {
        if (text.isEmpty() || text.equals("-"))
            return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Thousandths of the box shown as a percent to a tenth. */
    static String percent(int thousandths) {
        return pixels(thousandths / 10.0);
    }

    /** A typed percent as thousandths, or null. */
    static Integer parsePercent(String text) {
        return parseTenths(text);
    }

    /**
     * Where each stop ends up after one stop's position changes, as the paint sorts
     * them: stably by position, so stops at the same place keep their order.
     * {@code positions} are the stops' positions in their current (sorted) order;
     * the result maps each old index to its new one.
     */
    static int[] reorder(List<Integer> positions, int edited, int at) {
        List<Integer> changed = new ArrayList<>(positions);
        changed.set(edited, at);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < changed.size(); i++)
            order.add(i);
        order.sort((a, b) -> Integer.compare(changed.get(a), changed.get(b)));
        int[] moved = new int[changed.size()];
        for (int place = 0; place < order.size(); place++)
            moved[order.get(place)] = place;
        return moved;
    }

    /** A level in value units as a percent of the range, or the level unchanged for an empty range. */
    static float toPercent(float level, float min, float max) {
        return max == min ? level : (level - min) / (max - min) * 100;
    }

    /** A percent of the range as a level in value units, or unchanged for an empty range. */
    static float fromPercent(float percent, float min, float max) {
        return max == min ? percent : min + percent / 100 * (max - min);
    }

    /** The colour a second stop starts at: the first one, much darker. */
    static int darker(int colour) {
        int r = (int) (((colour >> 16) & 0xFF) * 0.35);
        int g = (int) (((colour >> 8) & 0xFF) * 0.35);
        int b = (int) ((colour & 0xFF) * 0.35);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
