package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.speaker.AlarmSpeaker;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerBlock;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerBlockEntity;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerControl;
import com.nectariich.create_flippies.content.speaker.SpeakerSettings;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Applies the menu's settings — all four channels — to one alarm speaker, in either
 * form; a null mount means the block at {@code pos} is the speaker. Shaped exactly
 * like {@link ConfigureAlphanumDisplayPacket}, turn included. Every field is clamped
 * by {@link SpeakerSettings#decode}.
 */
public class ConfigureAlarmSpeakerPacket {

    private final BlockPos pos;
    private final SpeakerSettings settings;
    private final boolean turn;
    private final ControlMount mount;

    public ConfigureAlarmSpeakerPacket(BlockPos pos, SpeakerSettings settings, boolean turn, ControlMount mount) {
        this.pos = pos;
        this.settings = settings;
        this.turn = turn;
        this.mount = mount;
    }

    public ConfigureAlarmSpeakerPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        settings = SpeakerSettings.decode(buffer);
        turn = buffer.readBoolean();
        mount = buffer.readBoolean() ? ControlMount.read(buffer) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        settings.encode(buffer);
        buffer.writeBoolean(turn);
        buffer.writeBoolean(mount != null);
        if (mount != null)
            mount.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null)
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64)
                return;
            if (mount != null) {
                AlarmSpeakerControl speaker = AlarmSpeaker.at(level.getBlockEntity(pos), mount);
                if (speaker != null)
                    speaker.configure(settings);
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof AlarmSpeakerBlockEntity speaker))
                return;
            speaker.configure(settings);
            if (turn)
                AlarmSpeakerBlock.turn(level, pos);
        });
        context.get().setPacketHandled(true);
    }
}
