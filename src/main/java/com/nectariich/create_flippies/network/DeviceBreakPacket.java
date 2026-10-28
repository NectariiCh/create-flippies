package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.device.DeviceBreaking;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player began holding the attack button on a device ({@code finished} false), or
 * held it long enough to break it (true). Devices are picked on the client, which
 * times the hold; the server checks the reach and the time again in
 * {@link DeviceBreaking}.
 */
public class DeviceBreakPacket implements FlippiesPacket {

    private final int entityId;
    private final boolean finished;

    public DeviceBreakPacket(int entityId, boolean finished) {
        this.entityId = entityId;
        this.finished = finished;
    }

    public DeviceBreakPacket(FriendlyByteBuf buffer) {
        entityId = buffer.readVarInt();
        finished = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeBoolean(finished);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player != null)
                DeviceBreaking.handle(player, entityId, finished);
        });
    }
}
