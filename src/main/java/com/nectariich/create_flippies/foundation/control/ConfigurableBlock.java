package com.nectariich.create_flippies.foundation.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * An instrument block with a configuration menu of its own.
 *
 * The mounted forms do not need this — a host holds its controls as objects and
 * {@link HostedControl#openEditor} is how one of those is reached — so this is for
 * the other half of the catalogue: a gauge, a switch or an indicator placed as a
 * block, whose state lives on its own block entity and whose screen the client can
 * therefore open straight from the block entity it already has.
 *
 * The one method answers on both sides and opens on one. The server's pass is asked
 * only so that {@link ControlConfig} can give the same verdict on both — the
 * screwdriver has to know whether the click was claimed before it decides whether to
 * open its own menu instead, and a client that guessed would open the tool menu over
 * a gauge's.
 */
public interface ConfigurableBlock {

    /**
     * Opens this block's configuration for one player, if it has one.
     *
     * @return whether there was a configuration to open, which is the same answer on
     *         both sides
     */
    boolean openConfig(Level level, BlockPos pos, Player player);
}
