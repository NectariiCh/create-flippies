package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * One client tick of mouse input from the typist on a keyboard and mouse (spec
 * 11.2.15): movement in PS/2 counts, dy positive up, and the buttons held (bit 0
 * left, 1 right, 2 middle). The server checks the sender as for key packets.
 */
public class KeyboardMousePacket {

    private final int id;
    private final int dx;
    private final int dy;
    private final int buttons;

    public KeyboardMousePacket(int id, int dx, int dy, int buttons) {
        this.id = id;
        this.dx = dx;
        this.dy = dy;
        this.buttons = buttons;
    }

    public KeyboardMousePacket(FriendlyByteBuf buffer) {
        id = buffer.readVarInt();
        dx = buffer.readVarInt();
        dy = buffer.readVarInt();
        buttons = buffer.readByte();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(id);
        buffer.writeVarInt(dx);
        buffer.writeVarInt(dy);
        buffer.writeByte(buttons);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            PcServer server = PcServer.get();
            if (player != null && server != null)
                server.captures().mouse(player, id, dx, dy, buttons);
        });
        context.get().setPacketHandled(true);
    }
}
