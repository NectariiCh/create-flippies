package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One chunk of a store file a client fetched to keep a blueprint locally: a world
 * blueprint moved to the local section or starred as a favourite needs its model
 * and fonts on this machine, so it can be made or edited in any world.
 *
 * Separate from {@link DesignDataPacket}, which feeds the renderer's model cache:
 * the bytes here go to the blueprint files, and a font has no business being parsed
 * as a model.
 */
public class BlueprintBlobPacket implements FlippiesPacket {

    private final String hash;
    private final int index;
    private final int total;
    private final byte[] bytes;

    public BlueprintBlobPacket(String hash, int index, int total, byte[] bytes) {
        this.hash = hash;
        this.index = index;
        this.total = total;
        this.bytes = bytes;
    }

    public BlueprintBlobPacket(FriendlyByteBuf buffer) {
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
                () -> () -> com.nectariich.create_flippies.client.blueprint.ClientBlueprints.receiveBlob(hash, index,
                        total, bytes)));
    }
}
