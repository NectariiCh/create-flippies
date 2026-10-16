package com.nectariich.create_flippies.foundation.control;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * What an installed control may ask of the block entity carrying it.
 *
 * Narrow on purpose. A control needs to know where it is, so that it can play a
 * sound or look at the world, and it needs to be able to say that its own state
 * has changed so the host saves and syncs; anything more and controls would start
 * reaching into the host's slot table, which is the one thing that must stay in
 * one pair of hands.
 */
public interface ControlHost {

    Level level();

    BlockPos pos();

    /** Persist and sync the host after one of its controls changed its own state. */
    void controlChanged();
}
