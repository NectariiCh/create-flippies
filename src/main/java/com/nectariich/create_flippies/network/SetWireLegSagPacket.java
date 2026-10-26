package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * WIRE SAG: a player dragging the sag of one leg of a hanging wire. The wire is named
 * by entity id, the leg by its index along the path, and the packet carries how many
 * bend points the sender saw: a point added or removed meanwhile (another player's
 * edit) shifts the legs, so the index would name the wrong one and the server drops
 * it. The server checks all of it in {@link WireBendService#setLegSag}.
 */
public class SetWireLegSagPacket {

    private final int entityId;
    private final int leg;
    private final int pointCount;
    private final float sag;

    public SetWireLegSagPacket(int entityId, int leg, int pointCount, float sag) {
        this.entityId = entityId;
        this.leg = leg;
        this.pointCount = pointCount;
        this.sag = sag;
    }

    public SetWireLegSagPacket(FriendlyByteBuf buffer) {
        entityId = buffer.readVarInt();
        leg = buffer.readVarInt();
        pointCount = buffer.readVarInt();
        sag = buffer.readFloat();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeVarInt(leg);
        buffer.writeVarInt(pointCount);
        buffer.writeFloat(sag);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null)
                WireBendService.setLegSag(player, entityId, leg, pointCount, sag);
        });
        context.get().setPacketHandled(true);
    }
}
