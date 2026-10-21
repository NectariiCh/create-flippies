package com.nectariich.create_flippies.content.wire_duct;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.duct.DuctColor;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPlacement;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSize;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A wire duct. Click one point on a face, then another on the same face, and a
 * duct is laid flat between them: straight, or as an L when the two are not in
 * line (see {@link DuctPlacement}), of any length. The first point is kept in the
 * stack until then; sneak-clicking forgets it. One item per block of duct.
 *
 * A duct item may carry a colour ({@link DuctColor}), which the duct it lays takes;
 * without one it lays a white duct.
 */
public class WireDuctItem extends Item {

    static final String TAG_START = "DuctStart";

    private final DuctSize size;

    public WireDuctItem(DuctSize size, Properties properties) {
        super(properties);
        this.size = size;
    }

    public DuctSize size() {
        return size;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null)
            return InteractionResult.PASS;
        if (player.isShiftKeyDown())
            return cancel(level, player, stack);
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        Direction face = context.getClickedFace();
        Vec3 point = WireDuctService.snapPoint(level, context.getClickLocation(), face, size);
        CompoundTag start = stack.getTagElement(TAG_START);
        if (start == null) {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("X", point.x);
            tag.putDouble("Y", point.y);
            tag.putDouble("Z", point.z);
            tag.putInt("Face", face.get3DDataValue());
            stack.getOrCreateTag().put(TAG_START, tag);
            message(player, Component.translatable("gui.create_flippies.wire_duct.next"), false);
            return InteractionResult.SUCCESS;
        }
        Vec3 from = new Vec3(start.getDouble("X"), start.getDouble("Y"), start.getDouble("Z"));
        int f = start.getInt("Face");
        Direction firstFace = f >= 0 && f < 6 ? Direction.from3DDataValue(f) : face;
        WireDuctService.place(player, stack, size, from, firstFace, point, face);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && stack.getTagElement(TAG_START) != null) {
            cancel(level, player, stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    private static InteractionResult cancel(Level level, Player player, ItemStack stack) {
        if (stack.getTagElement(TAG_START) == null)
            return InteractionResult.PASS;
        if (!level.isClientSide) {
            stack.removeTagKey(TAG_START);
            message(player, Component.translatable("gui.create_flippies.wire_duct.cancelled"), false);
        }
        return InteractionResult.SUCCESS;
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
        tooltip.add(Component.translatable("item.create_flippies.wire_duct.tooltip", size.capacityAwg())
                .withStyle(ChatFormatting.GRAY));
    }
}
