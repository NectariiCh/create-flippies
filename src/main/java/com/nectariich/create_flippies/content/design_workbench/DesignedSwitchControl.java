package com.nectariich.create_flippies.content.design_workbench;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControl;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * A rotary switch whose front is an imported model. Turning, the detents, the
 * contacts and the circuit are all the switch's own; this adds the design, and a
 * footprint whose front is the box the design's model sweeps.
 *
 * It has no editor: the switch's own one dresses a front this switch does not have,
 * and what this front looks like, and which of its detents spring back, was settled
 * at the workbench.
 *
 * A breaker control switch (a design of {@link DesignKind#BKR}) also has a flag that
 * latches the way the handle was last thrown, see {@link BreakerIndicator}. Which way
 * it is latched is saved with the control and synced like its position; where the
 * flag is drawn on the way between its two poses is eased here every tick, the way
 * the handle's angle is.
 */
public class DesignedSwitchControl extends RotarySwitchControl implements DesignedControl {

    /** How close to its latched pose the flag counts as arrived, as a fraction of its travel. */
    private static final float INDICATOR_SNAP = 0.005f;

    private ControlDesign design;
    private ControlFootprint footprint;

    /**
     * Which way a breaker's flag is latched, {@link BreakerIndicator#CCW} or
     * {@link BreakerIndicator#CW}. A new breaker starts counter-clockwise. Kept, and
     * saved, for every designed switch, but only a breaker ever changes it.
     */
    private int latched = BreakerIndicator.CCW;

    /** Where the flag is drawn, 0 counter-clockwise to 1 clockwise, eased towards {@link #latched}. */
    public float indicatorPosition;
    public float prevIndicatorPosition;

    /**
     * Whether the flag has been put where a read said it was. The first read puts it
     * there outright, so a breaker coming into view latched clockwise does not swing
     * over from counter-clockwise; later ones are eased to.
     */
    private boolean indicatorPlaced;

    DesignedSwitchControl(DesignedSwitchControlType type) {
        super(type);
        footprint = type.footprint();
    }

    @Override
    public ControlDesign design() {
        return design;
    }

    /** Sets the design and the footprint it implies, as a new object so the host notices. */
    void setDesign(ControlDesign design) {
        this.design = design;
        this.footprint = DesignedFootprints.rotary(kind(), design.front());
    }

    /**
     * Which detents spring back is part of the design, settled at the workbench like
     * everything else about this switch, so the switch's own setting is never
     * consulted. A switch with no design yet springs nowhere.
     */
    @Override
    public int springs() {
        return design == null ? 0 : design.springs();
    }

    /** Whether this is a breaker control switch with a flag. */
    public boolean isBreaker() {
        return design != null && design.indicator() != null;
    }

    /** Which way the flag is latched, {@link BreakerIndicator#CCW} or {@link BreakerIndicator#CW}. */
    public int latched() {
        return latched;
    }

    /**
     * A breaker's flag latches the way the handle comes to rest, before the contacts
     * are set, so a switchover breaker's contacts move over in the same change.
     */
    @Override
    public void setPosition(int position) {
        if (isBreaker())
            latched = BreakerIndicator.latch(kind(), latched, Math.floorMod(position, kind().positions()));
        super.setPosition(position);
    }

    /** A switchover breaker's contacts follow its flag instead of its handle. */
    @Override
    protected int madeStud() {
        if (!isBreaker())
            return super.madeStud();
        return BreakerIndicator.madeStud(design.indicator().mode(), kind(), getPosition(), latched);
    }

    @Override
    public void tick() {
        super.tick();
        prevIndicatorPosition = indicatorPosition;
        float target = latched;
        indicatorPosition += (target - indicatorPosition) * RotarySwitchBlockEntity.HANDLE_EASING;
        if (Math.abs(target - indicatorPosition) < INDICATOR_SNAP)
            indicatorPosition = target;
    }

    /** Says the switch's springs, and for a breaker what its contacts follow and which way its flag is latched. */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        boolean added = super.addToGoggleTooltip(tooltip, sneaking);
        if (!isBreaker())
            return added;
        String prefix = "gui." + CreateFlippies.MOD_ID + ".rotary_switch.goggles.";
        tooltip.add(Component.literal("    ").append(Component.translatable(prefix + "breaker_mode",
                design.indicator().mode().label()).withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(Component.translatable(prefix + "flag",
                Component.translatable(prefix + "flag." + (latched == BreakerIndicator.CW ? "cw" : "ccw"))
                        .withStyle(ChatFormatting.AQUA))
                .withStyle(ChatFormatting.GRAY)));
        return true;
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
        // Only a breaker has a flag, so no other designed switch's tag changes
        if (isBreaker())
            tag.putInt("Latched", latched);
    }

    /**
     * The design and the flag are read before the switch itself, because reading the
     * switch sets its contacts, and a switchover breaker's contacts are the flag's.
     * A breaker saved without a flag state is latched counter-clockwise.
     */
    @Override
    public void read(CompoundTag tag) {
        if (tag.contains(ControlDesign.TAG, Tag.TAG_COMPOUND)) {
            ControlDesign read = ControlDesign.read(tag.getCompound(ControlDesign.TAG));
            // The same design arriving again in a sync keeps its footprint object, so
            // the host does not rebuild its shapes for every turn of the handle
            if (design == null || !design.write().equals(read.write()))
                setDesign(read);
        }
        latched = BreakerIndicator.sanitiseLatch(tag.getInt("Latched"));
        if (!indicatorPlaced) {
            indicatorPosition = latched;
            prevIndicatorPosition = latched;
            indicatorPlaced = true;
        }
        super.read(tag);
    }
}
