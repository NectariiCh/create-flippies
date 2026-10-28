package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;

import net.minecraft.network.FriendlyByteBuf;

/** One chunk of a model the server is sending a client that asked for it. */
public class DesignDataPacket implements FlippiesPacket {

    private final String hash;
    private final int index;
    private final int total;
    private final byte[] bytes;

    public DesignDataPacket(String hash, int index, int total, byte[] bytes) {
        this.hash = hash;
        this.index = index;
        this.total = total;
        this.bytes = bytes;
    }

    public DesignDataPacket(FriendlyByteBuf buffer) {
        hash = buffer.readUtf(32);
        index = buffer.readVarInt();
        total = buffer.readVarInt();
        bytes = buffer.readByteArray(DesignTransfers.DOWNLOAD_CHUNK);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(hash, 32);
        buffer.writeVarInt(index);
        buffer.writeVarInt(total);
        buffer.writeByteArray(bytes);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.design.ClientDesigns.receive(hash, index, total,
                        bytes)));
    }
}
