package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Tells one player's client to open the editor for one mounted panel label.
 *
 * The instrument blocks open their menus straight from the client's own pass of the
 * interaction, but a mounted control cannot: a host block hands a click to its
 * controls on the server alone. So the click is answered on the server and the screen
 * is asked for from there, which also means the server has already decided this player
 * may edit this label before the screen exists.
 *
 * The label is named by its mount rather than by its place in the host's list, for
 * the reason {@code PanelLabel.at} gives.
 */
public class OpenPanelLabelPacket {

    private final BlockPos pos;
    private final ControlMount mount;

    public OpenPanelLabelPacket(BlockPos pos, ControlMount mount) {
        this.pos = pos;
        this.mount = mount;
    }

    public OpenPanelLabelPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.PanelLabelScreen.open(pos, mount)));
        context.get().setPacketHandled(true);
    }
}
