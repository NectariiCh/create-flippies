package com.nectariich.create_flippies.content.data_cord;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The universal data cord (spec 11.2.5). Right-click a port, then a second port of
 * the same type with a role that pairs; one item makes one cord of any length up to
 * {@link PortCompatibility#MAX_CORD_BLOCKS}. The first port is kept in the stack's
 * NBT until then; right-clicking the air or sneaking cancels it.
 */
public class DataCordItem extends Item {

    private static final String TAG_START = "CordStart";

    public DataCordItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player != null && player.isShiftKeyDown())
            return cancel(level, player, stack);
        DataPortHost host = DataCords.hostAt(level, PortAddress.block(context.getClickedPos()));
        int port = host == null ? -1 : host.portAt(context.getClickLocation());
        if (port < 0)
            return InteractionResult.PASS;
        return usePort(level, player, stack, host, port);
    }

    /**
     * {@code stack} used on {@code host}'s port {@code port}: the cord's first end
     * is remembered, or, with one remembered, the two are joined. Blocks get here
     * from {@link #useOn}, entity hosts from their own click handling.
     */
    public static InteractionResult usePort(Level level, Player player, ItemStack stack, DataPortHost host,
            int port) {
        if (player != null && player.isShiftKeyDown())
            return cancel(level, player, stack);
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        CompoundTag start = stack.getTagElement(TAG_START);
        if (start == null) {
            CompoundTag tag = new CompoundTag();
            host.portAddress().write(tag, "Pos");
            tag.putInt("Port", port);
            stack.getOrCreateTag().put(TAG_START, tag);
            message(player, Component.translatable("gui.create_flippies.pc.cord.next"), false);
            return InteractionResult.SUCCESS;
        }

        PortAddress startAt = PortAddress.read(start, "Pos");
        int startPort = start.getInt("Port");
        DataPortHost first = startAt == null ? null : DataCords.hostAt(level, startAt);
        if (first == null || startPort >= first.dataPortCount()) {
            stack.removeTagKey(TAG_START);
            message(player, Component.translatable("gui.create_flippies.pc.cord.start_gone"), true);
            return InteractionResult.FAIL;
        }
        PortCompatibility.Result result = PortCompatibility.check(
                new PortCompatibility.End(new PortEnd(startAt, startPort), first.portType(startPort),
                        first.portRole(startPort), first.link(startPort) != null),
                new PortCompatibility.End(new PortEnd(host.portAddress(), port), host.portType(port),
                        host.portRole(port), host.link(port) != null),
                first.portOrigin(startPort).distanceTo(host.portOrigin(port)));
        if (result != PortCompatibility.Result.OK) {
            message(player, Component.translatable(result.langKey()), true);
            return InteractionResult.FAIL;
        }
        if (DataCords.connect((ServerLevel) level, first, startPort, host, port) == null) {
            message(player, Component.translatable("gui.create_flippies.pc.cord.failed"), true);
            return InteractionResult.FAIL;
        }
        stack.removeTagKey(TAG_START);
        if (player == null || !player.getAbilities().instabuild)
            stack.shrink(1);
        message(player, Component.translatable("gui.create_flippies.pc.cord.connected"), false);
        return InteractionResult.SUCCESS;
    }

    /** One port of one host, as the compatibility check tells ports apart. */
    private record PortEnd(PortAddress host, int port) {}

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getTagElement(TAG_START) == null)
            return InteractionResultHolder.pass(stack);
        cancel(level, player, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static InteractionResult cancel(Level level, Player player, ItemStack stack) {
        if (stack.getTagElement(TAG_START) == null)
            return InteractionResult.PASS;
        if (!level.isClientSide) {
            stack.removeTagKey(TAG_START);
            message(player, Component.translatable("gui.create_flippies.pc.cord.cancelled"), false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static void message(Player player, Component text, boolean error) {
        if (player != null)
            player.displayClientMessage(error ? text.copy().withStyle(ChatFormatting.RED) : text, true);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.getTagElement(TAG_START) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("gui.create_flippies.pc.cord.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
