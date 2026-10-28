package com.nectariich.create_flippies.content.decal;

import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * The picture a screwdriver paints with, the colour it paints it in, and whether the
 * next picture imported onto it leaves out its background.
 *
 * Kept on the stack beside {@code ScrewdriverSettings} rather than inside it, and
 * written by its own packet. The settings are the menu's, sent whole when the menu
 * closes; a picture is chosen by importing a file, which finishes whenever the
 * upload does and has to land on the tool even if the menu is closed by then.
 *
 * The picture's proportions travel with its hash, read by the server out of the
 * stored picture itself, so that the overlay can show the rectangle a click would
 * paint without the client having to have downloaded the picture first.
 *
 * Only the server writes it, for the reason {@code ScrewdriverSettings} gives.
 *
 * @param transparent whether an import leaves out the drawing's background; the
 *                    picture already on the tool was imported one way or the other
 *                    and is not changed by it
 */
public record DecalSelection(String art, String name, float width, float height, int tint, boolean transparent) {

    private static final String ROOT = "Decal";

    public static final DecalSelection NONE = new DecalSelection("", "", 1, 1, PanelDecal.OWN_COLOURS, false);

    public boolean hasArt() {
        return ControlDesign.isHash(art) && width > 0 && height > 0;
    }

    public static DecalSelection read(ItemStack stack) {
        CompoundTag tag = StackData.peek(stack);
        if (tag == null || !tag.contains(ROOT))
            return NONE;
        CompoundTag decal = tag.getCompound(ROOT);
        float width = decal.getFloat("Width");
        float height = decal.getFloat("Height");
        return new DecalSelection(decal.getString("Art"), decal.getString("Name"), width > 0 ? width : 1,
                height > 0 ? height : 1, decal.getInt("Tint"), decal.getBoolean("Transparent"));
    }

    public void apply(ItemStack stack) {
        CompoundTag decal = new CompoundTag();
        decal.putString("Art", art);
        decal.putString("Name", DecalArt.sanitiseName(name));
        decal.putFloat("Width", width);
        decal.putFloat("Height", height);
        decal.putInt("Tint", tint == PanelDecal.OWN_COLOURS ? PanelDecal.OWN_COLOURS : tint | 0xFF000000);
        decal.putBoolean("Transparent", transparent);
        StackData.put(stack, ROOT, decal);
    }

    public DecalSelection withArt(String art, DecalArt picture) {
        return new DecalSelection(art, picture.name(), picture.width(), picture.height(), tint, transparent);
    }

    public DecalSelection withTint(int tint) {
        return new DecalSelection(art, name, width, height, tint, transparent);
    }

    public DecalSelection withTransparent(boolean transparent) {
        return new DecalSelection(art, name, width, height, tint, transparent);
    }
}
