package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.plc.PlcStatus;
import com.nectariich.create_flippies.content.plc.PlcToolchain;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Tells one player's client to open the menu of one DIN-rail PLC, with the sketch it
 * holds and how it is doing right now.
 *
 * The sketch travels here, in readable form, so the menu does not depend on what
 * the client last heard from the host's sync. That tag does also carry it, deflated,
 * because the host writes one tag for disk and network alike and the source must be
 * saved; but the menu is opened from this packet's copy, which is the server's text
 * as of the click. The status comes along so the menu opens on real figures instead
 * of a blank pane that fills in half a second later.
 *
 * The text is at most {@link PlcToolchain#MAX_SOURCE_CHARS}: writeUtf throws on a
 * longer string, on the server thread, so the sender clips it first.
 *
 * Sent from the server only, after it has decided this player may use this PLC; see
 * {@link com.nectariich.create_flippies.content.plc.PlcSmallControl#openEditor}.
 */
public class OpenPlcPacket {

    private final BlockPos pos;
    private final ControlMount mount;
    private final String source;
    private final PlcStatus status;

    public OpenPlcPacket(BlockPos pos, ControlMount mount, String source, PlcStatus status) {
        this.pos = pos;
        this.mount = mount;
        this.source = source;
        this.status = status;
    }

    public OpenPlcPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
        source = buffer.readUtf(PlcToolchain.MAX_SOURCE_CHARS);
        status = PlcStatus.decode(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
        buffer.writeUtf(source, PlcToolchain.MAX_SOURCE_CHARS);
        status.encode(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.PlcScreen.open(pos, mount, source, status)));
        context.get().setPacketHandled(true);
    }
}
