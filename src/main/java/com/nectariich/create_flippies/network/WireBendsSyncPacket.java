package com.nectariich.create_flippies.network;

import java.util.List;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.wire.bend.LegSag;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * A hanging wire's bend points as the server has them, after an edit or in answer to a refused one.
 * WIRE SAG: with the sag of each leg, or none for a wire that has no sag of its own.
 */
public class WireBendsSyncPacket implements FlippiesPacket {

    private final int entityId;
    private final List<Vec3> points;
    private final float[] legSags;

    public WireBendsSyncPacket(int entityId, List<Vec3> points, float[] legSags) {
        this.entityId = entityId;
        this.points = points;
        this.legSags = legSags;
    }

    public WireBendsSyncPacket(FriendlyByteBuf buffer) {
        entityId = buffer.readVarInt();
        points = WireBends.read(buffer);
        legSags = LegSag.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        WireBends.write(buffer, points);
        LegSag.write(buffer, legSags);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.wire.WireBendInput.received(entityId, points,
                        legSags)));
    }
}
