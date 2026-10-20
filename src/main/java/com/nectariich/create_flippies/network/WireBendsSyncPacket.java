package com.nectariich.create_flippies.network;

import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** A hanging wire's bend points as the server has them, after an edit or in answer to a refused one. */
public class WireBendsSyncPacket {

    private final int entityId;
    private final List<Vec3> points;

    public WireBendsSyncPacket(int entityId, List<Vec3> points) {
        this.entityId = entityId;
        this.points = points;
    }

    public WireBendsSyncPacket(FriendlyByteBuf buffer) {
        entityId = buffer.readVarInt();
        points = WireBends.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        WireBends.write(buffer, points);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.wire.WireBendInput.received(entityId, points)));
        context.get().setPacketHandled(true);
    }
}
