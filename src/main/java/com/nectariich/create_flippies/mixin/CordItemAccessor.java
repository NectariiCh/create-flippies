package com.nectariich.create_flippies.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

import org.patryk3211.powergrid.electricity.wire.powercord.CordItem;
import org.patryk3211.powergrid.electricity.wire.powercord.ICordEndpoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Power Grid's own step for a cord used on a socket: remember the first end in the
 * stack, or join it to the end already there. Used for sockets on entities, which
 * Power Grid's block click handling never sees.
 */
@Mixin(value = CordItem.class, remap = false)
public interface CordItemAccessor {

    @Invoker("addEndpoint")
    static InteractionResult create_flippies$addEndpoint(UseOnContext context, ICordEndpoint endpoint) {
        throw new AssertionError();
    }
}
