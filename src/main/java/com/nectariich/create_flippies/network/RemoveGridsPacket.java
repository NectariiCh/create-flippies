package com.nectariich.create_flippies.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.foundation.grid.ClientGridStore;

import net.minecraft.network.FriendlyByteBuf;
//? if neoforge {
/*import net.minecraft.core.UUIDUtil;
*///?}

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
public class RemoveGridsPacket implements FlippiesPacket {

    private final List<UUID> ids;

    public RemoveGridsPacket(List<UUID> ids) {
        this.ids = ids;
    }

    public RemoveGridsPacket(FriendlyByteBuf buffer) {
        //? if forge {
        ids = buffer.readCollection(ArrayList::new, FriendlyByteBuf::readUUID);
        //?} else {
        /*ids = buffer.readCollection(ArrayList::new, UUIDUtil.STREAM_CODEC);
        *///?}
    }

    public void encode(FriendlyByteBuf buffer) {
        //? if forge {
        buffer.writeCollection(ids, FriendlyByteBuf::writeUUID);
        //?} else {
        /*buffer.writeCollection(ids, UUIDUtil.STREAM_CODEC);
        *///?}
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientGridStore.forget(ids));
    }
}
