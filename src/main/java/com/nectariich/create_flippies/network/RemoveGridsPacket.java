package com.nectariich.create_flippies.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.grid.ClientGridStore;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * The grids a removal took with it, sent to everyone watching the chunks the
 * outermost one covered.
 *
 * It is a list rather than a single id because removing a grid removes everything
 * nested inside it, and the client has to be told about the whole subtree in one
 * message: told only about the parent it would work out the same subtree itself,
 * which is the sort of duplicated rule that goes wrong the first time the nesting
 * constraints change.
 */
public class RemoveGridsPacket {

    private final List<UUID> ids;

    public RemoveGridsPacket(List<UUID> ids) {
        this.ids = ids;
    }

    public RemoveGridsPacket(FriendlyByteBuf buffer) {
        ids = buffer.readCollection(ArrayList::new, FriendlyByteBuf::readUUID);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeCollection(ids, FriendlyByteBuf::writeUUID);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> ClientGridStore.forget(ids));
        context.get().setPacketHandled(true);
    }
}
