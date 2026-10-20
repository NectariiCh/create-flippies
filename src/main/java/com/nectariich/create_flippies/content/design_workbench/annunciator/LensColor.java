package com.nectariich.create_flippies.content.design_workbench.annunciator;

import java.util.Locale;

/**
 * The nine colours an annunciator window comes in. The X variants keep their colour
 * while the window is dark ("xred" is a red-tinted window even unlit); the plain ones
 * sit grey and colourless until they light.
 *
 * Ported from the Unity rack definition's {@code TileColor}, wire names and all, so a
 * CSV written for one reads the same in the other.
 */
public enum LensColor {

    WHITE, AMBER, RED, GREEN, BLUE, XAMBER, XRED, XGREEN, XBLUE;

    /** The name a CSV writes and a save holds: lower case, x-prefixed. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True for the x variants: coloured even when dark. */
    public boolean keepsColorUnlit() {
        return ordinal() >= XAMBER.ordinal();
    }

    /** The hue without its x, for naming materials: X variants light the same as their plain twin. */
    public String hueName() {
        String name = wireName();
        return keepsColorUnlit() ? name.substring(1) : name;
    }

    /** The colour a window lights in, as 0xRRGGBB. */
    public int litRgb() {
        return switch (this) {
        case AMBER, XAMBER -> 0xFF940F;
        case RED, XRED -> 0xFF0F08;
        case GREEN, XGREEN -> 0x29FF4A;
        case BLUE, XBLUE -> 0x3373FF;
        default -> 0xFFF7E6;
        };
    }

    /** The next colour, for a button that steps through them. */
    public LensColor next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * Reads what a CSV is likely to contain: "xred", "x-red", "dark red", "darkred"
     * and "DARK RED" are all the same window, and "yellow" is amber. Null when it is
     * not a lens colour.
     */
    public static LensColor parse(String text) {
        if (text == null || text.isBlank())
            return null;
        String name = text.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
        if (name.startsWith("dark"))
            name = "x" + name.substring(4);
        return switch (name) {
        case "white", "xwhite" -> WHITE;
        case "amber", "yellow" -> AMBER;
        case "red" -> RED;
        case "green" -> GREEN;
        case "blue" -> BLUE;
        case "xamber", "xyellow" -> XAMBER;
        case "xred" -> XRED;
        case "xgreen" -> XGREEN;
        case "xblue" -> XBLUE;
        default -> null;
        };
    }

    /** The colour a saved name stands for, white for one that is not a colour. */
    public static LensColor of(String wireName) {
        LensColor color = parse(wireName);
        return color == null ? WHITE : color;
    }
}
