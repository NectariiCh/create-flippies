package com.nectariich.create_flippies.content.pc;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * A 1.44 MB floppy disk (spec 11.2.3): a {@code DiskId} assigned on first insert,
 * the label it was given in an anvil, and the write-protect tab
 * ({@code WriteProtect}), toggled by sneaking and using it in the air.
 */
public class FloppyDiskItem extends PcPartItem {

    public static final String TAG_WRITE_PROTECT = "WriteProtect";

    public FloppyDiskItem(Properties properties) {
        super(Kind.FLOPPY, properties);
    }

    public static boolean writeProtected(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean(TAG_WRITE_PROTECT);
    }

    /** The anvil name, or null for an unlabelled disk. */
    public static String label(ItemStack stack) {
        return stack.hasCustomHoverName() ? stack.getHoverName().getString() : null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown())
            return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) {
            boolean protect = !writeProtected(stack);
            stack.getOrCreateTag().putBoolean(TAG_WRITE_PROTECT, protect);
            player.displayClientMessage(Component.translatable(protect
                    ? "gui.create_flippies.pc.tooltip.write_protected" : "gui.create_flippies.pc.tooltip.writable"),
                    true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(writeProtected(stack)
                ? "gui.create_flippies.pc.tooltip.write_protected" : "gui.create_flippies.pc.tooltip.writable")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.create_flippies.pc.tooltip.disk_id",
                PcDiskItems.shortId(PcDiskItems.idOf(stack))).withStyle(ChatFormatting.DARK_GRAY));
    }
}
