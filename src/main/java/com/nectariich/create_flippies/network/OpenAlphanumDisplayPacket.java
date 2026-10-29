package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Tells one player's client to open the element editor for one mounted display: an
 * alphanumeric display today, and any other control that shows
 * {@link com.nectariich.create_flippies.content.display.element.ScreenElements} (the
 * designed screen) once it exists, since the editor finds its display through
 * {@link com.nectariich.create_flippies.content.display.element.ElementDisplays#at}
 * and cares nothing for which kind it is.
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
 * The screen class is named only inside the lambda, so a dedicated server that
 * loads this packet never loads it.
 *
 * The display is named by its mount rather than by its place in the host's list, for
 * the reason {@code AlphanumDisplay.at} gives.
 */
public class OpenAlphanumDisplayPacket implements FlippiesPacket {

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

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.display.ScreenEditorScreen.open(pos, mount)));
    }
}
