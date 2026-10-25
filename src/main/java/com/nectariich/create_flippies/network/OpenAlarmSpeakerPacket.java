package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Tells one player's client to open the menu of one mounted alarm speaker, for the
 * reason {@link OpenAlphanumDisplayPacket} gives: a host hands clicks to its controls
 * on the server alone.
 */
public class OpenAlarmSpeakerPacket {

    private final BlockPos pos;
    private final ControlMount mount;

    public OpenAlarmSpeakerPacket(BlockPos pos, ControlMount mount) {
        this.pos = pos;
        this.mount = mount;
    }

    public OpenAlarmSpeakerPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.AlarmSpeakerScreen.open(pos, mount)));
        context.get().setPacketHandled(true);
    }
}
