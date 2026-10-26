package com.nectariich.create_flippies.content.design_workbench;

import java.util.List;

import com.nectariich.create_flippies.content.display.DisplayPins;
import com.nectariich.create_flippies.content.indicator.IndicatorLamp;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchKind;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;

/**
 * The space a designed control takes: the rear of the control it is built on,
 * exactly, and a front that is the box around its model. A rack has no rear yet,
 * so it is its front alone. A gauge's rear is the procedural gauges' barrel and
 * studs, in the mounted frame, whatever its face. A screen's rear is the
 * alphanumeric displays' barrel and pins.
 */
public final class DesignedFootprints {

    private DesignedFootprints() {}

    public static ControlFootprint rotary(RotarySwitchKind kind, double[] front) {
        return ControlFootprint.of(front, RotarySwitchBlock.NECK, RotarySwitchBlock.RACK)
                .plus(RotarySwitchBlock.contactBoxes(kind));
    }

    public static ControlFootprint button(double[] front) {
        return ControlFootprint.of(front, IndicatorLamp.BARREL).plus(IndicatorLamp.CONTACT_BOXES);
    }

    public static ControlFootprint rack(double[] front) {
        return ControlFootprint.of(front);
    }

    /** A gauge's barrel, behind the plate, in the mounted frame. */
    public static double[] gaugeBarrel() {
        return ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.BARREL);
    }

    /** A gauge's three studs, in the mounted frame: the boxes its terminals are. */
    public static List<double[]> gaugeStuds() {
        return List.of(ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_NEGATIVE),
                ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_DECORATIVE),
                ProceduralGaugeBlock.mounted(ProceduralGaugeBlock.STUD_POSITIVE));
    }

    public static ControlFootprint gauge(double[] front) {
        List<double[]> studs = gaugeStuds();
        return ControlFootprint.of(front, gaugeBarrel(), studs.get(0), studs.get(1), studs.get(2));
    }

    /** A screen's front over the displays' barrel, contacts and unwired pins. */
    public static ControlFootprint screen(double[] front) {
        return DisplayPins.rearFootprint(front);
    }

    /** The footprint of a design of any kind, from its front. */
    public static ControlFootprint of(ControlDesign design) {
        if (design.kind().isRack())
            return rack(design.front());
        if (design.kind().isGauge())
            return gauge(design.front());
        if (design.kind().isButton())
            return button(design.front());
        if (design.kind().isScreen())
            return screen(design.front());
        return rotary(design.rotary(), design.front());
    }
}
