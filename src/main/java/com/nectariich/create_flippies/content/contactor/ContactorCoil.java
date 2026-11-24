package com.nectariich.create_flippies.content.contactor;

/**
 * How every contactor coil in the mod behaves, the DIN contactors' and the open
 * contactor's coil terminal alike: when it pulls in, when it lets go, and what it
 * draws. The user set these figures on 2026-11-09.
 *
 * <h2>Pull-in and hold</h2>
 *
 * A released coil pulls in at {@link #PICK_UP} of its rated voltage. Once in, it
 * holds safely down to {@link #SAFE_HOLD}; between {@link #MIN_HOLD} and that it
 * still holds, but each tick has a {@link #RELEASE_CHANCE} chance to let go; below
 * {@link #MIN_HOLD} it drops out.
 *
 * <h2>What it draws</h2>
 *
 * Its rated power at its rated voltage, and in proportion to the voltage below that:
 * a coil held at 15 % draws 15 % of its rated power, which is a constant current
 * of rated power over rated voltage. That is not a resistor, so it is modelled as
 * one re-valued each tick from the last solve's voltage — {@link #targetOhms} —
 * eased half way there per tick so a soft supply settles rather than hunts. Below
 * {@link #MIN_HOLD} and above the rated voltage it is a plain resistor at the
 * value it has at those two ends.
 */
public final class ContactorCoil {

    public static final double PICK_UP = 0.90;
    public static final double SAFE_HOLD = 0.20;
    public static final double MIN_HOLD = 0.10;
    public static final double RELEASE_CHANCE = 0.001;

    /** How far a coil's resistance moves towards its target in a tick. */
    static final double EASING = 0.5;

    /** Below this relative difference the resistance is left alone, so a settled network is not re-solved. */
    static final double SETTLED = 0.01;

    private ContactorCoil() {}

    /**
     * Whether a coil seeing {@code fraction} of its rated voltage is pulled in, given
     * whether it was. {@code roll} is a uniform draw in [0, 1), read only in the
     * uncertain band.
     */
    public static boolean decide(boolean was, double fraction, double roll) {
        fraction = Math.abs(fraction);
        if (!was)
            return fraction >= PICK_UP;
        if (fraction >= SAFE_HOLD)
            return true;
        if (fraction < MIN_HOLD)
            return false;
        return roll >= RELEASE_CHANCE;
    }

    /** The resistance that draws what the coil should at {@code volts}; see the class comment. */
    public static double targetOhms(double ratedVolts, double ratedWatts, double volts) {
        double ratedAmps = ratedWatts / ratedVolts;
        double seen = Math.max(MIN_HOLD * ratedVolts, Math.min(ratedVolts, Math.abs(volts)));
        return seen / ratedAmps;
    }

    /** The resistance at rated voltage, which a coil starts at. */
    public static double ratedOhms(double ratedVolts, double ratedWatts) {
        return ratedVolts * ratedVolts / ratedWatts;
    }

    /** One tick's step from {@code current} towards {@code target}, or {@code current} if close enough. */
    public static double ease(double current, double target) {
        if (Math.abs(target - current) <= SETTLED * Math.max(current, target))
            return current;
        return current + (target - current) * EASING;
    }
}
