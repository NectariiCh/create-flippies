package com.nectariich.create_flippies.content.circuit_breaker;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;

/**
 * One breaker clipped to a rail: a contact across each pole, all thrown together,
 * and the two elements that open them whether anyone asked or not.
 *
 * The contacts are {@code SwitchedWire}s rather than wires that come and go, for
 * the same reason the rotary switch's are. Power Grid streams a block's
 * switched-wire states to the client positionally with no count, so a control
 * whose wire count varied with its own state would shift every later control's
 * readings on the same rail — a breaker tripping at one end would corrupt the
 * appearance of every breaker after it.
 *
 * <h2>Ganging</h2>
 *
 * The poles are ganged, as the exports draw them: one bar across every lever, so
 * one state for the lot. A breaker is made on all its poles or none of them, and
 * an overload on any one pole opens all of them — which is the whole point of a
 * multi-pole breaker, since a motor left running on two phases of three is worse
 * off than one that has stopped.
 *
 * <h2>Tripping</h2>
 *
 * A type C characteristic, which is what the letter in front of the rating on a
 * real MCB names: two elements in series with the contacts, either of which can
 * throw the latch, and between them the two halves of the IEC 60898-1 curve.
 *
 * The <em>thermal</em> element is the inverse-time half. A bimetal strip warms
 * with the square of the current and cools towards ambient on a time constant,
 * and bends far enough to trip at the temperature {@link #HOLD_MULTIPLE} times
 * the rating would reach if it were left there for ever. So a load below that
 * never gets there at all, one just above it takes minutes, and one well above it
 * takes a moment — the further past the rating, the sooner it goes. {@link #heat}
 * is that strip, and the whole of the curve below falls out of the one time
 * constant:
 *
 * <pre>
 *   × rating    trips after
 *      1.13     never
 *      1.2      57 s
 *      1.45     24 s
 *      2        10 s
 *      3         4.0 s
 *      5         1.4 s
 *      7.5       0.1 s   (magnetic)
 * </pre>
 *
 * The <em>magnetic</em> element is the vertical drop at the end of it. A coil
 * pulls the latch open on the spot once the current reaches
 * {@link #MAGNETIC_MULTIPLE} times the rating, without waiting for anything to
 * warm up, because at that point it is not an overload but a fault. IEC 60898-1
 * puts a type C's instantaneous trip anywhere between five and ten times the
 * rating; 7.5 sits in the middle of that band, and where that drop goes is the
 * whole difference between a type C and the type B and D breakers that share the
 * same curve with it moved.
 *
 * This replaced a flat rule — carry one amp over the rating for ever, open half a
 * second past that — asked for on 2026-08-25 for predictability and asked back on
 * 2026-08-26. Don't restore it without asking: the point of the curve is that one
 * breaker both rides out the inrush of something starting up and clears a short
 * before anything downstream notices, which a single threshold and a single delay
 * cannot do at both ends.
 *
 * <h2>Thermal memory</h2>
 *
 * The strip carries on cooling while the breaker is open, and closing it by hand
 * does not reset it. A breaker that has just tripped is therefore still hot and
 * will go again far sooner than the table says if it is closed straight back onto
 * the same load; left alone for a minute it is cold again. That is what a real one
 * does, it is why the standard quotes separate hot and cold curves, and it is what
 * makes repeatedly slamming a marginal load onto a breaker fail rather than work.
 *
 * The heating, the cooling and the counting all run on the server alone. Both
 * sides tick, and both ease the switch towards where it has been put, but only one
 * of them may decide to move it: a client that tripped its own copy would sit open
 * until the next sync told it otherwise, and would trip again immediately
 * afterwards.
 */
public class CircuitBreakerControl extends HostedControl {

    /** Resistance of one closed contact, low enough to be nearly nothing. */
    public static final float CONTACT_RESISTANCE = 0.005f;

    /**
     * IEC 60898-1's conventional non-tripping current as a multiple of the rating:
     * the most the thermal element lets past indefinitely. A CB16 holds 18.08 A for
     * as long as you like, and anything above that is on a clock.
     */
    public static final double HOLD_MULTIPLE = 1.13;

    /**
     * Where the magnetic element picks up, as a multiple of the rating. The one
     * number that makes this a type C rather than a type B or a type D.
     */
    public static final double MAGNETIC_MULTIPLE = 7.5;

    /** How long the magnetic element takes to open: the standard's 0.1 s, to the tick. */
    public static final int MAGNETIC_DELAY = 2;

    /**
     * The thermal element's time constant, in ticks — 26 seconds. This one number
     * sets where the whole inverse-time half of the curve lands; the table in the
     * class comment is what it works out to, and changing it moves every row.
     */
    public static final int THERMAL_TIME_CONSTANT = 520;

