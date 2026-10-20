package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The Edgewise Procedural Voltmeter as something that can be mounted inside a host
 * block, one kind per measurement range for the reasons
 * {@link ProceduralVoltmeterControlType} gives.
 *
 * Its footprint is {@link EdgewiseVoltmeterBlock}'s bezel and barrel and the three
 * studs, all moved into the mounted frame the way {@link ProceduralGaugeBlock#mounted}
 * moves the Square gauge's. The bezel is four pixels across and thirteen down, so
 * edgewise gauges can stand much closer side by side than Square ones; the studs are
 * the Square gauge's, so the terminals are {@link MountedGaugeGeometry}'s.
 */
public class EdgewiseVoltmeterControlType extends ControlType {

    static final ControlFootprint FOOTPRINT = ControlFootprint.of(
            ProceduralGaugeBlock.mounted(EdgewiseVoltmeterBlock.HEAD),
            ProceduralGaugeBlock.mounted(EdgewiseVoltmeterBlock.BARREL),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_NEGATIVE),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_DECORATIVE),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_POSITIVE));

    private static final Map<VoltmeterRange, EdgewiseVoltmeterControlType> BY_RANGE = byRange();

    private final VoltmeterRange range;

    private EdgewiseVoltmeterControlType(VoltmeterRange range) {
        super(range.edgewiseBlockId());
        this.range = range;
    }

    /** Adds every measurement range to the catalogue of mountable controls. */
    public static void register() {
        BY_RANGE.values().forEach(ControlTypes::register);
    }

    public static EdgewiseVoltmeterControlType of(VoltmeterRange range) {
        return BY_RANGE.get(range);
    }

    public VoltmeterRange range() {
        return range;
    }

    @Override
    public int terminalCount() {
        return MountedGaugeGeometry.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return FOOTPRINT;
    }

    @Override
    public HostedControl create() {
        return new EdgewiseVoltmeterControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return MountedGaugeGeometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.EDGEWISE_PROCEDURAL_VOLTMETERS.get(range).get());
    }

    private static Map<VoltmeterRange, EdgewiseVoltmeterControlType> byRange() {
        Map<VoltmeterRange, EdgewiseVoltmeterControlType> kinds = new EnumMap<>(VoltmeterRange.class);
        for (VoltmeterRange range : VoltmeterRange.values())
            kinds.put(range, new EdgewiseVoltmeterControlType(range));
        return Collections.unmodifiableMap(kinds);
    }
}
