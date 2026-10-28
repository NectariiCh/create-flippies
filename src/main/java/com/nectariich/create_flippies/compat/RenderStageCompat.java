package com.nectariich.create_flippies.compat;

import net.minecraftforge.client.event.RenderLevelStageEvent;

/** {@link RenderLevelStageEvent} accessors whose return types differ between the versions. */
public final class RenderStageCompat {

    private RenderStageCompat() {}

    /**
     * The frame's partial tick. 1.21.1 hands out a {@code DeltaTracker} instead of the
     * float; the game-time value of it is what 1.20.1's float was.
     */
    public static float partialTick(RenderLevelStageEvent event) {
        //? if forge {
        return event.getPartialTick();
        //?} else {
        /*return event.getPartialTick().getGameTimeDeltaPartialTick(false);
        *///?}
    }
}
