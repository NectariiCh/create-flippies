package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.content.pc.PcSnapshots;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * One piece of a PC snapshot the server asked the player's computer for (spec 12.19), or, with
 * {@code error} set, why the client cannot send it. The piece is read against
 * {@link PcSnapshots#UPLOAD_CHUNK}, so an oversized one is refused before anything is allocated.
 */
public class SnapshotUploadPacket implements FlippiesPacket {

    private final int transfer;
    private final int index;
    private final int total;
    private final String error;
    private final byte[] bytes;

    public SnapshotUploadPacket(int transfer, int index, int total, String error, byte[] bytes) {
        this.transfer = transfer;
        this.index = index;
        this.total = total;
        this.error = error;
        this.bytes = bytes;
    }

    public SnapshotUploadPacket(FriendlyByteBuf buffer) {
        transfer = buffer.readVarInt();
        index = buffer.readVarInt();
        total = buffer.readVarInt();
        error = buffer.readUtf(256);
        bytes = buffer.readByteArray(PcSnapshots.UPLOAD_CHUNK);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(transfer);
        buffer.writeVarInt(index);
        buffer.writeVarInt(total);
        buffer.writeUtf(error, 256);
        buffer.writeByteArray(bytes);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            PcServer server = PcServer.get();
            if (player != null && server != null)
                server.snapshots().uploadPiece(player, transfer, index, total, error, bytes);
        });
    }
}
