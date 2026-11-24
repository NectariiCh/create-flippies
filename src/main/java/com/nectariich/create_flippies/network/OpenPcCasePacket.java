package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.content.pc.PcStatus;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Tells one player's client to open a PC case's screen (spec 11.2.11), with the
 * status as of the click so it opens on real figures. Sent from the server only,
 * after a screwdriver click on the case.
 */
public class OpenPcCasePacket implements FlippiesPacket {

    private final int caseId;
    private final PcStatus status;

    public OpenPcCasePacket(int caseId, PcStatus status) {
        this.caseId = caseId;
        this.status = status;
    }

    public OpenPcCasePacket(FriendlyByteBuf buffer) {
        caseId = buffer.readVarInt();
        status = PcStatus.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(caseId);
        status.encode(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.pc.PcCaseScreen.open(caseId, status)));
    }
}
