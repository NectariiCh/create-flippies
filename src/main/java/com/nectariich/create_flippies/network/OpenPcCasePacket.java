package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.pc.PcStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Tells one player's client to open a PC case's screen (spec 11.2.11), with the
 * status as of the click so it opens on real figures. Sent from the server only,
 * after a screwdriver click on the case.
 */
public class OpenPcCasePacket {

    private final BlockPos pos;
    private final PcStatus status;

    public OpenPcCasePacket(BlockPos pos, PcStatus status) {
        this.pos = pos;
        this.status = status;
    }

    public OpenPcCasePacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        status = PcStatus.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        status.encode(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.pc.PcCaseScreen.open(pos, status)));
        context.get().setPacketHandled(true);
    }
}
