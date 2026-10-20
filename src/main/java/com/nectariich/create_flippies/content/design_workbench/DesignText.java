package com.nectariich.create_flippies.content.design_workbench;

import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * One line of text printed on a designed control: a legend on the plate, or a
 * marking on the handle itself.
 *
 * The text sits in a rectangle on a plane parallel to the plate, seen from the top
 * view the workbench edits it in, so x runs right and z runs down the screen. All
 * lengths are authored pixels, like every other length in a design. The text is
 * centred vertically in its rectangle and aligned horizontally by {@link #align},
 * and the renderer shrinks it to fit the rectangle's width rather than let it spill.
 *
 * Like the rest of a design this arrives from a client, so {@link #read} makes any
 * value safe before anything draws it.
 *
 * @param text      a single line, at most {@link #MAX_LENGTH} characters
 * @param y         the height of the text's plane above the plate
 * @param onMoving  whether the text follows the moving parts instead of the body
 * @param font      one of {@link #BUILT_IN_FONTS}, or {@code "ttf:<hash>"} for a font
 *                  file kept in the design store beside the model
 * @param color     the colour as 0xRRGGBB
 * @param size      the height of a capital letter, in pixels
 * @param align     0 left, 1 centre, 2 right
 * @param quarters  how many quarter turns the text is rotated in the plate's plane
 * @param glow      0 for text lit like the panel, 1 for full bright with a halo
 */
public record DesignText(String text, float x1, float z1, float x2, float z2, float y, boolean onMoving,
        String font, boolean bold, boolean italic, boolean underline, boolean strikethrough, int color,
        float size, int align, int quarters, float glow) {

    public static final int MAX_LENGTH = 128;

    public static final float MIN_SIZE = 0.25f, MAX_SIZE = 16;

    public static final String DEFAULT_FONT = "minecraft:default";

    /** The fonts every client has, without anything being sent. */
    public static final Set<String> BUILT_IN_FONTS = Set.of(DEFAULT_FONT, "minecraft:uniform", "minecraft:alt",
            "minecraft:illageralt");

    /** What marks a font as one of the design store's own files. */
    public static final String TTF_PREFIX = "ttf:";

    /** The same reach every box of a design is held to, so text cannot float far off the panel. */
    private static final float MIN_COORD = -8, MAX_COORD = 24, MAX_HEIGHT = 16;

    /** A fresh box with the defaults the workbench starts from, drawn where the player dragged. */
    public static DesignText create(float x1, float z1, float x2, float z2, float y) {
        return new DesignText("Text", x1, z1, x2, z2, y, false, DEFAULT_FONT, false, false, false, false, 0xFFFFFF,
                2, 1, 0, 0);
    }

    /** The design store hash of this text's font file, or null for a built-in font. */
    public String fontHash() {
        return font.startsWith(TTF_PREFIX) ? font.substring(TTF_PREFIX.length()) : null;
    }

    /** Whether a font name is one this text can use: a built-in, or a well formed file reference. */
    public static boolean isValidFont(String font) {
        return BUILT_IN_FONTS.contains(font)
                || font.startsWith(TTF_PREFIX) && ControlDesign.isHash(font.substring(TTF_PREFIX.length()));
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Text", text);
        tag.putFloat("X1", x1);
        tag.putFloat("Z1", z1);
        tag.putFloat("X2", x2);
        tag.putFloat("Z2", z2);
        tag.putFloat("Y", y);
        tag.putBoolean("OnMoving", onMoving);
        tag.putString("Font", font);
        tag.putBoolean("Bold", bold);
        tag.putBoolean("Italic", italic);
        tag.putBoolean("Underline", underline);
        tag.putBoolean("Strikethrough", strikethrough);
        tag.putInt("Color", color);
        tag.putFloat("Size", size);
        tag.putInt("Align", align);
        tag.putInt("Quarters", quarters);
        tag.putFloat("Glow", glow);
        return tag;
    }

    /**
     * Reads a text box and makes it safe to draw, whoever wrote it: every number
     * finite and in range, the corners ordered, the text one bounded line, and a
     * font nobody can resolve replaced by the default.
     */
    public static DesignText read(CompoundTag tag) {
        float x1 = coord(tag.getFloat("X1")), x2 = coord(tag.getFloat("X2"));
        float z1 = coord(tag.getFloat("Z1")), z2 = coord(tag.getFloat("Z2"));
        String font = tag.getString("Font");
        if (!isValidFont(font))
            font = DEFAULT_FONT;
        float size = tag.getFloat("Size");
        size = Float.isFinite(size) ? Mth.clamp(size, MIN_SIZE, MAX_SIZE) : 2;
        float y = tag.getFloat("Y");
        y = Float.isFinite(y) ? Mth.clamp(y, 0, MAX_HEIGHT) : 0;
        float glow = tag.getFloat("Glow");
        glow = Float.isFinite(glow) ? Mth.clamp(glow, 0, 1) : 0;
        return new DesignText(line(tag.getString("Text")), Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2),
                Math.max(z1, z2), y, tag.getBoolean("OnMoving"), font, tag.getBoolean("Bold"),
                tag.getBoolean("Italic"), tag.getBoolean("Underline"), tag.getBoolean("Strikethrough"),
                tag.getInt("Color") & 0xFFFFFF, size, Mth.clamp(tag.getInt("Align"), 0, 2),
                Math.floorMod(tag.getInt("Quarters"), 4), glow);
    }

    private static float coord(float value) {
        return Float.isFinite(value) ? Mth.clamp(value, MIN_COORD, MAX_COORD) : 8;
    }

    /** One line: line breaks become spaces, since the renderer draws a single row. */
    private static String line(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() > MAX_LENGTH ? flat.substring(0, MAX_LENGTH) : flat;
    }
}
