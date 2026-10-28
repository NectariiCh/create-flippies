package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** One chunk of an imported model on its way to the world's design store. */
public class DesignUploadPacket implements FlippiesPacket {

    private final String hash;
    private final int index;
    private final int total;
    private final byte[] bytes;

    public DesignUploadPacket(String hash, int index, int total, byte[] bytes) {
        this.hash = hash;
        this.index = index;
        this.total = total;
        this.bytes = bytes;
    }

    public DesignUploadPacket(FriendlyByteBuf buffer) {
        hash = buffer.readUtf(32);
        index = buffer.readVarInt();
        total = buffer.readVarInt();
        bytes = buffer.readByteArray(DesignTransfers.UPLOAD_CHUNK);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(hash, 32);
        buffer.writeVarInt(index);
        buffer.writeVarInt(total);
        buffer.writeByteArray(bytes);
    }

    public void handle(PacketContext context) {
        ServerPlayer player = context.sender();
        if (player != null)
            context.enqueueWork(() -> DesignTransfers.receive(player, hash, index, total, bytes));
    }
}
