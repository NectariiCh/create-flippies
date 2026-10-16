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
 * The Square Procedural Gauge as a mountable control: the link-driven gauge, on a
 * panel rather than in a block of its own.
 *
 * It is the same body, the same two terminals and the same supply requirement as the
 * block form, and it shares its geometry with the mounted voltmeters through
 * {@link MountedGaugeGeometry}. The one thing it needs that a voltmeter does not is
 * a line to be written to, because its needle only moves for what a display link
 * sends it — and a link addresses a block position and a line, while a host block
 * holds many controls.
 *
 * That is settled by numbering the host's link-driven controls as one list, in
 * reading order, whichever kind each of them is: see
 * {@link com.nectariich.create_flippies.foundation.control.LinkDrivenControl}. So a
 * panel carrying three legends and two gauges offers a link five rows, and a link
 * aimed at row three writes to whatever is third down the face.
 */
public class SquareProceduralGaugeControlType extends ControlType {

    /** One kind, because unlike a voltmeter the gauge has no range built into it. */
    public static final SquareProceduralGaugeControlType INSTANCE = new SquareProceduralGaugeControlType();

    private SquareProceduralGaugeControlType() {
        super("square_procedural_gauge");
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
        return MountedGaugeGeometry.FOOTPRINT;
    }

    @Override
    public HostedControl create() {
        return new SquareProceduralGaugeControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return MountedGaugeGeometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.SQUARE_PROCEDURAL_GAUGE.get());
    }
}
