package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.pc.PcStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** A case's status, in answer to a {@link PcActionPacket}, for the screen that asked. */
public class PcStatusPacket {

    private final BlockPos pos;
    private final PcStatus status;

    public PcStatusPacket(BlockPos pos, PcStatus status) {
        this.pos = pos;
        this.status = status;
    }

    public PcStatusPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        status = PcStatus.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        status.encode(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.pc.PcCaseScreen.statusArrived(pos, status)));
        context.get().setPacketHandled(true);
    }
}
