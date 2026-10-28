package com.nectariich.create_flippies.content.speaker;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * The device behind an {@link AlarmSpeakerBlock}: four channels of settings and the
 * nodes they are read from. Everything audible happens on clients; see
 * {@link AlarmSpeaker}.
 */
public class AlarmSpeakerBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {

    /** Assigned in {@link #buildCircuit}, never initialised here; see {@code AlphanumDisplayBlockEntity}. */
    private IElectricNode[] nodes;

    private SpeakerSettings settings;

    public AlarmSpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ALARM_SPEAKER.get(), pos, state);
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        nodes = AlarmSpeaker.buildCircuit(builder, kind());
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || !level.isClientSide)
            return;
        AlarmSpeaker.clientTick(level, worldPosition, hornPosition(), kind(), settings(), nodes);
    }

    private ControlMount mount() {
        BlockState state = getBlockState();
        return ControlMount.centred(state.getValue(AlarmSpeakerBlock.FACING),
                state.getValue(AlarmSpeakerBlock.ROTATION));
    }

    private Vec3 hornPosition() {
        if (!(getBlockState().getBlock() instanceof AlarmSpeakerBlock))
            return Vec3.atCenterOf(worldPosition);
        return AlarmSpeaker.hornPosition(mount()).add(Vec3.atLowerCornerOf(worldPosition));
    }

    /** The whole device, barrel included; see {@code AlphanumDisplayBlockEntity}. */
    @Override
    protected AABB createRenderBoundingBox() {
        if (!(getBlockState().getBlock() instanceof AlarmSpeakerBlock))
            return super.createRenderBoundingBox();
        return SpeakerPins.footprint().bounds(mount()).move(worldPosition);
    }

    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        invalidateRenderBoundingBox();
    }

    public SpeakerKind kind() {
        return getBlockState().getBlock() instanceof AlarmSpeakerBlock block ? block.getKind() : SpeakerKind.FALLBACK;
    }

    public SpeakerSettings settings() {
        if (settings == null)
            settings = new SpeakerSettings();
        return settings;
    }

    /** Server-side: the menu's settings, applied together. */
    public void configure(SpeakerSettings settings) {
        this.settings = settings;
        notifyUpdate();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        CompoundTag speaker = new CompoundTag();
        settings().write(speaker);
        tag.put("Speaker", speaker);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        settings = SpeakerSettings.read(tag.getCompound("Speaker"));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        return AlarmSpeaker.addTooltip(tooltip, kind(), settings(), nodes);
    }
}
