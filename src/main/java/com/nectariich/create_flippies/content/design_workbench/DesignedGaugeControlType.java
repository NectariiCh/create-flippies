package com.nectariich.create_flippies.content.design_workbench;

import java.util.List;

import com.nectariich.create_flippies.content.procedural_gauge.MountedGaugeGeometry;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A gauge made at the Design Workbench, as something that can be mounted: its
 * imported model in front, with the printed face on one submesh and the needle
 * following the reading, and the procedural gauges' barrel and two terminals behind
 * it. One kind per face, rotary or edgewise, since the kind names the item and a
 * save names the kind.
 *
 * Its footprint here has a small placeholder front; each control replaces it with
 * the one its own design implies.
 */
public class DesignedGaugeControlType extends ControlType {

    /** The front a gauge with no design yet is boxed as, in authored pixels: a small square on the plate. */
    private static final double[] DEFAULT_FRONT = { 4, 0, 4, 12, 1.5, 12 };

    private static final DesignedGaugeControlType DIAL = new DesignedGaugeControlType(DesignKind.GAUGE);
    private static final DesignedGaugeControlType EDGEWISE = new DesignedGaugeControlType(DesignKind.EDGEWISE_GAUGE);

    private final DesignKind kind;
    private final ControlFootprint footprint;

    private DesignedGaugeControlType(DesignKind kind) {
        super(kind.itemId());
        this.kind = kind;
        this.footprint = DesignedFootprints.gauge(DEFAULT_FRONT.clone());
    }

    public static void register() {
        ControlTypes.register(DIAL);
        ControlTypes.register(EDGEWISE);
    }

    public static List<DesignedGaugeControlType> all() {
        return List.of(DIAL, EDGEWISE);
    }

    public DesignKind kind() {
        return kind;
    }

    @Override
    public int terminalCount() {
        return MountedGaugeGeometry.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new DesignedGaugeControl(this);
    }

    @Override
    public HostedControl create(ItemStack stack) {
        DesignedGaugeControl control = new DesignedGaugeControl(this);
        ControlDesign design = ControlDesign.of(stack);
        if (design != null && design.kind() == kind)
            control.setDesign(design);
        return control;
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return MountedGaugeGeometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.DESIGNED_CONTROLS.get(kind).get());
    }
}
