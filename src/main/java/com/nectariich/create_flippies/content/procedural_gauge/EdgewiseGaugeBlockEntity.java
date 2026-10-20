package com.nectariich.create_flippies.content.procedural_gauge;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * Shared behaviour of the edgewise procedural gauges, exactly as
 * {@link ProceduralGaugeBlockEntity} is for the Square ones: two Power Grid
 * terminals bridged by an internal resistance, the pointer's easing state, and an
 * optional screwdriver-applied face customization overriding the variant's default.
 */
public abstract class EdgewiseGaugeBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {

    protected IElectricNode node1;
    protected IElectricNode node2;
    protected ElectricWire connection;

    /** Customized face; null = the variant's default face. */
    private EdgewiseFaceDefinition customFace;

    public float dialTarget;
    public float dialState;
    public float prevDialState;

    protected EdgewiseGaugeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(2);
        node1 = builder.terminalNode(0);
        node2 = builder.terminalNode(1);
        connection = builder.connect(internalResistance(), node1, node2);
    }

    protected abstract float internalResistance();

    /** Potential difference across the terminals, in volts. */
    public float measuredVoltage() {
        if (node1 == null || node2 == null)
            return 0;
        return (float) (node1.getVoltage() - node2.getVoltage());
    }

    /** The face this gauge ships with, before any customization. */
    public abstract EdgewiseFaceDefinition defaultFaceDefinition();

    /** The face currently printed on this gauge. */
    public EdgewiseFaceDefinition getFaceDefinition() {
        return customFace != null ? customFace : defaultFaceDefinition();
    }

    public boolean hasCustomFace() {
        return customFace != null;
    }

    /** Server-side: apply (or with null, reset) the customization. */
    public void setCustomFace(EdgewiseFaceDefinition face) {
        customFace = face;
        notifyUpdate();
    }

    /** Pointer position for this tick, 0..1 along the scale (slightly outside = pegged). */
    protected abstract float computeDialTarget();

    @Override
    public void tick() {
        super.tick();
        float target = computeDialTarget();
        dialTarget = Float.isNaN(target) ? 0 : target;
        prevDialState = dialState;
        dialState += (dialTarget - dialState) * ProceduralGaugeBlockEntity.NEEDLE_EASING;
        // Pegged past full scale: let the pointer tremble against the stop
        if (dialState > 1 && level != null && level.random.nextFloat() < 0.5f)
            dialState -= (dialState - 1) * level.random.nextFloat();
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        if (customFace != null)
            tag.put("CustomFace", customFace.toNbt());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        customFace = tag.contains("CustomFace") ? EdgewiseFaceDefinition.fromNbt(tag.getCompound("CustomFace"))
                : null;
    }
}
