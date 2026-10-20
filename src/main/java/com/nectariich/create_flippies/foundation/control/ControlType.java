package com.nectariich.create_flippies.foundation.control;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A kind of control that can be mounted inside a host block: how many terminals
 * one of them needs, how much room it takes, where its contacts are, and how to
 * make a fresh one.
 *
 * Everything here is fixed for the kind. What varies per mounted control — where
 * it sits, which way up it is, and whatever it is doing — belongs to
 * {@link HostedControl}, and the only thing an instance adds to the geometry is
 * the {@link ControlMount} that moves it.
 *
 * Kinds are named by a string because the name is what a save holds. They live in
 * a plain map in {@link ControlTypes} rather than a Forge registry: nothing
 * outside this mod addresses them, they take part in no tag or recipe, and a real
 * registry would add a sync and an id remapping to worry about for no gain.
 */
public abstract class ControlType {

    private final String id;

    protected ControlType(String id) {
        this.id = id;
    }

    /** The name this kind is saved under, stable for as long as worlds exist. */
    public String id() {
        return id;
    }

    /**
     * Terminals one of these needs, which is also the width of the slot it takes
     * in the host's table and therefore the bucket a released slot goes back into.
     */
    public abstract int terminalCount();

    public abstract ControlFootprint footprint();

    /** A fresh one in its default state, for both placement and loading. */
    public abstract HostedControl create();

    /**
     * A fresh one made from the item being mounted. The same as {@link #create()} for
     * every kind whose items are all alike; a kind whose item carries what the control
     * is (a designed control's model and settings) reads it from the stack here.
     */
    public HostedControl create(ItemStack stack) {
        return create();
    }

    /**
     * The contacts one of these presents when mounted here, in slot order.
     *
     * Built afresh on each call rather than cached per mount, because
     * {@code TerminalBoundingBox.withOrigin} and {@code withColor} mutate the box
     * they are called on and return it — a shared instance would have its origin
     * rewritten by the next control to be mounted the other way up.
     */
    public abstract TerminalBoundingBox[] terminals(ControlMount mount);

    /** What breaking the host block should give back for one of these. */
    public abstract ItemStack asItem();
}
