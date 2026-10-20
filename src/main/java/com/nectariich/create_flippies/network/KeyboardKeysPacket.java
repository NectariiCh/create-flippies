package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.keyboard.KeyboardEvents;
import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * One client tick of key events from the typist (spec 11.2.12), each
 * {@code Ps2Key} ordinal << 1 | down, at most {@link KeyboardEvents#MAX_PER_PACKET}.
 * The server checks the sender is the keyboard's typist and still in reach.
 */
public class KeyboardKeysPacket {

    private final BlockPos pos;
    private final short[] events;

    public KeyboardKeysPacket(BlockPos pos, short[] events) {
        this.pos = pos;
        this.events = events;
    }

    public KeyboardKeysPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        int count = Math.min(buffer.readVarInt(), KeyboardEvents.MAX_PER_PACKET);
        events = new short[count];
        for (int i = 0; i < count; i++)
            events[i] = buffer.readShort();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        int count = Math.min(events.length, KeyboardEvents.MAX_PER_PACKET);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++)
            buffer.writeShort(events[i]);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            PcServer server = PcServer.get();
            if (player != null && server != null)
                server.captures().keys(player, pos, events);
        });
        context.get().setPacketHandled(true);
    }
}