    /**
     * What {@link #heat} has to reach for the latch to go. The strip settles at the
     * square of the current in multiples of the rating, so the temperature
     * {@link #HOLD_MULTIPLE} times the rating tends towards is that squared.
     */
    private static final double TRIP_HEAT = HOLD_MULTIPLE * HOLD_MULTIPLE;

    /**
     * How loud a breaker is to close. It is a switch under a thumb, not an event,
     * so it sits a little under a trip -- but only a little, because every take of
     * both is levelled to the same loudness by tools/convert_audio.py and this is
     * now the only thing deciding which of them is louder. Make them equal here if
     * that difference is not wanted.
     */
    private static final float CLOSE_VOLUME = 0.85f;

    /** How loud it is to open by hand: the same latch, let go the same way. */
    private static final float OPEN_VOLUME = 0.85f;

    /**
     * How loud it is when an element throws it: as loud as the game will play
     * anything, because a trip is the one thing here that happens without anybody
     * standing in front of it and is worth hearing from across a room.
     *
     * There is no headroom above this. Minecraft clamps a sound's gain at one and
     * spends anything past it on carrying further instead, so a louder trip than
     * this has to come from the recordings, which means the target in
     * convert_audio.py rather than a number here.
     */
    private static final float TRIP_VOLUME = 1.0f;

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".circuit_breaker.goggles.";

    /** Whether the contacts are made. One state for every pole; see the class comment. */
    private boolean closed = true;

    /** Which element opened it, if it was not somebody's hand. */
    private CircuitBreakerTrip trip = CircuitBreakerTrip.NONE;

    /** One per pole, in pole order. Null until the host builds the circuit. */
    private SwitchedWire[] contacts;

    /**
     * The bimetal strip as a number: the current squared, in multiples of the
     * rating, put through a first-order lag of {@link #THERMAL_TIME_CONSTANT}. One
     * is where the rated current settles, {@link #TRIP_HEAT} is where the latch
     * goes. Server-side only; the client is told the outcome.
     */
    private double heat;

    /** Consecutive ticks over the magnetic threshold. Server-side only, for the same reason. */
    private int faultTicks;

    public float switchAngle = CircuitBreakerModel.CLOSED_ANGLE;
    public float prevSwitchAngle = CircuitBreakerModel.CLOSED_ANGLE;

    CircuitBreakerControl(CircuitBreakerType type) {
        super(type);
    }

