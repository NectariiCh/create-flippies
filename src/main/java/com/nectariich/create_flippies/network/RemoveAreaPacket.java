package com.nectariich.create_flippies.network;

import java.util.UUID;

import com.nectariich.create_flippies.foundation.protection.ClientProtectionStore;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The one area a removal took away, sent to everyone watching the chunks it
 * covered.
 *
 * One id rather than a list, unlike the grid packet it otherwise mirrors: areas do
 * not nest, so removing one never implies removing another, and a client told about
 * this id has been told everything that changed.
 */
public class RemoveAreaPacket implements FlippiesPacket {

    private final UUID id;

    public RemoveAreaPacket(UUID id) {
        this.id = id;
    }

    public RemoveAreaPacket(FriendlyByteBuf buffer) {
        id = buffer.readUUID();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(id);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientProtectionStore.forget(id));
    }
}
