package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.foundation.wire.duct.DuctColor;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPlacement;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSize;
import com.nectariich.create_flippies.foundation.wire.duct.DuctWires;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A wire duct. It is laid with a screwdriver in a placement mode held in the other
 * hand ({@code ScrewdriverActions}), the same in every such mode: click one point
 * on a face, then another on the same face or one at right angles to it, and a
 * duct is laid flat between them: straight, as an L when the two are not in line,
 * or turning over the edge onto the other face (see {@link DuctPlacement}), of any
 * length. The first point is kept in the stack until then; a sneaking right
 * click with the screwdriver forgets it ({@link #cancel}). One item per block of
 * duct. On its own the item does nothing.
 *
 * A duct item may carry a colour ({@link DuctColor}), which the duct it lays takes;
 * without one it lays a white duct.
 */
public class WireDuctItem extends Item {

    public static final String TAG_START = "DuctStart";

    private final DuctSize size;

    public WireDuctItem(DuctSize size, Properties properties) {
        super(properties);
        this.size = size;
    }

    public DuctSize size() {
        return size;
    }

    /**
     * The duct stack a screwdriver click would lay from: the duct in one hand while
     * the other holds a screwdriver in a placement mode. Null otherwise.
     */
    public static ItemStack placing(Player player) {
        InteractionHand tool = ScrewdriverItem.heldBy(player);
        if (tool == null || !ScrewdriverSettings.read(player.getItemInHand(tool)).mode().places())
            return null;
        ItemStack stack = player.getItemInHand(ScrewdriverItem.otherHand(tool));
        return stack.getItem() instanceof WireDuctItem ? stack : null;
    }

    /** The duct stack with a first point chosen, in the hand opposite a screwdriver; null if none. */
    public static ItemStack pending(Player player) {
        InteractionHand tool = ScrewdriverItem.heldBy(player);
        if (tool == null)
            return null;
        ItemStack stack = player.getItemInHand(ScrewdriverItem.otherHand(tool));
        return stack.getItem() instanceof WireDuctItem && stack.getTagElement(TAG_START) != null ? stack : null;
    }

    /**
     * Forgets the first point of a duct being laid, if the player holds a screwdriver
     * and, in the other hand, a duct with one. Whether there was one: the sneaking
     * click that asked is then this, not the screwdriver's menu. Both sides answer
     * alike; only the server says so.
     */
    public static boolean cancel(Player player) {
        ItemStack stack = pending(player);
        if (stack == null)
            return false;
        stack.removeTagKey(TAG_START);
        if (!player.level().isClientSide)
            message(player, Component.translatable("gui.create_flippies.wire_duct.cancelled"), false);
        return true;
    }

    /**
     * A screwdriver click on {@code face} at {@code hit} with {@code stack} in the
     * other hand: the first point of a duct, or the second, which lays it.
     */
    public void click(Player player, ItemStack stack, Direction face, Vec3 hit) {
        Level level = player.level();
        Vec3 point = WireDuctService.snapPoint(level, player.getEyePosition(), hit, face, size);
        CompoundTag start = stack.getTagElement(TAG_START);
        if (start == null) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("X", point.x);
            tag.putDouble("Y", point.y);
            tag.putDouble("Z", point.z);
            tag.putInt("Face", face.get3DDataValue());
            stack.getOrCreateTag().put(TAG_START, tag);
            message(player, Component.translatable("gui.create_flippies.wire_duct.next"), false);
            return;
        }
        Vec3 from = new Vec3(start.getDouble("X"), start.getDouble("Y"), start.getDouble("Z"));
        int f = start.getInt("Face");
        Direction firstFace = f >= 0 && f < 6 ? Direction.from3DDataValue(f) : face;
        WireDuctService.place(player, stack, size, from, firstFace, point, face);
    }

    static void message(Player player, Component text, boolean error) {
        player.displayClientMessage(error ? text.copy().withStyle(ChatFormatting.RED)
                : text.copy().withStyle(ChatFormatting.GRAY), true);
    }

    /** The colour a duct laid from {@code stack} takes. */
    public static int color(ItemStack stack) {
        return DuctColor.read(stack.getTag());
    }

    /** {@code stack} given {@code color}; white is no colour at all, so plain stacks still stack together. */
    public static ItemStack withColor(ItemStack stack, int color) {
        if ((color & 0xFFFFFF) == DuctColor.WHITE) {
            stack.removeTagKey(DuctColor.TAG);
        } else {
            DuctColor.write(stack.getOrCreateTag(), color);
        }
        return stack;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.getTagElement(TAG_START) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.create_flippies.wire_duct.tooltip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.create_flippies.wire_duct.size", pixels(size.widthPx()),
                pixels(size.widthPx() / 2)).withStyle(ChatFormatting.GRAY));
        // How many of each gauge fit alone, thickest first
        List<Component> fits = new ArrayList<>();
        for (int i = WireGauges.AWGS.size() - 1; i >= 0; i--) {
            int awg = WireGauges.AWGS.get(i);
            int count = DuctWires.howMany(size, awg);
            if (count > 0)
                fits.add(Component.translatable("item.create_flippies.wire_duct.holds_entry", count,
                        awg == 0 ? "1/0" : Integer.toString(awg)));
        }
        Component list = fits.isEmpty() ? Component.literal("-") : ComponentUtils.formatList(fits, Component.literal(", "));
        tooltip.add(Component.translatable("item.create_flippies.wire_duct.holds", list).withStyle(ChatFormatting.GRAY));
    }

    private static String pixels(double px) {
        return px == Math.rint(px) ? Integer.toString((int) px) : Double.toString(px);
    }
}
