package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.network.chat.Component;

import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The space and the terminals a mounted procedural gauge takes up, stated once for
 * both kinds that can be mounted.
 *
 * A voltmeter and a Square Procedural Gauge are the same body, the same bezel and
 * the same two studs; all that differs is where the needle's position comes from.
 * Stating the geometry twice would let the two drift apart, and a footprint that
 * disagreed with a terminal box by a pixel is the kind of bug that only shows up as
 * a wire that will not attach.
 *
 * Everything here goes through {@link ProceduralGaugeBlock#mounted}, because the
 * gauge model is authored facing north while a mounted control is authored
 * plate-down. Converting in one place is what lets the authored numbers stay exactly
 * as the model has them.
 */
final class MountedGaugeGeometry {

    /** Positive and negative, in the order the block form lists them. */
    static final int TERMINALS = 2;

    static final ControlFootprint FOOTPRINT = ControlFootprint.of(
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.HEAD),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.BARREL),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_NEGATIVE),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_DECORATIVE),
            ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_POSITIVE));

    private MountedGaugeGeometry() {}

    /**
     * The two studs, in the block form's order and colours. No origin is given, so a
     * wire lands on the middle of the stud — which is what the block form does too,
     * and it is right here because the boxes are the studs the model draws rather
     * than boxes grown out beyond them.
     */
    static TerminalBoundingBox[] terminals(ControlMount mount) {
        return new TerminalBoundingBox[] {
                terminal(mount, IDecoratedTerminal.POSITIVE, ProceduralGaugeBlock.STUD_POSITIVE,
                        IDecoratedTerminal.RED),
                terminal(mount, IDecoratedTerminal.NEGATIVE, ProceduralGaugeBlock.STUD_NEGATIVE,
                        IDecoratedTerminal.BLUE) };
    }

    private static TerminalBoundingBox terminal(ControlMount mount, Component name, double[] authored, int colour) {
        double[] box = mount.box(ProceduralGaugeBlock.mounted(authored));
        return new TerminalBoundingBox(name, box[0], box[1], box[2], box[3], box[4], box[5]).withColor(colour);
    }
}
