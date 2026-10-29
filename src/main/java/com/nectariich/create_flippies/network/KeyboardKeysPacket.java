package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.keyboard.KeyboardEvents;
import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * One client tick of key events from the typist (spec 11.2.12), each
 * {@code Ps2Key} ordinal << 1 | down, at most {@link KeyboardEvents#MAX_PER_PACKET}.
 * The server checks the sender is the keyboard's typist and still in reach.
 */
public class KeyboardKeysPacket implements FlippiesPacket {

    private final int id;
    private final short[] events;

    public KeyboardKeysPacket(int id, short[] events) {
        this.id = id;
        this.events = events;
    }

    public KeyboardKeysPacket(FriendlyByteBuf buffer) {
        id = buffer.readVarInt();
        int count = Math.min(buffer.readVarInt(), KeyboardEvents.MAX_PER_PACKET);
        events = new short[count];
        for (int i = 0; i < count; i++)
            events[i] = buffer.readShort();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(id);
        int count = Math.min(events.length, KeyboardEvents.MAX_PER_PACKET);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++)
            buffer.writeShort(events[i]);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            PcServer server = PcServer.get();
            if (player != null && server != null)
                server.captures().keys(player, id, events);
        });
    }
}
