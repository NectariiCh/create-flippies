package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * An annunciator rack mounted on a panel. For now it is only seen: its windows are
 * dark, it takes no wires, and a click does nothing. Everything it is lives in its
 * design, settled at the workbench, so it has no editor either.
 */
public class DesignedRackControl extends HostedControl implements DesignedControl {

    private ControlDesign design;
    private ControlFootprint footprint;

    DesignedRackControl(DesignedRackControlType type) {
        super(type);
        footprint = type.footprint();
    }

    @Override
    public ControlDesign design() {
        return design;
    }

    /** Sets the design and the footprint its rack implies, as a new object so the host notices. */
    void setDesign(ControlDesign design) {
        this.design = design;
        this.footprint = DesignedFootprints.rack(design.front());
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    /** Nothing electrical yet. */
    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(0);
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
}
