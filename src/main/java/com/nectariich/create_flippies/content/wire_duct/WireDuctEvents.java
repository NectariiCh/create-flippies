package com.nectariich.create_flippies.content.wire_duct;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.wire.duct.PendingRoute;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Takes over the last click of a wire that has been routed through a duct.
 *
 * Power Grid finishes a hanging wire in its terminal's {@code onWire}, reached
 * from Architectury's forwarder of this event at {@link EventPriority#HIGH}; that
 * would make a plain straight wire and forget the route. So while the wire in hand
 * carries a route, every click on a block is handled here, first: on a terminal it
 * makes the routed wire, anywhere else it is refused, rather than letting Power
 * Grid lay a block wire from the first terminal. A wire with no route never gets
 * past the first check, and Power Grid handles it exactly as before.
 *
 * The client cancels as well, so it neither predicts Power Grid's result nor
 * swings twice; the click still reaches the server.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class WireDuctEvents {

    private WireDuctEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (event.getHand() != InteractionHand.MAIN_HAND || player.isShiftKeyDown())
            return;
        Level level = event.getLevel();
        ItemStack stack = event.getItemStack();
        if (!routing(level, stack))
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player instanceof ServerPlayer server)
            WireDuctService.finish(server, stack, PendingRoute.of(stack), event.getPos(), event.getHitVec());
    }

    /** Whether {@code stack} is a wire being placed with part of its way through ducts already chosen. */
    public static boolean routing(Level level, ItemStack stack) {
        if (!WireDuctService.isRoutableWire(level, stack))
            return false;
        PendingRoute route = PendingRoute.of(stack);
        return route != null && !route.isEmpty();
    }
}
