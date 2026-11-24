package com.nectariich.create_flippies.content.cabinet;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.StackData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * What the screwdriver's cabinet mode cuts: a square or a round hole of a set size
 * at the point clicked, or a rectangle drawn corner to corner. Kept on the tool's
 * stack under a tag of its own, beside {@link com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings},
 * and written by the server alone, for the reason that class gives.
 *
 * The size is in half pixels, so a hole can be as small as half a pixel and is
 * always placed with its edges on the half-pixel grid.
 */
public record CabinetCutSettings(Kind kind, int halfPixels) {

    public enum Kind {
        SQUARE, CIRCLE, DYNAMIC;

        public Kind next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** Whether it is made at one point with the size dialled in, rather than drawn corner to corner. */
        public boolean sized() {
            return this != DYNAMIC;
        }

        public Component label() {
            return Component.translatable(
                    "gui." + CreateFlippies.MOD_ID + ".screwdriver.cut_kind." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Half a pixel to two blocks. */
    public static final int MIN_SIZE = 1, MAX_SIZE = 64;

    public static final CabinetCutSettings DEFAULT = new CabinetCutSettings(Kind.CIRCLE, 8);

    private static final String TAG = "CabinetCut";

    public CabinetCutSettings {
        kind = kind == null ? Kind.CIRCLE : kind;
        halfPixels = Mth.clamp(halfPixels, MIN_SIZE, MAX_SIZE);
    }

    /** The size, px. */
    public double size() {
        return halfPixels / 2.0;
    }

    public static CabinetCutSettings read(ItemStack stack) {
        CompoundTag tag = StackData.sub(stack, TAG);
        if (tag == null || tag.isEmpty())
            return DEFAULT;
        int kind = tag.getByte("Kind");
        return new CabinetCutSettings(kind >= 0 && kind < Kind.values().length ? Kind.values()[kind] : Kind.CIRCLE,
                tag.contains("Size") ? tag.getInt("Size") : DEFAULT.halfPixels);
    }

    public void apply(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Kind", (byte) kind.ordinal());
        tag.putInt("Size", halfPixels);
        StackData.put(stack, TAG, tag);
    }

    /** The size as the menu shows it: "4" or "2.5". */
    public static String format(int halfPixels) {
        return halfPixels % 2 == 0 ? Integer.toString(halfPixels / 2) : (halfPixels / 2) + ".5";
    }

    /** A size typed into the menu, px, as half pixels to the nearest; -1 for none. */
    public static int parse(String text) {
        try {
            double px = Double.parseDouble(text.trim());
            if (!Double.isFinite(px))
                return -1;
            return Mth.clamp((int) Math.round(px * 2), MIN_SIZE, MAX_SIZE);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
