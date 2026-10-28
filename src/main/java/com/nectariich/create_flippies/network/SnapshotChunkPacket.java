package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.pc.PcSnapshot;
import com.nectariich.create_flippies.content.pc.PcSnapshots;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One piece of a PC snapshot on its way to the player's computer (spec 12.19). The pieces of
 * one transfer come in order; the client writes each and answers with a {@link SnapshotAckPacket}.
 */
public class SnapshotChunkPacket implements FlippiesPacket {

    private final int transfer;
    private final String name;
    private final int index;
    private final int total;
    private final long size;
    private final byte[] bytes;

    public SnapshotChunkPacket(int transfer, String name, int index, int total, long size, byte[] bytes) {
        this.transfer = transfer;
        this.name = name;
        this.index = index;
        this.total = total;
        this.size = size;
        this.bytes = bytes;
    }

    public SnapshotChunkPacket(FriendlyByteBuf buffer) {
        transfer = buffer.readVarInt();
        name = buffer.readUtf(64);
        index = buffer.readVarInt();
        total = buffer.readVarInt();
        size = buffer.readLong();
        bytes = buffer.readByteArray(PcSnapshots.DOWNLOAD_CHUNK);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(transfer);
        buffer.writeUtf(name, 64);
        buffer.writeVarInt(index);
        buffer.writeVarInt(total);
        buffer.writeLong(size);
        buffer.writeByteArray(bytes);
    }

    public void handle(PacketContext context) {
        if (!PcSnapshot.validName(name))
            return;
        context.enqueueWork(() -> ClientOnly.run(() -> () -> com.nectariich.create_flippies.client.ClientSnapshots
                .receive(transfer, name, index, total, size, bytes)));
    }
}
