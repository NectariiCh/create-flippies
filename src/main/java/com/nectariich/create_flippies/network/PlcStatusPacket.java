package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.plc.PlcStatus;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** A PLC's live status, in answer to a {@link PlcActionPacket}, for the menu that asked. */
public class PlcStatusPacket implements FlippiesPacket {

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

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.PlcScreen.statusArrived(pos, mount, status)));
    }
}
