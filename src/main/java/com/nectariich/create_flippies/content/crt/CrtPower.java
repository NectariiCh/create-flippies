package com.nectariich.create_flippies.content.crt;

import com.nectariich.create_flippies.content.pc.PcPower;

/**
 * The CRT's power (spec 11.2.14): the same socket, cord and supply window as the
 * PC case, and a draw that depends on whether there is a picture. Pure, so it is
 * tested without a world.
 *
 * The draw is at the cord, already through the monitor's own supply:
 * - 60 W with a picture: a small colour VGA monitor of the era (a 14 inch one drew
 *   about 60 to 80 W; this one's tube is smaller);
 * - 15 W powered without a signal: the tube's heater kept warm and the deflection
 *   stopped, as a monitor in its power-saving standby does;
 * - nothing but the leak when it is off (no voltage in the window).
 */
public final class CrtPower {

    public static final double PICTURE_WATTS = 60;
    public static final double NO_SIGNAL_WATTS = 15;

    private CrtPower() {}

    /**
     * Whether the monitor is on after this voltage: an off one comes on inside the
     * start window, an on one stays on inside the run window (the PC's hysteresis).
     * There is no switch: it is on whenever its supply is.
     */
    public static boolean nextOn(boolean on, double volts) {
        return on ? PcPower.check(volts) == PcPower.Event.NONE : PcPower.canStart(volts);
    }

    /** Watts taken from the cord. */
    public static double inputWatts(boolean on, boolean signal) {
        if (!on)
            return 0;
        return signal ? PICTURE_WATTS : NO_SIGNAL_WATTS;
    }

    /** The load resistance for this voltage and draw; the leak when off. */
    public static double loadOhms(double volts, boolean on, boolean signal) {
        return PcPower.loadOhms(volts, inputWatts(on, signal));
    }
}
