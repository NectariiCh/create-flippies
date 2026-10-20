package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.pc.PcServer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * A client asks to start typing on a keyboard ({@code capture} true) or releases it
 * after a double Escape (false) (spec 11.2.12). The server answers a request with a
 * {@link KeyboardCaptureStatePacket}.
 */
public class KeyboardCapturePacket {

    private final BlockPos pos;
    private final boolean capture;

    public KeyboardCapturePacket(BlockPos pos, boolean capture) {
        this.pos = pos;
        this.capture = capture;
    }

    public KeyboardCapturePacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        capture = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeBoolean(capture);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            PcServer server = PcServer.get();
            if (player == null || server == null)
                return;
            if (capture)
                server.captures().request(player, pos);
            else
                server.captures().release(player, pos);
        });
        context.get().setPacketHandled(true);
    }
}
