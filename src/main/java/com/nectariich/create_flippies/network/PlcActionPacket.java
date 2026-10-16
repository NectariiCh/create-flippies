package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.plc.PlcSmallControl;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * Something a PLC's open menu asks of the server: a fresh status for the live pane,
 * or a press of the menu's Reset. Either way the reply is a {@link PlcStatusPacket}
 * to this player alone.
 *
 * The menu polls with this twice a second for as long as it is open, which is why
 * the status is fetched rather than synced: it costs nothing for a PLC nobody is
 * looking at. A poll needs only that the player could have opened the menu; a reset
 * needs that they may change the world as well.
 */
public class PlcActionPacket {

    public enum Action {
        POLL,
        RESET
    }

    private final BlockPos pos;
    private final ControlMount mount;
    private final Action action;

    public PlcActionPacket(BlockPos pos, ControlMount mount, Action action) {
        this.pos = pos;
        this.mount = mount;
        this.action = action;
    }

    public PlcActionPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
        action = buffer.readEnum(Action.class);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
        buffer.writeEnum(action);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            PlcSmallControl plc = PlcSmallControl.reachable(player, pos, mount, action == Action.RESET);
            if (plc == null)
                return;
            if (action == Action.RESET)
                plc.pressReset();
            ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PlcStatusPacket(pos, mount, plc.status()));
        });
        context.get().setPacketHandled(true);
    }
}
