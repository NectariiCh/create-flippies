package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.plc.PlcStatus;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** A PLC's live status, in answer to a {@link PlcActionPacket}, for the menu that asked. */
public class PlcStatusPacket {

    private final BlockPos pos;
    private final ControlMount mount;
    private final PlcStatus status;

    public PlcStatusPacket(BlockPos pos, ControlMount mount, PlcStatus status) {
        this.pos = pos;
        this.mount = mount;
        this.status = status;
    }

    public PlcStatusPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
        status = PlcStatus.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
        status.encode(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.PlcScreen.statusArrived(pos, mount, status)));
        context.get().setPacketHandled(true);
    }
}
