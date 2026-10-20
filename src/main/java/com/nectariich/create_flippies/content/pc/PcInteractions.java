package com.nectariich.create_flippies.content.pc;

import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * What a right click on a case does (spec 11.2.3, 11.2.9), given what it landed on.
 * The rules themselves are {@link PcSlotRules}'; this applies them, moves the
 * items and tells the player why when something is refused.
 *
 * - Buttons: power and reset, with anything in hand that has no action of its own.
 * - Floppy slot: a disk in hand inserts it, an empty hand ejects; any time.
 * - The open side (panel off): a part in hand installs it, into the aimed RAM slot
 *   if that is free; the panel item fits the panel back.
 * - A part, sneaking with an empty hand: takes it out.
 * - The front panel comes off with a screwdriver ({@link PcCaseBlock#openConfig}).
 */
public final class PcInteractions {

    /** How far the screwdriver path re-picks the aim from the player's eye. */
    static final double PICK_RANGE = 8;

    private PcInteractions() {}

    static InteractionResult use(PcCaseBlockEntity pc, PcCaseGeometry.Target target, Player player,
            InteractionHand hand) {
        Level level = pc.getLevel();
        ItemStack held = player.getItemInHand(hand);
        PcPartItem.Kind kind = PcPartItem.kindOf(held);
        switch (target.kind()) {
            case POWER_BUTTON, RESET_BUTTON -> {
                if (hand != InteractionHand.MAIN_HAND)
                    return InteractionResult.PASS;
                if (!level.isClientSide) {
                    if (target.kind() == PcCaseGeometry.Kind.POWER_BUTTON)
                        pc.pressPower(player);
                    else
                        pc.pressReset();
                }
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            case FLOPPY_SLOT -> {
                if (kind == PcPartItem.Kind.FLOPPY)
                    return install(pc, PcSlot.FLOPPY, player, hand, held);
                if (held.isEmpty() && hand == InteractionHand.MAIN_HAND)
                    return takeOut(pc, PcSlot.FLOPPY, player);
                return InteractionResult.PASS;
            }
            case PART, INTERIOR -> {
                PcSlot aimed = target.kind() == PcCaseGeometry.Kind.PART ? target.slot() : null;
                if (held.isEmpty() && player.isShiftKeyDown() && hand == InteractionHand.MAIN_HAND && aimed != null
                        && aimed != PcSlot.FRONT_PANEL)
                    return takeOut(pc, aimed, player);
                if (kind == null || kind == PcPartItem.Kind.FLOPPY)
                    return InteractionResult.PASS;
                PcSlot slot = kind == PcPartItem.Kind.RAM ? PcSlotRules.ramSlotFor(aimed, pc.filled())
                        : PcPartItem.slotOf(kind);
                return install(pc, slot, player, hand, held);
            }
            default -> {
                return InteractionResult.PASS;
            }
        }
    }

    private static InteractionResult install(PcCaseBlockEntity pc, PcSlot slot, Player player, InteractionHand hand,
            ItemStack held) {
        Level level = pc.getLevel();
        Set<PcSlot> filled = pc.filled();
        PcSlotRules.Refusal refusal = PcSlotRules.canInstall(slot, filled, pc.running());
        if (refusal != PcSlotRules.Refusal.OK)
            return refuse(level, player, refusal, slot);
        if (!level.isClientSide) {
            ItemStack one = held.copyWithCount(1);
            if (!player.getAbilities().instabuild)
                held.shrink(1);
            pc.install(slot, one);
            level.playSound(null, pc.getBlockPos(), SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6f, 1.2f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static InteractionResult takeOut(PcCaseBlockEntity pc, PcSlot slot, Player player) {
        Level level = pc.getLevel();
        PcSlotRules.Refusal refusal = PcSlotRules.canRemove(slot, pc.filled(), pc.running());
        if (refusal == PcSlotRules.Refusal.EMPTY)
            return InteractionResult.PASS;
        if (refusal != PcSlotRules.Refusal.OK)
            return refuse(level, player, refusal, slot);
        if (!level.isClientSide) {
            give(player, pc.remove(slot));
            level.playSound(null, pc.getBlockPos(), SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6f, 1.2f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** The screwdriver on the front panel: it comes off into the inventory, or drops. Server only. */
    static void takePanel(PcCaseBlockEntity pc, Player player) {
        PcSlotRules.Refusal refusal = PcSlotRules.canRemove(PcSlot.FRONT_PANEL, pc.filled(), pc.running());
        if (refusal != PcSlotRules.Refusal.OK)
            return;
        give(player, pc.remove(PcSlot.FRONT_PANEL));
        pc.getLevel().playSound(null, pc.getBlockPos(), SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.6f,
                0.9f);
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack))
            player.drop(stack, false);
    }

    private static InteractionResult refuse(Level level, Player player, PcSlotRules.Refusal refusal, PcSlot slot) {
        if (!level.isClientSide)
            player.displayClientMessage(Component.translatable(refusal.langKey(), Component.translatable(slot.langKey()))
                    .withStyle(ChatFormatting.RED), true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
