package com.nectariich.create_flippies.content.procedural_gauge;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * Shared behaviour of the Square procedural gauges: two Power Grid terminals
 * bridged by an internal resistance, needle animation state, and an optional
 * screwdriver-applied dial face customization overriding the variant's default.
 * Voltages are synchronized to clients by Power Grid's network sim, so
 * {@link #tick()} animates the needle on both sides, the same way the mod's
 * own gauges do.
 */
public abstract class ProceduralGaugeBlockEntity extends ElectricBlockEntity implements IHaveGoggleInformation {

    /**
     * How much of the way to the reading the needle travels each tick. Shared with
     * the mounted form of the same gauge, so that one screwed into a panel settles
     * exactly as one placed as a block of its own does.
     */
    public static final float NEEDLE_EASING = 0.125f;

    protected IElectricNode node1;
    protected IElectricNode node2;
    protected ElectricWire connection;

    /** Customized dial face; null = the variant's default face. */
    private GaugeFaceDefinition customFace;

    public float dialTarget;
    public float dialState;
    public float prevDialState;

    protected ProceduralGaugeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
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
    public abstract GaugeFaceDefinition defaultFaceDefinition();

    /** The dial face currently printed on this gauge. */
    public GaugeFaceDefinition getFaceDefinition() {
        return customFace != null ? customFace : defaultFaceDefinition();
    }

    public boolean hasCustomFace() {
        return customFace != null;
    }

    /** Server-side: apply (or with null, reset) the customization. */
    public void setCustomFace(GaugeFaceDefinition face) {
        customFace = face;
        onFaceChanged();
        notifyUpdate();
    }

    /** Hook for subclasses whose value mapping depends on the face. */
    protected void onFaceChanged() {}

    /** Needle position for this tick, 0..1 along the scale (slightly outside = pegged). */
    protected abstract float computeDialTarget();

    @Override
    public void tick() {
        super.tick();
        float target = computeDialTarget();
        dialTarget = Float.isNaN(target) ? 0 : target;
        prevDialState = dialState;
        dialState += (dialTarget - dialState) * NEEDLE_EASING;
        // Pegged past full scale: let the needle tremble against the stop
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
        customFace = tag.contains("CustomFace") ? GaugeFaceDefinition.fromNbt(tag.getCompound("CustomFace")) : null;
        onFaceChanged();
    }

    /**
     * A reading as a fraction of the scale, allowed a little past each end so that
     * the needle can be seen to peg rather than stopping dead on the last marking.
     * Shared with the mounted form of the same gauge.
     */
    public static float clampProgress(float progress) {
        if (progress > 1)
            return 1.06f;
        return Mth.clamp(progress, -0.04f, 1f);
    }
}
