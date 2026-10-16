package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Tells one player's client to open the dial-customization menu for one mounted
 * gauge.
 *
 * The block form opens the same menu straight from the client's own pass of the
 * screwdriver click, and a mounted gauge cannot: a host hands a click to its controls
 * on the server alone. So the click is answered on the server and the screen asked for
 * from there — which also means the server has decided this player may edit this gauge
 * before the screen exists.
 *
 * Sent twice over a gauge's life, from the same tool both times: a sneaking right
 * click with a screwdriver, and the screwdriver immediately after mounting it, so the
 * printing can be set as the gauge goes on the panel.
 *
 * The gauge is named by its mount rather than by its place in the host's list, for
 * the reason {@code PanelLabel.at} gives: no two controls may occupy the same space,
 * so a mount identifies one without depending on the order the host happens to hold
 * them in.
 */
public class OpenGaugeMenuPacket {

    private final BlockPos pos;
    private final ControlMount mount;

    public OpenGaugeMenuPacket(BlockPos pos, ControlMount mount) {
        this.pos = pos;
        this.mount = mount;
    }

    public OpenGaugeMenuPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.GaugeCustomizationScreen.open(pos, mount)));
        context.get().setPacketHandled(true);
    }
}
