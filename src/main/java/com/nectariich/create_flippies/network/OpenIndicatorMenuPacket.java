package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Tells one player's client to open the lens menu for one mounted indicator lamp.
 *
 * The block form opens the same menu straight from the client's own pass of the
 * screwdriver click, and a mounted unit cannot: a host hands a click to its controls
 * on the server alone. So the click is answered on the server and the screen asked for
 * from there — which also means the server has decided this player may refit this unit
 * before the screen exists.
 *
 * Sent from two places, both server-side: a sneaking screwdriver click on the panel,
 * and the screwdriver immediately after mounting a unit, so its cap and its legend can
 * be chosen as it goes on rather than hunted for again once it is flush in a row of
 * others.
 */
public class OpenIndicatorMenuPacket implements FlippiesPacket {

    private final BlockPos pos;
    private final ControlMount mount;

    public OpenIndicatorMenuPacket(BlockPos pos, ControlMount mount) {
        this.pos = pos;
        this.mount = mount;
    }

    public OpenIndicatorMenuPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.IndicatorScreen.open(pos, mount)));
    }
}
