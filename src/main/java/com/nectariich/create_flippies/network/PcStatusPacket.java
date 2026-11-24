package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.pc.PcStatus;

import net.minecraft.network.FriendlyByteBuf;

/** A case's status, in answer to a {@link PcActionPacket}, for the screen that asked. */
public class PcStatusPacket implements FlippiesPacket {

    private final int caseId;
    private final PcStatus status;

    public PcStatusPacket(int caseId, PcStatus status) {
        this.caseId = caseId;
        this.status = status;
    }

    public PcStatusPacket(FriendlyByteBuf buffer) {
        caseId = buffer.readVarInt();
        status = PcStatus.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(caseId);
        status.encode(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.pc.PcCaseScreen.statusArrived(caseId, status)));
    }
}
