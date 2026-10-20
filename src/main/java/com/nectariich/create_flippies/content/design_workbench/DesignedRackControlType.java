package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.content.design_workbench.annunciator.AnnunciatorRack;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * An annunciator rack made at the Design Workbench, as something that can be mounted:
 * its generated model and legends, and nothing electrical yet. It declares no
 * terminals, which a host copes with exactly as it does for a panel label (see
 * {@code PanelLabelControlType}).
 *
 * Its footprint here is the default rack's box; each control replaces it with the
 * box of its own rack.
 */
public class DesignedRackControlType extends ControlType {

    private static final DesignedRackControlType INSTANCE = new DesignedRackControlType();

    /** Shared, since an empty array has nothing for a mount to mutate. */
    private static final TerminalBoundingBox[] NO_TERMINALS = new TerminalBoundingBox[0];

    private final ControlFootprint footprint = DesignedFootprints.rack(AnnunciatorRack.defaults().box());

    private DesignedRackControlType() {
        super(DesignKind.ANNUNCIATOR.itemId());
    }

    public static void register() {
        ControlTypes.register(INSTANCE);
    }

    public static DesignedRackControlType get() {
        return INSTANCE;
    }

    @Override
    public int terminalCount() {
        return 0;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new DesignedRackControl(this);
    }

    @Override
    public HostedControl create(ItemStack stack) {
        DesignedRackControl control = new DesignedRackControl(this);
        ControlDesign design = ControlDesign.of(stack);
        if (design != null && design.kind().isRack())
            control.setDesign(design);
        return control;
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return NO_TERMINALS;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.DESIGNED_CONTROLS.get(DesignKind.ANNUNCIATOR).get());
    }
}
