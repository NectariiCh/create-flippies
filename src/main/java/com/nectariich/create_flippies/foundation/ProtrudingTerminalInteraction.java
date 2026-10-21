package com.nectariich.create_flippies.foundation;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.wire.IWire;

/**
 * Connects wires to terminals that sit outside their block's own space.
 *
 * Power Grid's {@code WireItem.useOn} takes the block position from the
 * interaction event but the click location from a ray cast it runs itself,
 * server side. For a terminal inside its own block those always describe the
 * same spot; for the Square gauge's studs, which stand in the block behind the
 * panel, the re-cast can easily land somewhere else entirely — and then
 * {@code terminalIndexAt} finds no terminal and the wire is dropped onto a
 * mid-air endpoint instead of the contact.
 *
 * Running ahead of Architectury's forwarder (which subscribes at
 * {@link EventPriority#HIGH}) hands Power Grid the authoritative hit result
 * from the interaction itself, so the terminal is resolved from the spot the
 * player actually clicked.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class ProtrudingTerminalInteraction {

    private ProtrudingTerminalInteraction() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWireUsedOnProtrudingTerminal(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        // Power Grid's own wire handling bows out in both these cases
        if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown())
            return;

        Level level = event.getLevel();
        ItemStack stack = event.getItemStack();
        if (!IWire.isWire(level, stack.getItem()) || IWire.isCord(level, stack.getItem()))
            return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IProtrudingHitbox))
            return;

        // Through IElectric.getAt, not through the block: a block that hosts many
        // controls answers for its terminals from its block entity and declares
        // none itself, so asking the block would find nothing to connect to and
        // leave the wire on a mid-air endpoint
        IElectric electric = IElectric.getAt(level, pos);
        if (electric == null)
            return;

        BlockHitResult hit = event.getHitVec();
        if (hit == null)
            return;

        InteractionResult result = electric.onWire(state, new UseOnContext(player, hand, hit));
        if (result == InteractionResult.PASS)
            return;

        event.setCanceled(true);
        event.setCancellationResult(result);
    }
}
