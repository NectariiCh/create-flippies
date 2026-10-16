package com.nectariich.create_flippies.content.indicator;

import java.util.UUID;

import com.nectariich.create_flippies.registry.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;

/**
 * The plunger behind an indicator's cap: what pressing it does, whether it is down,
 * and the three poles that follow it.
 *
 * Held here rather than written into both {@link IndicatorBlockEntity} and
 * {@link IndicatorControl}, unlike the rotary switch's position, because this is not
 * a field and a line of arithmetic — it is a small state machine with a way of going
 * wrong that the switch does not have. A spring-return button is closed for as long
 * as one particular player holds the use key, which means the thing that closed it
 * can walk out of range, die, disconnect or have its chunk saved and loaded while it
 * is still down. Everything about that is in one place here so that the two forms of
 * indicator cannot disagree about a button that is stuck on.
 *
 * <h2>What is trusted</h2>
 *
 * The client says when the key goes down and when it comes up, and nothing else. It
 * is not asked how long the press was and it is not believed about whether the
 * button is down — {@link #tick} checks every tick that whoever pressed it is still
 * there to be pressing it, and lets go on their behalf if they are not. So a client
 * that sends a press and never a release holds the contacts for exactly as long as
 * that player stands in front of the button, which is the same as holding the key.
 *
 * <h2>What is saved</h2>
 *
 * Whether the button is down is written whichever action it is doing, because a
 * mounted control writes one tag for the disk and the network alike and the client
 * has to be told that a cap is pushed in. What is not written is who is holding it,
 * which is what makes a reload safe: a spring-return button that comes back down has
 * no holder, and the first tick lets it go.
 */
public class IndicatorButton {

    /** How far the holder of a spring-return button may be from it before it lets go. */
    private static final double MAX_REACH = 8;

    /** How loud a press is; the same level the rotary switch's detents are cut at. */
    private static final float CLICK_VOLUME = 0.7f;

    /** A press and its release, told apart by pitch rather than by two recordings. */
    private static final float PRESS_PITCH = 1.15f;
    private static final float RELEASE_PITCH = 0.9f;

    private IndicatorAction action = IndicatorAction.defaultAction();
    private boolean pressed;

    /**
     * Who is holding a spring-return button down, server-side and never saved. Null
     * for every other case, including a latched button, which is nobody's.
     */
    private UUID holder;

    /**
     * One switched wire per pole, in the order {@link IndicatorLamp} numbers them.
     * Null until whichever form owns this builds its circuit, which it does before
     * anything can press the cap.
     */
    private SwitchedWire[] poles;

    public IndicatorAction action() {
        return action;
    }

    public boolean isPressed() {
        return pressed;
    }

    /**
     * Server-side: change what pressing does.
     *
     * A button taken off a closing action lets go at once. Leaving the contacts made
     * would be a set of poles nobody can open again — the gesture that would have
     * opened them is the one just taken away.
     */
    public void setAction(IndicatorAction action) {
        this.action = action;
        if (!action.clickable() && pressed)
            open();
    }

    /**
     * The wires this button drives, handed over by whichever form built the circuit.
     * Taking them here rather than building them here is deliberate: the two forms
     * build through different builders, and the loop that connects three pairs is
     * shorter than the interface that would have unified them.
     */
    public void setPoles(SwitchedWire[] poles) {
        this.poles = poles;
        apply();
    }

    /**
     * Server-side: the use key has gone down on the cap.
     *
     * @return whether the contacts moved, so a caller can keep quiet about a press on
     *         a lamp that does not click
     */
    public boolean press(Player player) {
        if (!action.clickable())
            return false;
        if (action.springs()) {
            // A second player pressing a held button takes it over rather than being
            // refused: the contacts are already closed, and whoever is still standing
            // there should be the one that keeps them closed
            holder = player.getUUID();
            if (pressed)
                return false;
            set(true);
            return true;
        }
        set(!pressed);
        return true;
    }

    /**
     * Server-side: the use key has come up.
     *
     * Only the player holding the button may release it, so that someone else's click
     * on the same cap cannot open a contact they never closed. A latched button
     * ignores this outright — its release is the next press.
     *
     * @return whether the contacts moved
     */
    public boolean release(Player player) {
        if (!action.springs() || !pressed || !player.getUUID().equals(holder))
            return false;
        open();
        return true;
    }

    /**
     * Server-side, once a tick: let go of a spring-return button whose holder is no
     * longer there to hold it.
     *
     * This is what the client is not trusted for. It covers the press whose release
     * was lost, the player who walked away or died with the key down, the one who
     * disconnected, and the button that was saved closed and loaded with no holder at
     * all.
     *
     * @return whether the contacts moved
     */
    public boolean tick(Level level, BlockPos pos) {
        if (level == null || level.isClientSide || !pressed || !action.springs())
            return false;
        if (holder != null && holds(level, pos))
            return false;
        open();
        return true;
    }

    /** Point the poles at whatever state the button is in; see {@link #setPoles}. */
    public void apply() {
        if (poles == null)
            return;
        for (SwitchedWire pole : poles)
            if (pole != null)
                pole.setState(pressed);
    }

    /**
     * The cap dropping, or springing back, heard at the indicator.
     *
     * Server-side only: this broadcasts to everyone in earshot, so letting the client
     * reach it as well would play every press twice for whoever pressed it. The two
     * are the switch's detent at two pitches rather than two recordings of their own —
     * a cap and a detent are the same click of moulded plastic, and levelling a third
     * sample against the other two is work for no difference anyone could hear.
     */
    public static void playClick(Level level, BlockPos pos, boolean down) {
        if (level == null || level.isClientSide)
            return;
        level.playSound(null, pos, ModSounds.ROTARY_SWITCH_TURN.get(), SoundSource.BLOCKS, CLICK_VOLUME,
                down ? PRESS_PITCH : RELEASE_PITCH);
    }

    public void write(CompoundTag tag) {
        tag.putString("Action", action.id());
        tag.putBoolean("Pressed", pressed);
    }

    /**
     * What a tag describes. An indicator saved before there were contacts behind it
     * carries neither key and reads back as the plain lamp it was.
     *
     * The holder is deliberately not read, because it was never written: a button
     * that comes back down comes back held by nobody, and {@link #tick} opens it on
     * the first tick after the level is running.
     */
    public void read(CompoundTag tag) {
        action = IndicatorAction.of(tag.getString("Action"), IndicatorAction.defaultAction());
        pressed = action.clickable() && tag.getBoolean("Pressed");
        holder = null;
        apply();
    }

    /** Whether the remembered holder is still a player standing in front of this. */
    private boolean holds(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server))
            return false;
        ServerPlayer player = server.getServer().getPlayerList().getPlayer(holder);
        return player != null && player.level() == level && !player.isRemoved()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_REACH * MAX_REACH;
    }

    private void open() {
        set(false);
    }

    private void set(boolean pressed) {
        this.pressed = pressed;
        if (!pressed)
            holder = null;
        apply();
    }
}
