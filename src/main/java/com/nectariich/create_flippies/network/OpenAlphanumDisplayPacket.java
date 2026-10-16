package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Tells one player's client to open the editor for one mounted alphanumeric display.
 *
 * The block form opens the same menu straight from the client's own pass of the
 * screwdriver click, and a mounted device cannot: a host hands a click to its
 * controls on the server alone. So the click is answered on the server and the screen
 * asked for from there — which also means the server has decided this player may
 * reconfigure this display before the screen exists.
 *
 * Sent from two places, both server-side: a screwdriver click on the panel, and the
 * screwdriver immediately after mounting a display, so the lettering can be set as it
 * goes on rather than hunted for again once it is flush in a row of others.
 *
 * The display is named by its mount rather than by its place in the host's list, for
 * the reason {@code AlphanumDisplay.at} gives.
 */
public class OpenAlphanumDisplayPacket {

    private final BlockPos pos;
    private final ControlMount mount;

    public OpenAlphanumDisplayPacket(BlockPos pos, ControlMount mount) {
        this.pos = pos;
        this.mount = mount;
    }

    public OpenAlphanumDisplayPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.AlphanumDisplayScreen.open(pos, mount)));
        context.get().setPacketHandled(true);
    }
}
