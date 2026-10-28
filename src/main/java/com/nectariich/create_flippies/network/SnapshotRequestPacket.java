package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The server asks the player's computer about its PC snapshots (spec 12.19): {@link #LIST} them
 * in the chat, or {@link #UPLOAD} one, which the client answers with {@link SnapshotUploadPacket}s.
 * A client sends only what it was asked for, by the transfer number the server gave.
 */
public class SnapshotRequestPacket implements FlippiesPacket {

    public static final int LIST = 0;
    public static final int UPLOAD = 1;

    private final int kind;
    private final int transfer;
    private final String name;

    public SnapshotRequestPacket(int kind, int transfer, String name) {
        this.kind = kind;
        this.transfer = transfer;
        this.name = name;
    }

    public SnapshotRequestPacket(FriendlyByteBuf buffer) {
        kind = buffer.readVarInt();
        transfer = buffer.readVarInt();
        name = buffer.readUtf(64);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(kind);
        buffer.writeVarInt(transfer);
        buffer.writeUtf(name, 64);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(() -> () -> com.nectariich.create_flippies.client.ClientSnapshots
                .request(kind, transfer, name)));
    }
}
