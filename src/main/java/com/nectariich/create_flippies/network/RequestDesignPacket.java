package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** A client has met a designed control whose model it has not got. */
public class RequestDesignPacket {

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

    public void handle(Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        if (player != null)
            context.get().enqueueWork(() -> DesignTransfers.send(player, hash));
        context.get().setPacketHandled(true);
    }
}
