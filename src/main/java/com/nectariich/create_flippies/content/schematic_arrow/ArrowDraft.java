package com.nectariich.create_flippies.content.schematic_arrow;

import java.util.Arrays;

import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.foundation.panel.PanelDefinition;
import com.nectariich.create_flippies.foundation.panel.PanelPlane;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * An arrow being drawn: the surface it is on and the points clicked so far, in
 * offset units.
 *
 * Kept on the stack and written only by the server, like the pending corner of a
 * rectangle, so the point the overlay previews is the point the server will add.
 */
public record ArrowDraft(PanelPlane plane, int depth, int[] points) {

    private static final String ROOT = "ArrowDraft";

    public int pointCount() {
        return points.length / 2;
    }

    public int[] last() {
        return new int[] { points[points.length - 2], points[points.length - 1] };
    }

    public ArrowDraft plus(int[] point) {
        int[] grown = Arrays.copyOf(points, points.length + 2);
        grown[points.length] = point[0];
        grown[points.length + 1] = point[1];
        return new ArrowDraft(plane, depth, grown);
    }

    /** One point fewer, or null if that leaves none. */
    public ArrowDraft less() {
        return points.length <= 2 ? null : new ArrowDraft(plane, depth, Arrays.copyOf(points, points.length - 2));
    }

    public boolean isOn(PanelPlane surface, int depth) {
        return plane.equals(surface) && this.depth == depth;
    }

    public static ArrowDraft read(ItemStack stack) {
        CompoundTag tag = StackData.peek(stack);
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND))
            return null;
        CompoundTag draft = tag.getCompound(ROOT);
        PanelPlane plane = PanelPlane.load(draft);
        int[] points = draft.getIntArray("Points");
        if (plane == null || points.length < 2 || points.length % 2 != 0
                || points.length > 2 * PanelArrow.MAX_POINTS)
            return null;
        return new ArrowDraft(plane, draft.getInt("Depth") == 0 ? 0 : PanelDefinition.THICKNESS, points);
    }

    /** Records, or with null forgets, the arrow being drawn. */
    public static void write(ItemStack stack, ArrowDraft draft) {
        if (draft == null) {
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
        draft.plane.save(tag);
        tag.putInt("Depth", draft.depth);
        tag.put("Points", new IntArrayTag(draft.points));
        StackData.put(stack, ROOT, tag);
    }
}