    public CircuitBreakerType breaker() {
        return (CircuitBreakerType) type();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isTripped() {
        return trip != CircuitBreakerTrip.NONE;
    }

    /** Which element opened it, or {@link CircuitBreakerTrip#NONE} if none did. */
    public CircuitBreakerTrip trip() {
        return trip;
    }

    /** The worst current through any pole, in amps; zero on the client, which is not told. */
    public double current() {
        double worst = 0;
        if (contacts != null)
            for (SwitchedWire contact : contacts)
                worst = Math.max(worst, Math.abs(contact.current()));
        return worst;
    }

    /** The most this breaker carries indefinitely, in amps. */
    public double holdCurrent() {
        return breaker().rating() * HOLD_MULTIPLE;
    }

    /** From this current up, in amps, it opens on the spot rather than waiting for the strip. */
    public double faultCurrent() {
        return breaker().rating() * MAGNETIC_MULTIPLE;
    }

    /**
     * How far along the thermal element is, from cold at zero to tripping at one.
     * Anything between the two is a breaker on its way there, which will arrive
     * unless the load comes off.
     */
    public double thermalLoad() {
        return heat / TRIP_HEAT;
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        int poles = breaker().poles().poles();
        builder.setTerminalCount(poles * 2);
        contacts = new SwitchedWire[poles];
        for (int pole = 0; pole < poles; pole++)
            contacts[pole] = builder.connectSwitch(CONTACT_RESISTANCE,
                    builder.terminalNode(CircuitBreakerModel.line(pole)),
                    builder.terminalNode(CircuitBreakerModel.load(pole)),
                    closed);
    }

    /**
     * A click throws the breaker by hand. Closing one that tripped is the reset,
     * which is what the lever of a real breaker does too — and if whatever tripped
     * it is still there, it goes again on its own, the sooner for the heat still in
     * the strip.
     */
    @Override
    public InteractionResult use(Player player, InteractionHand hand, Vec3 local) {
        setClosed(!closed);
        play(closed ? ModSounds.CIRCUIT_BREAKER_CLOSE.get() : ModSounds.CIRCUIT_BREAKER_TRIP.get(),
                closed ? CLOSE_VOLUME : OPEN_VOLUME);
        return InteractionResult.CONSUME;
    }

    /**
     * Puts the contacts where a hand has put them. {@link #heat} is deliberately
     * left alone: a lever cannot cool a bimetal strip, and a reset that did would
     * hand an unlimited supply of full-length overloads to anyone willing to keep
     * clicking.
     */
    public void setClosed(boolean closed) {
        this.closed = closed;
        if (closed) {
            trip = CircuitBreakerTrip.NONE;
            faultTicks = 0;
        }
        applyContacts();
        changed();
    }

    @Override
    public void tick() {
        prevSwitchAngle = switchAngle;
        float target = closed ? CircuitBreakerModel.CLOSED_ANGLE : CircuitBreakerModel.OPEN_ANGLE;
        switchAngle += (target - switchAngle)
                * (closed ? CircuitBreakerModel.CLOSE_EASING : CircuitBreakerModel.OPEN_EASING);
        if (Math.abs(target - switchAngle) < 0.05f)
            switchAngle = target;

        Level level = host() == null ? null : host().level();
        if (level == null || level.isClientSide || contacts == null)
            return;

        // Warms and cools whether the contacts are made or not. An open breaker's
        // strip is cooling towards ambient, and how far it has got is the whole of
        // what decides how much of a second go the thing has left in it. It also
        // has to run before the check below, so that the tick a reset lands on is
        // one the strip has cooled through rather than one it trips on
        double multiple = current() / breaker().rating();
        heat += (multiple * multiple - heat) / THERMAL_TIME_CONSTANT;

        if (!closed) {
            faultTicks = 0;
            return;
        }

        // The magnetic element first, and over consecutive ticks like the thermal
        // one: a single tick at fault current is a solver still settling after
        // something switched, not a short
        if (multiple >= MAGNETIC_MULTIPLE) {
            if (++faultTicks >= MAGNETIC_DELAY)
                open(level, CircuitBreakerTrip.MAGNETIC);
            return;
        }
        faultTicks = 0;

        if (heat >= TRIP_HEAT)
            open(level, CircuitBreakerTrip.THERMAL);
    }

    @Override
    public void write(CompoundTag tag) {
        tag.putBoolean("Closed", closed);
        tag.putString("Trip", trip.name());
        tag.putDouble("Heat", heat);
    }

    @Override
    public void read(CompoundTag tag) {
        closed = !tag.contains("Closed") || tag.getBoolean("Closed");
        trip = readTrip(tag);
        heat = tag.getDouble("Heat");
        // The circuit may have been built before this ran, from the state this
        // breaker started in rather than the one it was saved in
        applyContacts();
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        CircuitBreakerType breaker = breaker();
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header",
                breaker.poles().poles(), breaker.rating()).withStyle(ChatFormatting.WHITE)));
        Component state = Component.translatable(GOGGLE_PREFIX + (closed ? "closed" : trip.stateKey()))
                .withStyle(closed ? ChatFormatting.GREEN : trip.color());
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "state", state).withStyle(ChatFormatting.GRAY)));
        if (closed)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "current",
                    Component.literal(String.format("%.2f", current())).withStyle(ChatFormatting.AQUA))
                    .withStyle(ChatFormatting.GRAY)));
        return true;
    }

    /**
     * Which element a save says opened it. Breakers saved before the curve existed
     * say only that something did, and under the flat rule that something was
     * always the overload.
     */
    private static CircuitBreakerTrip readTrip(CompoundTag tag) {
        if (tag.contains("Trip"))
            return CircuitBreakerTrip.byName(tag.getString("Trip"));
        return tag.getBoolean("Tripped") ? CircuitBreakerTrip.THERMAL : CircuitBreakerTrip.NONE;
    }

    /** Throws the latch, from whichever element got there first. */
    private void open(Level level, CircuitBreakerTrip cause) {
        closed = false;
        trip = cause;
        faultTicks = 0;
        applyContacts();
        changed();
        play(ModSounds.CIRCUIT_BREAKER_TRIP.get(), TRIP_VOLUME);
    }

    /**
     * Makes a noise at the breaker, if it is anywhere. Pitch is left at one and the
     * variation comes from the recordings themselves -- each of these events is
     * several takes and the sound engine picks between them, which is a better
     * rattle than the same clip resampled.
     *
     * Both callers reach here from the server alone, so this broadcasts to whoever
     * is near rather than playing locally.
     */
    private void play(SoundEvent sound, float volume) {
        Level level = host() == null ? null : host().level();
        if (level != null)
            level.playSound(null, host().pos(), sound, SoundSource.BLOCKS, volume, 1f);
    }

    /** Point every pole's contact at whatever the breaker's one state says. */
    private void applyContacts() {
        if (contacts == null)
            return;
        for (SwitchedWire contact : contacts)
            contact.setState(closed);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
