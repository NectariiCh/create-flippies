package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Seats the far end of a freshly placed block wire on the terminal it was aimed
 * at, in the two places a placement finishes.
 *
 * The route stops on a cell centre next to the target rather than on the target,
 * and the near end has moved onto the terminal origin under it, so the chain is
 * short by a fraction of a pixel on each axis. Both hooks fire on the server after
 * the second endpoint is known and before the result leaves for the client, so the
 * correction is made once and then persists as segment lengths — a wire is never
 * re-snapped on load, where an unloaded endpoint would answer with a block centre.
 *
 * A wire that reaches an existing wire instead of a terminal goes through
 * {@code mergeWires}, which is not touched: it joins two chains that were each
 * already seated when they were placed.
 */
@Mixin(value = WireItem.class, remap = false)
public class WireItemMixin {

    @Redirect(
            method = "connect",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;"
                            + "tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z",
                    remap = true))
    private static boolean create_flippies$snapBeforeSpawn(ServerLevel level, Entity entity) {
        if (entity instanceof BlockWireEntity wire)
            BlockWireGeometry.snapFarEnd(wire);
        return level.tryAddFreshEntityWithPassengers(entity);
    }

    /** The other ending: a wire extended from its own end onto a new terminal. */
    @Redirect(
            method = "connect",
            at = @At(value = "INVOKE",
                    target = "Lorg/patryk3211/powergrid/electricity/wire/BlockWireEntity;sendExtraData()V"))
    private static void create_flippies$snapBeforeSync(BlockWireEntity wire) {
        BlockWireGeometry.snapFarEnd(wire);
        wire.sendExtraData();
    }
}
