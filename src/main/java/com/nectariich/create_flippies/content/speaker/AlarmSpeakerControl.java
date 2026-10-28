package com.nectariich.create_flippies.content.speaker;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenAlarmSpeakerPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;


import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * An alarm speaker mounted inside a host block: the same horn, barrel, pins and
 * settings as {@link AlarmSpeakerBlockEntity}, on a panel.
 */
public class AlarmSpeakerControl extends HostedControl {

    private SpeakerSettings settings = new SpeakerSettings();
    private IElectricNode[] nodes;

    AlarmSpeakerControl(AlarmSpeakerControlType type) {
        super(type);
    }

    public SpeakerKind kind() {
        return ((AlarmSpeakerControlType) type()).kind();
    }

    public SpeakerSettings settings() {
        return settings;
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        nodes = AlarmSpeaker.buildCircuit(builder, kind());
    }

    @Override
    public void tick() {
        if (host() == null || mount() == null)
            return;
        Level level = host().level();
        if (level == null || !level.isClientSide)
            return;
        // The host and the mount together are what identifies a control across ticks
        // and syncs; a record of the two is a key that survives this instance being
        // rebuilt by a sync
        AlarmSpeaker.clientTick(level, new Key(hostPos(), mount()),
                AlarmSpeaker.hornPosition(mount()).add(Vec3.atLowerCornerOf(hostPos())), kind(), settings, nodes);
    }

    private record Key(BlockPos pos, ControlMount mount) {}

    /** Called server-side when the menu's packet arrives. */
    public void configure(SpeakerSettings settings) {
        this.settings = settings;
        changed();
    }

    @Override
    public boolean hasEditor() {
        return true;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        if (host() == null)
            return false;
        ModPackets.sendToPlayer(player, new OpenAlarmSpeakerPacket(host().pos(), mount()));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        CompoundTag speaker = new CompoundTag();
        settings.write(speaker);
        tag.put("Speaker", speaker);
    }

    @Override
    public void read(CompoundTag tag) {
        settings = SpeakerSettings.read(tag.getCompound("Speaker"));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return AlarmSpeaker.addTooltip(tooltip, kind(), settings, nodes);
    }
}
