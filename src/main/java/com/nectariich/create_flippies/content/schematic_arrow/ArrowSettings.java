package com.nectariich.create_flippies.content.schematic_arrow;

import com.nectariich.create_flippies.compat.StackData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * How the screwdriver draws schematic arrows: the style the next one takes, what a
 * right click does, and how points snap.
 *
 * Kept on the stack beside {@code ScrewdriverSettings} and the decal selection, and
 * written only by the server for the reason {@code ScrewdriverSettings} gives: the
 * menu sends it, the server applies it, the inventory sync brings it back.
 */
public record ArrowSettings(ArrowStyle style, ArrowAction action, ArrowSnap snap) {

    private static final String ROOT = "Arrow";

    public static final ArrowSettings DEFAULT = new ArrowSettings(ArrowStyle.DEFAULT, ArrowAction.DRAW,
            ArrowSnap.GRID_ANGLE);

    public ArrowSettings {
        style = style == null ? ArrowStyle.DEFAULT : style;
        action = action == null ? ArrowAction.DRAW : action;
        snap = snap == null ? ArrowSnap.GRID_ANGLE : snap;
    }

    public static ArrowSettings read(ItemStack stack) {
        CompoundTag tag = StackData.peek(stack);
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND))
            return DEFAULT;
        return load(tag.getCompound(ROOT));
    }

    public void apply(ItemStack stack) {
        StackData.put(stack, ROOT, save());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        style.save(tag);
        tag.putByte("Action", (byte) action.ordinal());
        tag.putByte("Snap", (byte) snap.ordinal());
        return tag;
    }

    public static ArrowSettings load(CompoundTag tag) {
        return new ArrowSettings(ArrowStyle.load(tag), ArrowAction.byIndex(tag.getByte("Action")),
                ArrowSnap.byIndex(tag.getByte("Snap")));
    }

    public void write(FriendlyByteBuf buffer) {
        style.write(buffer);
        buffer.writeByte(action.ordinal());
        buffer.writeByte(snap.ordinal());
    }

    public static ArrowSettings read(FriendlyByteBuf buffer) {
        return new ArrowSettings(ArrowStyle.read(buffer), ArrowAction.byIndex(buffer.readByte()),
                ArrowSnap.byIndex(buffer.readByte()));
    }

    public ArrowSettings withStyle(ArrowStyle style) {
        return new ArrowSettings(style, action, snap);
    }

    public ArrowSettings withAction(ArrowAction action) {
        return new ArrowSettings(style, action, snap);
    }

    public ArrowSettings withSnap(ArrowSnap snap) {
        return new ArrowSettings(style, action, snap);
    }
}
