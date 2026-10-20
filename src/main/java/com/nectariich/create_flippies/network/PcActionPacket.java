package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * Something an open case screen asks of the server (spec 11.2.11). Only a poll for
 * now: the screen asks every 10 ticks while it is open, and the answer is a
 * {@link PcStatusPacket} to this player alone, if they are within 16 blocks.
 */
public class PcActionPacket {

    public enum Action {
        POLL
    }

    private final BlockPos pos;
    private final Action action;

    public PcActionPacket(BlockPos pos, Action action) {
        this.pos = pos;
        this.action = action;
    }

    public PcActionPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        action = buffer.readEnum(Action.class);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeEnum(action);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null || !player.level().isLoaded(pos))
                return;
            if (!(player.level().getBlockEntity(pos) instanceof PcCaseBlockEntity pc) || !pc.reachable(player))
                return;
            ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PcStatusPacket(pos, pc.status()));
        });
        context.get().setPacketHandled(true);
    }
}
