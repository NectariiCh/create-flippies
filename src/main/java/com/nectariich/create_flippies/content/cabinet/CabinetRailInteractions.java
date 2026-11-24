package com.nectariich.create_flippies.content.cabinet;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.registry.ModBlocks;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps a DIN rail in hand from being placed as a block against a cabinet's rail.
 *
 * The rail cell's own {@code use} already swallows the click, but a sneaking click
 * with an item in hand never reaches a block's {@code use}: it goes straight to the
 * item, which for a rail is a block item, and would stand a rail block loose inside
 * the cabinet. Sneaking is also how a rail being drawn is cancelled, so the click is
 * stopped here, on both sides, before the item sees it.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class CabinetRailInteractions {

    private CabinetRailInteractions() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getItemStack().is(ModItems.DIN_RAIL.get()))
            return;
        if (!event.getLevel().getBlockState(event.getPos()).is(ModBlocks.CABINET_RAIL_HOST.get()))
            return;
        if (!event.getEntity().isShiftKeyDown())
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }
}
