package com.nectariich.create_flippies.foundation.control;

import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * One control mounted inside a host block: its kind, where it is, which terminal
 * indices it owns, and whatever state it keeps.
 *
 * A control is not a block and has no block state, which is the whole point —
 * there is one block entity per cell and Power Grid keys a wire endpoint on a
 * block position, so many controls in one cell can only be objects owned by one
 * block entity. Everything a standalone instrument keeps on its own block entity
 * moves here nearly verbatim; what it used to read off its block, such as which
 * variant it is, has to come from the kind instead.
 *
 * The mount and the slot are handed over once, when the host installs it, and are
 * then fixed for the life of the control. Moving a mounted control means removing
 * it and mounting a new one, because a move is a change of footprint and of
 * terminal geometry and both have to be re-checked against everything else in the
 * block; and the slot in particular can never be reassigned, for the reasons
 * {@link TerminalSlots} sets out.
 */
public abstract class HostedControl {

    private final ControlType type;

    private ControlHost host;
    private ControlMount mount;
    private int slot = TerminalSlots.NO_SLOT;
    private boolean locked;

    protected HostedControl(ControlType type) {
        this.type = type;
    }

    public ControlType type() {
        return type;
    }

    public ControlMount mount() {
        return mount;
    }

    /** The first of this control's terminal indices in its host's flat run. */
    public int slot() {
        return slot;
    }

    /**
     * Whether this control is protected from being unscrewed.
     *
     * Per control rather than per area, which is the difference between this and the
     * break protection the screwdriver rules in boxes. That one is about blocks — it
     * stops the tool mining a wall it is being used to build — and it deliberately
     * says nothing about the instruments on that wall, because taking one off is the
     * gesture the tool exists for. This is the other half: the one gauge that is
     * finished and must not come off while the panel around it is still being worked.
     *
     * The host owns it the way it owns the mount and the slot, so it is written with
     * those rather than with the control's own state — but unlike them it is not part
     * of {@link #isInstalledAs}. It changes while a control stays exactly where it
     * is, and treating it as identity would rebuild the instance and throw away
     * everything transient it holds every time it was toggled.
     */
    public boolean isLocked() {
        return locked;
    }

    /** Set by the host as it installs or loads the control, and by nothing else. */
    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public int terminalCount() {
        return type.terminalCount();
    }

    protected ControlHost host() {
        return host;
    }

    /**
     * Fixes where this control lives. The host calls this once, as it installs the
     * control or loads it back; anything else calling it moves a control without
     * moving its terminals.
     */
    public void installAt(ControlHost host, ControlMount mount, int slot) {
        this.host = host;
        this.mount = mount;
        this.slot = slot;
    }

    /**
     * Whether this is the control an arriving save or packet is describing, rather
     * than another one that merely comes next in the list.
     *
     * The kind, the mount and the slot are the whole of what an installed control
     * <em>is</em>: the host fixes all three as it installs one and a change to any of
     * them is a different installation, not the same control moved. Everything else
     * a control holds is state — the position of a handle and the angle it is easing
     * through, the legend on a label — and some of that state exists only in memory
     * and only on the client. So a sync that finds a control still installed the
     * same way must update it in place; building a fresh one would silently reset
     * every transient field to its default, which is what made a whole panel of
     * switches jump back to their first detent whenever any one of them was turned.
     */
    public boolean isInstalledAs(ControlType type, ControlMount mount, int slot) {
        return this.type == type && this.slot == slot && mount.equals(this.mount);
    }

    public TerminalBoundingBox[] terminals() {
        return type.terminals(mount);
    }

    /**
     * The space this control takes up.
     *
     * The kind's, for everything whose size is fixed the moment it is made, which
     * is nearly all of it. A control the player sizes as they place it — see
     * {@link SpannedControlType} — overrides this with the extent it was actually
     * drawn at, and everything that asks about a mounted control's space asks here
     * rather than the kind, so an overlap test, a hitbox and a renderer all get the
     * same answer for it.
     */
    public ControlFootprint footprint() {
        return type.footprint();
    }

    public AABB[] volumes() {
        return footprint().volumes(mount);
    }

    public VoxelShape shape() {
        return footprint().shape(mount);
    }

    /**
     * Builds this control's circuit through a view that numbers its own terminals
     * from zero, so the body of this method is the same as a standalone
     * instrument's.
     */
    public abstract void buildCircuit(HostedCircuitBuilder builder);

    /**
     * Writes this control's own state, and nothing else. The host owns the kind,
     * the mount and the slot, so a control that wrote those would be writing them
     * twice and could disagree with its host after an edit.
     */
    public void write(CompoundTag tag) {}

    public void read(CompoundTag tag) {}

    /** Called on both sides once a tick while the host is loaded. */
    public void tick() {}

    /**
     * A right-click that was not a wire connection and not a wrench.
     *
     * {@code local} is where the click landed, in the host block's own space and in
     * block units — the same point the host resolved this control from. Most controls
     * have nothing a plain click can mean and ignore it; a rotary switch is turned one
     * way or the other depending on which half of its front was pushed, and cannot be
     * operated without it.
     *
     * This used to open the editor by default, which spent the one gesture the
     * operable controls need on the one thing that is not operating them. Configuring
     * anything is now a sneaking right click with a screwdriver — see
     * {@link ControlConfig} — so a plain click on a control that does nothing does
     * nothing, and a plain click on a button presses it.
     */
    public InteractionResult use(Player player, InteractionHand hand, Vec3 local) {
        return InteractionResult.PASS;
    }

    /**
     * Whether {@link #openEditor} has anything to open.
     *
     * Asked where the answer is wanted without the screen: the client's pass of a
     * screwdriver click, which has to know whether the click was claimed by a control
     * before it decides to open the tool's own menu instead, and which cannot call
     * {@link #openEditor} because that is the server's. The two must agree — a control
     * that overrides one overrides both.
     */
    public boolean hasEditor() {
        return false;
    }

    /**
     * Offers this control's editor to one player, answering whether it has one.
     *
     * Always called on the server, from two places: a sneaking screwdriver click on
     * the control, and the screwdriver just after mounting one. The second is the
     * reason this is separate from {@link #use} — a control whose printing is worth
     * setting should be offered as it goes on, rather than placed blank and then
     * hunted for again.
     *
     * A mounted control cannot open its own screen the way an instrument block does.
     * A host hands a click to its controls on the server alone, so the screen has to
     * be asked for from here — which also means the server has decided this player may
     * edit this control before the screen exists.
     */
    public boolean openEditor(ServerPlayer player) {
        return false;
    }

    /**
     * What taking this control off gives back. The kind's item for nearly
     * everything; a control that holds something put into it after it was mounted
     * — a relay mount and its relay — gives that back as well.
     */
    public List<ItemStack> drops() {
        return List.of(type.asItem());
    }

    /**
     * A left click that would unscrew this control, offered to it first. A control
     * holding something put into it after it was mounted — a relay mount and its
     * relay — gives that up instead and answers true, and only the next click takes
     * the control itself. Called on the server, after the lock has been checked.
     */
    public boolean breakPart(Player player) {
        return false;
    }

    /** @return whether anything was added */
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        return false;
    }

    /** Persist and sync the host after changing this control's state. */
    protected void changed() {
        if (host != null)
            host.controlChanged();
    }
}
