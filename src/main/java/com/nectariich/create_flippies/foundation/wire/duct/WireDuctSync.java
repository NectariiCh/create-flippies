package com.nectariich.create_flippies.foundation.wire.duct;

import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.network.DuctRouteSyncPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.PacketDistributor;

/** Tells every client watching a wire routed through ducts what its path now is. */
public final class WireDuctSync {

    private WireDuctSync() {}

    public static void sync(Entity entity) {
        if (entity.level().isClientSide || !(entity instanceof BendableWire wire))
            return;
        ModPackets.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity),
                new DuctRouteSyncPacket(entity.getId(), wire.bendPoints(), wire.bendDuctLegs()));
    }
}
