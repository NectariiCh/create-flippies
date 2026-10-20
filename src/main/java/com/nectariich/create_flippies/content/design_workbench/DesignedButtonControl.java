package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.content.indicator.IndicatorAction;
import com.nectariich.create_flippies.content.indicator.IndicatorControl;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.indicator.IndicatorSize;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * An indicator push-button whose cap is an imported model. Pressing, the lamp, the
 * poles and the contacts are the indicator's own; the press lands on the design's
 * moving part instead of the cap, and the lamp is rated as the design says.
 *
 * It has no editor: the indicator's own one sets a lens colour and a legend this
 * front does not have, and the action was chosen at the workbench. The design is
 * the only word on the action, so a design made as a pure indicator
 * ({@link IndicatorAction#NO_CLICK}) can never be clicked, whatever the control's
 * own saved state says.
 */
public class DesignedButtonControl extends IndicatorControl implements DesignedControl {

    private ControlDesign design;
    private ControlFootprint footprint;

    DesignedButtonControl(DesignedButtonControlType type) {
        super(type);
        footprint = type.footprint();
    }

    @Override
    public ControlDesign design() {
        return design;
    }

    /** Sets the design, the footprint it implies, and the action it was made with. */
    void setDesign(ControlDesign design) {
        this.design = design;
        this.footprint = DesignedFootprints.button(design.front());
        matchAction();
    }

    /**
     * Brings the button's action back to the design's. Asked on every read as well
     * as on every new design, because the action the button saved may predate the
     * design it carries: designs used to be unable to say {@code NO_CLICK}, and a
     * button that had been saved clicking must not keep clicking once its design
     * says it is a lamp.
     */
    private void matchAction() {
        if (design != null && action() != design.action())
            setAction(design.action());
    }

    @Override
    public IndicatorKind kind() {
        return design == null ? super.kind() : IndicatorKind.of(IndicatorSize.MED, design.rating());
    }

    @Override
    public double[] capBox() {
        return design == null ? super.capBox() : design.moving();
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public boolean hasEditor() {
        return false;
    }

    @Override
    public boolean openEditor(ServerPlayer player) {
        return false;
    }

    @Override
    public ItemStack asItem() {
        ItemStack stack = type().asItem();
        return design == null ? stack : design.applyTo(stack);
    }

    @Override
    public void write(CompoundTag tag) {
        super.write(tag);
        if (design != null)
            tag.put(ControlDesign.TAG, design.write());
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        if (tag.contains(ControlDesign.TAG, Tag.TAG_COMPOUND)) {
            ControlDesign read = ControlDesign.read(tag.getCompound(ControlDesign.TAG));
            if (design == null || !design.write().equals(read.write()))
                setDesign(read);
        }
        matchAction();
    }
}
