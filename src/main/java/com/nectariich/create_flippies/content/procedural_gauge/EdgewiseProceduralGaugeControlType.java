package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The Edgewise Procedural Gauge as a mountable control: the link-driven edgewise
 * gauge on a panel, as {@link SquareProceduralGaugeControlType} is the Square one.
 * Its body is the edgewise voltmeters', so it takes their footprint, and its studs
 * are every mounted gauge's, so it takes {@link MountedGaugeGeometry}'s terminals.
 */
public class EdgewiseProceduralGaugeControlType extends ControlType {

    /** One kind, because unlike a voltmeter the gauge has no range built into it. */
    public static final EdgewiseProceduralGaugeControlType INSTANCE = new EdgewiseProceduralGaugeControlType();

    private EdgewiseProceduralGaugeControlType() {
        super("edgewise_procedural_gauge");
    }

    public static void register() {
        ControlTypes.register(INSTANCE);
    }

    @Override
    public int terminalCount() {
        return MountedGaugeGeometry.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return EdgewiseVoltmeterControlType.FOOTPRINT;
    }

    @Override
    public HostedControl create() {
        return new EdgewiseProceduralGaugeControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return MountedGaugeGeometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.EDGEWISE_PROCEDURAL_GAUGE.get());
    }
}
