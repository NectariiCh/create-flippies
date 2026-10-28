package com.nectariich.create_flippies.content.schematic_arrow;

import com.nectariich.create_flippies.compat.NbtCompat;
import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * An arrow picked up to be moved: which one, a cell that holds it, and the point it
 * was picked up by, on its surface in offset units. The next click puts that point
 * where it lands.
 *
 * Kept on the stack and written only by the server, like {@link ArrowDraft}.
 */
public record ArrowGrab(long id, BlockPos cell, PanelPlane plane, int depth, int u, int v) {

    private static final String ROOT = "ArrowGrab";

    public static ArrowGrab read(ItemStack stack) {
        CompoundTag tag = StackData.peek(stack);
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND))
            return null;
        CompoundTag grab = tag.getCompound(ROOT);
        PanelPlane plane = PanelPlane.load(grab);
        if (plane == null || !grab.contains("Cell", Tag.TAG_COMPOUND))
            return null;
        return new ArrowGrab(grab.getLong("Id"), NbtCompat.readBlockPos(grab.getCompound("Cell")), plane,
                grab.getInt("Depth") == 0 ? 0 : PanelDefinition.THICKNESS, grab.getInt("U"), grab.getInt("V"));
    }

    /** Records, or with null forgets, the arrow being moved. */
    public static void write(ItemStack stack, ArrowGrab grab) {
        if (grab == null) {
            //? if forge {
            CompoundTag tag = stack.getTag();
            if (tag != null)
                tag.remove(ROOT);
            //?} else {
            /*StackData.remove(stack, ROOT);
            *///?}
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", grab.id);
        tag.put("Cell", NbtCompat.writeBlockPos(grab.cell));
        grab.plane.save(tag);
        tag.putInt("Depth", grab.depth);
        tag.putInt("U", grab.u);
        tag.putInt("V", grab.v);
        StackData.put(stack, ROOT, tag);
    }
}
