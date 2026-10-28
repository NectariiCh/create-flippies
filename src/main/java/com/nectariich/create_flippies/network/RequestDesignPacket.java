package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** A client has met a designed control whose model it has not got. */
public class RequestDesignPacket implements FlippiesPacket {

    private final String hash;

    public RequestDesignPacket(String hash) {
        this.hash = hash;
    }

    public RequestDesignPacket(FriendlyByteBuf buffer) {
        hash = buffer.readUtf(32);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(hash, 32);
    }

    public void handle(PacketContext context) {
        ServerPlayer player = context.sender();
        if (player != null)
            context.enqueueWork(() -> DesignTransfers.send(player, hash));
    }
}
