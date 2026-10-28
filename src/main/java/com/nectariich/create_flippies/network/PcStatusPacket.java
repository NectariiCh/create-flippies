package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.pc.PcStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** A case's status, in answer to a {@link PcActionPacket}, for the screen that asked. */
public class PcStatusPacket implements FlippiesPacket {

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

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.pc.PcCaseScreen.statusArrived(pos, status)));
    }
}
