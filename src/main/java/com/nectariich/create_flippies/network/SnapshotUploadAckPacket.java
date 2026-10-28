package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;

import net.minecraft.network.FriendlyByteBuf;

/** The server has {@code received} pieces of a snapshot upload; -1 means it stopped it (spec 12.19). */
public class SnapshotUploadAckPacket implements FlippiesPacket {

    private final int transfer;
    private final int received;

    public SnapshotUploadAckPacket(int transfer, int received) {
        this.transfer = transfer;
        this.received = received;
    }

    public SnapshotUploadAckPacket(FriendlyByteBuf buffer) {
        transfer = buffer.readVarInt();
        received = buffer.readVarInt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(transfer);
        buffer.writeVarInt(received);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(() -> () -> com.nectariich.create_flippies.client.ClientSnapshots
                .uploadAcked(transfer, received)));
    }
}
