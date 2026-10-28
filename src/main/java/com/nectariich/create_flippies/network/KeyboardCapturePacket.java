package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * A client asks to start typing on a keyboard ({@code capture} true) or releases it
 * after a double Escape (false) (spec 11.2.12). The server answers a request with a
 * {@link KeyboardCaptureStatePacket}.
 */
public class KeyboardCapturePacket implements FlippiesPacket {

    private final int id;
    private final boolean capture;

    public KeyboardCapturePacket(int id, boolean capture) {
        this.id = id;
        this.capture = capture;
    }

    public KeyboardCapturePacket(FriendlyByteBuf buffer) {
        id = buffer.readVarInt();
        capture = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(id);
        buffer.writeBoolean(capture);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            PcServer server = PcServer.get();
            if (player == null || server == null)
                return;
            if (capture)
                server.captures().request(player, id);
            else
                server.captures().release(player, id);
        });
    }
}
