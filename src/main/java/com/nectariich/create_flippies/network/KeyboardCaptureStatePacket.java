package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Whether a player now types on a keyboard (spec 11.2.12): the answer to a request,
 * or the end of a capture the server ended (walked away, cord broken, and so on).
 * {@code reason} names why it was refused or ended; empty when granted.
 */
public class KeyboardCaptureStatePacket implements FlippiesPacket {

    private static final int MAX_REASON = 32;

    private final int id;
    private final boolean granted;
    private final String reason;

    public KeyboardCaptureStatePacket(int id, boolean granted, String reason) {
        this.id = id;
        this.granted = granted;
        this.reason = reason;
    }

    public KeyboardCaptureStatePacket(FriendlyByteBuf buffer) {
        id = buffer.readVarInt();
        granted = buffer.readBoolean();
        reason = buffer.readUtf(MAX_REASON);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(id);
        buffer.writeBoolean(granted);
        buffer.writeUtf(reason, MAX_REASON);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.pc.KeyboardClient.captureState(id, granted, reason)));
    }
}
