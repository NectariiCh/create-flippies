package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.foundation.wire.entity.EntityCordEndpoint;
import com.nectariich.create_flippies.foundation.wire.entity.EntityWireEndpoint;

import net.minecraft.nbt.CompoundTag;

import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reads back the endpoints that put Power Grid's wires on entities. Power Grid's
 * endpoint types are an enum that cannot grow, so {@link EntityWireEndpoint} and
 * {@link EntityCordEndpoint} save themselves under existing types with a tag of
 * their own added; every saved endpoint (a cord entity's ends, a cord item's held
 * end, the network's saved line parts) is read through here, and a tag carrying
 * that mark is made into ours instead.
 */
@Mixin(value = WireEndpointType.class, remap = false)
public class WireEndpointTypeMixin {

    @Inject(method = "deserialize", at = @At("HEAD"), cancellable = true)
    private static void create_flippies$entityEndpoints(CompoundTag tag, CallbackInfoReturnable<IWireEndpoint> cir) {
        if (tag == null || !tag.hasUUID(EntityWireEndpoint.TAG))
            return;
        IWireEndpoint endpoint = "cord".equals(tag.getString(EntityWireEndpoint.KIND)) ? new EntityCordEndpoint()
                : new EntityWireEndpoint();
        endpoint.read(tag);
        cir.setReturnValue(endpoint);
    }
}
