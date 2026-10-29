package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/** The client has written {@code received} pieces of a snapshot download (spec 12.19). */
public class SnapshotAckPacket implements FlippiesPacket {

    private final int transfer;
    private final int received;

    public SnapshotAckPacket(int transfer, int received) {
        this.transfer = transfer;
        this.received = received;
    }

    public SnapshotAckPacket(FriendlyByteBuf buffer) {
        transfer = buffer.readVarInt();
        received = buffer.readVarInt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(transfer);
        buffer.writeVarInt(received);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            PcServer server = PcServer.get();
            if (player != null && server != null)
                server.snapshots().downloadAcked(player, transfer, received);
        });
    }
}
