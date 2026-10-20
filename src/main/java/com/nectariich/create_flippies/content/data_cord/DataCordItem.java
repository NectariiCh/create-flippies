package com.nectariich.create_flippies.content.data_cord;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
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
        DataPortHost host = DataCords.hostAt(level, context.getClickedPos());
        int port = host == null ? -1 : host.portAt(context.getClickLocation());
        if (port < 0)
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        CompoundTag start = stack.getTagElement(TAG_START);
        if (start == null) {
            CompoundTag tag = new CompoundTag();
            tag.put("Pos", NbtUtils.writeBlockPos(context.getClickedPos()));
            tag.putInt("Port", port);
            stack.getOrCreateTag().put(TAG_START, tag);
            message(player, Component.translatable("gui.create_flippies.pc.cord.next"), false);
            return InteractionResult.SUCCESS;
        }

        BlockPos startPos = NbtUtils.readBlockPos(start.getCompound("Pos"));
        int startPort = start.getInt("Port");
        DataPortHost first = DataCords.hostAt(level, startPos);
        if (first == null || startPort >= first.dataPortCount()) {
            stack.removeTagKey(TAG_START);
            message(player, Component.translatable("gui.create_flippies.pc.cord.start_gone"), true);
            return InteractionResult.FAIL;
        }
        PortCompatibility.Result result = PortCompatibility.check(
                new PortCompatibility.End(startPos.asLong() * 64 + startPort, first.portType(startPort),
                        first.portRole(startPort), first.link(startPort) != null),
                new PortCompatibility.End(context.getClickedPos().asLong() * 64 + port, host.portType(port),
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
