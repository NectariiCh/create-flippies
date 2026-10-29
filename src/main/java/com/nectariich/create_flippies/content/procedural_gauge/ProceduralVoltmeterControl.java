package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.List;

import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenGaugeMenuPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;


import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * A Square Procedural Voltmeter mounted inside a host block: the same pair of
 * terminals bridged by the same high internal resistance as
 * {@link ProceduralVoltmeterBlockEntity} keeps, held as an object among others
 * rather than as one block's worth of state.
 *
 * The circuit is built through a view that numbers this gauge's own two terminals
 * from zero, so the body of {@link #buildCircuit} is the standalone gauge's
 * unchanged. What differs is only where the state lives and who tells it to save:
 * the measurement range comes from the kind rather than from a block state, which is
 * the one thing a control cannot read off a block of its own.
 *
 * The needle is eased on both sides of the connection, as the block form's is —
 * Power Grid's sim syncs node voltages, so the client has a reading to ease towards.
 * That easing state is the reason a host must update its controls in place on a
 * sync rather than rebuild them; see {@link HostedControl#isInstalledAs}.
 *
 * A mounted gauge may be relabelled like a block one. What it stores is only the
 * override: null means the printing its range ships with, so a gauge nobody has
 * customised costs nothing to save and follows its range if that range is ever
 * retuned. The editor is opened by a sneaking right click with a screwdriver, as
 * every control's is, and by the screwdriver as the gauge is mounted — so the
 * printing can be set before the gauge is flush in a row of others.
 */
public class ProceduralVoltmeterControl extends HostedControl implements MountedProceduralGauge {

    private IElectricNode positive;
    private IElectricNode negative;

    /** Null while this gauge still wears its range's own printing. */
    private GaugeFaceDefinition customFace;

    /** Read by the renderer, which interpolates between the two. */
    public float dialState;
    public float prevDialState;

    ProceduralVoltmeterControl(ProceduralVoltmeterControlType type) {
        super(type);
    }

    /** The hardware measurement range, fixed by which kind of gauge this is. */
    public VoltmeterRange range() {
        return ((ProceduralVoltmeterControlType) type()).range();
    }

    /** The dial face printed on this gauge: whatever it was customised to, else its range's. */
    @Override
    public GaugeFaceDefinition faceDefinition() {
        return customFace != null ? customFace : range().face();
    }

    @Override
    public float dialState() {
        return dialState;
    }

    @Override
    public float prevDialState() {
        return prevDialState;
    }

    /** Whether anyone has printed something other than the range's own face on this gauge. */
    public boolean hasCustomFace() {
        return customFace != null;
    }

    /** Called server-side from the editor; null puts the range's own printing back. */
    public void configure(GaugeFaceDefinition face) {
        customFace = face;
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
        ModPackets.sendToPlayer(player,
                new OpenGaugeMenuPacket(host().pos(), mount()));
        return true;
    }

    @Override
    public void write(CompoundTag tag) {
        if (customFace != null)
            tag.put("Face", customFace.toNbt());
    }

    @Override
    public void read(CompoundTag tag) {
        customFace = tag.contains("Face") ? GaugeFaceDefinition.fromNbt(tag.getCompound("Face")) : null;
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(ProceduralVoltmeterControlType.TERMINALS);
        positive = builder.terminalNode(0);
        negative = builder.terminalNode(1);
        builder.connect(ProceduralVoltmeterBlockEntity.INTERNAL_RESISTANCE, positive, negative);
    }

    /** Potential difference across the terminals, in volts. */
    public float measuredVoltage() {
        if (positive == null || negative == null)
            return 0;
        return (float) (positive.getVoltage() - negative.getVoltage());
    }

    @Override
    public void tick() {
        VoltmeterRange range = range();
        float span = range.max() - range.min();
        float reading = span == 0 ? 0
                : ProceduralGaugeBlockEntity.clampProgress((measuredVoltage() - range.min()) / span);
        float target = Float.isNaN(reading) ? 0 : reading;
        prevDialState = dialState;
        dialState += (target - dialState) * ProceduralGaugeBlockEntity.NEEDLE_EASING;
        // Pegged past full scale: let the needle tremble against the stop
        Level level = host() == null ? null : host().level();
        if (dialState > 1 && level != null && level.random.nextFloat() < 0.5f)
            dialState -= (dialState - 1) * level.random.nextFloat();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        String prefix = ProceduralVoltmeterBlockEntity.GOGGLE_PREFIX;
        tooltip.add(indent(Component.translatable(prefix + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(prefix + "voltage",
                Component.literal(String.format("%.2f", measuredVoltage())).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(prefix + "range",
                range().label().copy().withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
