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
 * The Square Procedural Voltmeter as something that can be mounted inside a host
 * block: the same bezel, barrel and pair of contacts as
 * {@link ProceduralGaugeBlock} authors, described so that several of them can share
 * one cell.
 *
 * One kind per measurement range, mirroring both the blocks and the way
 * {@code RotarySwitchControlType} treats its markings. The range is not state: it
 * is which instrument this is, it decides the item a broken panel gives back, and a
 * save names the kind rather than describing it — so a parameterised kind with the
 * range in its own NBT would be a way of writing the same fact twice and letting
 * the two disagree.
 *
 * The footprint is where this differs from the block form in substance rather than
 * in packaging. A block never has to ask whether it fits; a mounted control has to
 * declare the space it will really use, and for a gauge that is the whole of it —
 * bezel, barrel and studs — because the barrel stands in the cell behind the panel
 * where the far side's controls may be reaching back the other way. Nothing here
 * moves, so unlike the rotary switch there is no swept volume to add: the needle
 * turns inside the bezel that is already declared.
 *
 * The consequences are worth stating plainly. The bezel is six pixels across, so two
 * gauges on one face need their centres six pixels apart. In free placement the
 * block's own face is the limit, so a centre has to sit at least three pixels in from
 * every edge of it: that allows two rows of two on one face and no more, and a
 * three-by-three ruling puts cell centres 2.67 pixels from the edge and is refused
 * for overhanging the face. In constrained placement the limit is the ruled grid
 * instead, so a gauge may sit on a block edge or straddle two blocks provided it
 * stays inside the grid. The barrel spends 6.5 pixels of the depth budget either
 * way, and two gauges mounted back to back through a panel one block thick clear
 * each other by three pixels.
 *
 * The link-driven Square Procedural Gauge is mountable too, as
 * {@link SquareProceduralGaugeControlType}, and shares this kind's geometry through
 * {@link MountedGaugeGeometry}. The two differ only in where the needle's reading
 * comes from: a voltmeter reads what is across its own terminals and needs nothing
 * else, while a gauge has to be given a row in the host's link numbering.
 */
public class ProceduralVoltmeterControlType extends ControlType {

    /** Positive and negative, in the order the block form lists them. */
    public static final int TERMINALS = MountedGaugeGeometry.TERMINALS;

    private static final Map<VoltmeterRange, ProceduralVoltmeterControlType> BY_RANGE = byRange();

    private final VoltmeterRange range;

    private ProceduralVoltmeterControlType(VoltmeterRange range) {
        super(range.blockId());
        this.range = range;
    }

    /** Adds every measurement range to the catalogue of mountable controls. */
    public static void register() {
        BY_RANGE.values().forEach(ControlTypes::register);
    }

    public static ProceduralVoltmeterControlType of(VoltmeterRange range) {
        return BY_RANGE.get(range);
    }

    public VoltmeterRange range() {
        return range;
    }

    @Override
    public int terminalCount() {
        return TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return MountedGaugeGeometry.FOOTPRINT;
    }

    @Override
    public HostedControl create() {
        return new ProceduralVoltmeterControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return MountedGaugeGeometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.SQUARE_PROCEDURAL_VOLTMETERS.get(range).get());
    }

    private static Map<VoltmeterRange, ProceduralVoltmeterControlType> byRange() {
        Map<VoltmeterRange, ProceduralVoltmeterControlType> kinds = new EnumMap<>(VoltmeterRange.class);
        for (VoltmeterRange range : VoltmeterRange.values())
            kinds.put(range, new ProceduralVoltmeterControlType(range));
        return Collections.unmodifiableMap(kinds);
    }
}
