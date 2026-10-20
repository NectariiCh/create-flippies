package com.nectariich.create_flippies.content.design_workbench;

import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.design_workbench.gauge.GaugeDial;
import com.nectariich.create_flippies.content.procedural_gauge.MountedGaugeGeometry;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * A gauge made at the Design Workbench, mounted on a panel. It measures what its
 * design says between its two terminals, the way the procedural voltmeters do: a
 * voltage across their high internal resistance, or a current as the drop across a
 * small shunt, so it is wired in parallel or in series accordingly. Node voltages are
 * synced by Power Grid's sim, so the needle is eased on both sides with the
 * procedural gauges' easing and pegs past either end of the scale as theirs does.
 * {@link #dialState} is the reading's progress along the scale, which is the
 * position the design's needle is posed at ({@code DesignMotion}).
 *
 * Everything it is lives in its design, settled at the workbench, so it has no editor.
 */
public class DesignedGaugeControl extends HostedControl implements DesignedControl {

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".designed_gauge.goggles.";

    private ControlDesign design;
    private ControlFootprint footprint;

    private IElectricNode positive;
    private IElectricNode negative;
    private ElectricWire load;

    /** Read by the renderer, which interpolates between the two. */
    public float dialState;
    public float prevDialState;

    DesignedGaugeControl(DesignedGaugeControlType type) {
        super(type);
        footprint = type.footprint();
    }

    @Override
    public ControlDesign design() {
        return design;
    }

    /** Sets the design and the footprint its model implies, as a new object so the host notices. */
    void setDesign(ControlDesign design) {
        this.design = design;
        this.footprint = DesignedFootprints.gauge(design.front());
        // The circuit may have been built before the design arrived, with the
        // default quantity's resistance
        if (load != null)
            load.setResistance(quantity().resistance());
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    /** What this gauge measures; a voltage while it has no design. */
    public GaugeDial.Quantity quantity() {
        return design == null || design.gauge() == null ? GaugeDial.Quantity.VOLTAGE : design.gauge().quantity;
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(MountedGaugeGeometry.TERMINALS);
        positive = builder.terminalNode(0);
        negative = builder.terminalNode(1);
        load = builder.connect((float) quantity().resistance(), positive, negative);
    }

    /** The reading in the quantity's own unit: volts across the terminals, or amperes through them. */
    public float reading() {
        if (positive == null || negative == null)
            return 0;
        return quantity().reading((float) (positive.getVoltage() - negative.getVoltage()));
    }

    @Override
    public void tick() {
        GaugeDial gauge = design == null ? null : design.gauge();
        float progress = gauge == null ? 0 : gauge.progress(reading());
        float target = Float.isNaN(progress) ? 0 : progress;
        prevDialState = dialState;
        dialState += (target - dialState) * ProceduralGaugeBlockEntity.NEEDLE_EASING;
        // Pegged past full scale: let the needle tremble against the stop
        Level level = host() == null ? null : host().level();
        if (dialState > 1 && level != null && level.random.nextFloat() < 0.5f)
            dialState -= (dialState - 1) * level.random.nextFloat();
    }

    @Override
    public ItemStack asItem() {
        ItemStack stack = type().asItem();
        return design == null ? stack : design.applyTo(stack);
    }

    @Override
    public void write(CompoundTag tag) {
        if (design != null)
            tag.put(ControlDesign.TAG, design.write());
    }

    @Override
    public void read(CompoundTag tag) {
        if (tag.contains(ControlDesign.TAG, Tag.TAG_COMPOUND)) {
            ControlDesign read = ControlDesign.read(tag.getCompound(ControlDesign.TAG));
            // The same design arriving again in a sync keeps its footprint object, so
            // the host does not rebuild its shapes for nothing
            if (design == null || !design.write().equals(read.write()))
                setDesign(read);
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        String name = design == null ? "" : design.name();
        tooltip.add(Component.literal("    ").append(name.isEmpty()
                ? Component.translatable(GOGGLE_PREFIX + "header")
                : Component.literal(name)).withStyle(ChatFormatting.WHITE));
        // In volts or amperes, whatever the face prints: a face may be scaled to
        // read in millivolts, and its units are only a label
        tooltip.add(Component.literal("    ").append(Component.translatable(GOGGLE_PREFIX + quantity().id(),
                Component.literal(String.format(Locale.ROOT, "%.3f", reading())).withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        return true;
    }
}
