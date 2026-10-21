package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.content.wire_duct.DuctRouting;

import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WIRE DUCTS 2: every way Power Grid takes a placeable wire on from where it got
 * to (a click on a face, on a terminal, on another wire) goes through
 * {@code WireItem.connect}. A wire whose end is inside a duct leaves it first,
 * through the side facing where it is going ({@link DuctRouting#leaveDuct}); Power
 * Grid then carries on from there as from any other point. A wire not in a duct
 * goes through untouched.
 */
@Mixin(value = WireItem.class, remap = false)
public class WireItemDuctMixin {

    @Inject(method = "connect", at = @At("HEAD"))
    private static void create_flippies$leaveDuct(Level level, ItemStack stack, Player player, IWireEndpoint from,
            IWireEndpoint to, CallbackInfoReturnable<InteractionResultHolder<BlockWireEntity>> cir) {
        DuctRouting.leaveDuct(level, stack, player, from, to);
    }
}
