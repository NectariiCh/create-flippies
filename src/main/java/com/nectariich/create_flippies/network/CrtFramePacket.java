package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.crt.CrtFrameCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * One picture update for the CRT at {@code pos} (spec 11.2.14), to one player: a
 * {@link CrtFrameCodec} payload, a full frame or the changed rows, deflated.
 */
public class CrtFramePacket {

    /** The largest payload a client reads: a whole frame that did not deflate at all, and its header. */
    private static final int MAX_PAYLOAD = CrtFrameCodec.MAX_FRAME_BYTES + 4096;

    private final BlockPos pos;
    private final byte[] payload;

    public CrtFramePacket(BlockPos pos, byte[] payload) {
        this.pos = pos;
        this.payload = payload;
    }

    public CrtFramePacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        payload = buffer.readByteArray(MAX_PAYLOAD);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByteArray(payload);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.pc.CrtClient.frame(pos, payload)));
        context.get().setPacketHandled(true);
    }
}
