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
 * An Edgewise Procedural Voltmeter mounted inside a host block: the same pair of
 * terminals and the same high internal resistance as
 * {@link EdgewiseVoltmeterBlockEntity}, eased the same way, with the range taken from
 * the kind.
 *
 * Like {@link ProceduralVoltmeterControl} it may be relabelled, and what it stores is
 * only the override: null means the printing its range ships with. The editor is
 * opened by a sneaking right click with a screwdriver, and by the screwdriver as the
 * gauge is mounted.
 */
public class EdgewiseVoltmeterControl extends HostedControl implements MountedEdgewiseGauge {

    private IElectricNode positive;
    private IElectricNode negative;

    /** Null while this gauge still wears its range's own printing. */
    private EdgewiseFaceDefinition customFace;

    /** Read by the renderer, which interpolates between the two. */
    public float dialState;
    public float prevDialState;

    EdgewiseVoltmeterControl(EdgewiseVoltmeterControlType type) {
        super(type);
    }

    public VoltmeterRange range() {
        return ((EdgewiseVoltmeterControlType) type()).range();
    }

    @Override
    public EdgewiseFaceDefinition faceDefinition() {
        return customFace != null ? customFace : range().edgewiseFace();
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
    public void configure(EdgewiseFaceDefinition face) {
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
        customFace = tag.contains("Face") ? EdgewiseFaceDefinition.fromNbt(tag.getCompound("Face")) : null;
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(MountedGaugeGeometry.TERMINALS);
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
        Level level = host() == null ? null : host().level();
        if (dialState > 1 && level != null && level.random.nextFloat() < 0.5f)
            dialState -= (dialState - 1) * level.random.nextFloat();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        String prefix = ProceduralVoltmeterBlockEntity.GOGGLE_PREFIX;
        tooltip.add(indent(Component.translatable(EdgewiseVoltmeterBlockEntity.GOGGLE_PREFIX + "header")
                .withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(prefix + "voltage",
                Component.literal(String.format("%.2f", measuredVoltage())).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        tooltip.add(indent(Component.translatable(prefix + "range",
                range().label().copy().withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY)));
        return true;
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
