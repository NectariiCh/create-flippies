package com.nectariich.create_flippies.foundation.wire.duct;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.DyeColor;

/**
 * A wire duct's colour: plain RGB, white unless dyed. The same key carries it in a
 * duct's save data and on a duct item, so a duct broken off gives back items of its
 * own colour and a duct laid from them comes out that colour again.
 */
public final class DuctColor {

    public static final int WHITE = 0xFFFFFF;

    public static final String TAG = "Color";

    private DuctColor() {}

    /** The colour saved in {@code tag}, or white when none is (as in a save from before ducts had colours). */
    public static int read(CompoundTag tag) {
        return tag != null && tag.contains(TAG, Tag.TAG_INT) ? tag.getInt(TAG) & 0xFFFFFF : WHITE;
    }

    public static void write(CompoundTag tag, int color) {
        tag.putInt(TAG, color & 0xFFFFFF);
    }

    /** A dye's colour, worked out the way Power Grid colours a wire with one. */
    public static int of(DyeColor dye) {
        return of(dye.getTextureDiffuseColors());
    }

    /** A colour from a dye's diffuse red, green and blue, each 0 to 1. */
    public static int of(float[] rgb) {
        return (int) (rgb[0] * 255) << 16 | (int) (rgb[1] * 255) << 8 | (int) (rgb[2] * 255);
    }

    /** {@code color} darkened by {@code shade} (1 leaves it as it is), as an opaque ARGB value. */
    public static int shaded(int color, float shade) {
        int r = Math.round(((color >> 16) & 0xFF) * shade);
        int g = Math.round(((color >> 8) & 0xFF) * shade);
        int b = Math.round((color & 0xFF) * shade);
        return 0xFF000000 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }
}
