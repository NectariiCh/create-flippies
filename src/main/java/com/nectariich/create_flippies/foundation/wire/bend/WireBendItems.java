package com.nectariich.create_flippies.foundation.wire.bend;

import com.nectariich.create_flippies.content.data_cord.DataCordItem;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.wire.IWire;

/** Which items let a player bend wires: any Power Grid wire or cord (string light cord included), and the data cord. */
public final class WireBendItems {

    private WireBendItems() {}

    public static boolean isWireItem(Player player, ItemStack stack) {
        if (stack.isEmpty())
            return false;
        return stack.getItem() instanceof DataCordItem || IWire.isWire(player.level(), stack.getItem());
    }

    /** Bending is done with the wire in the main hand. */
    public static boolean holdsWireItem(Player player) {
        return isWireItem(player, player.getMainHandItem());
    }
}
