package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.pc.PcCaseEntity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Something an open case screen asks of the server (spec 11.2.11). Only a poll for
 * now: the screen asks every 10 ticks while it is open, and the answer is a
 * {@link PcStatusPacket} to this player alone, if they are within 16 blocks.
 */
public class PcActionPacket implements FlippiesPacket {

    public enum Action {
        POLL
    }

    private final int caseId;
    private final Action action;

    public PcActionPacket(int caseId, Action action) {
        this.caseId = caseId;
        this.action = action;
    }

    public PcActionPacket(FriendlyByteBuf buffer) {
        caseId = buffer.readVarInt();
        action = buffer.readEnum(Action.class);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(caseId);
        buffer.writeEnum(action);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || !(player.level().getEntity(caseId) instanceof PcCaseEntity pc)
                    || !pc.reachable(player))
                return;
            ModPackets.sendToPlayer(player, new PcStatusPacket(caseId, pc.status()));
        });
    }
}
