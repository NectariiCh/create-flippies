package com.nectariich.create_flippies.content.pc;

import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;

/**
 * The PC's power supply: when it may start, when it must stop, what it draws and
 * the resistance that draws it (spec 11.2.4). Pure arithmetic plus the two-terminal
 * circuit, so the window and the draw are tested without a world, and the circuit
 * against Power Grid's real solver.
 *
 * The supply is a bridge rectifier in front of a switching regulator, as the PSU
 * spec reasons, so it sees the magnitude of the voltage and reverse polarity works.
 *
 * It is auto-ranging between the mod's two mains ratings, and runs inside either's
 * window for mains electronics: 95 to 135 V for 120 V and 195 to 260 V for 230 V.
 * Nothing between the two is a rating, so nothing between them starts it.
 */
public final class PcPower {

    /** The 120 V range's window, volts: the supply comes up only inside it (or the 230 V one). */
    public static final double LOW_MIN_VOLTS = 95, LOW_MAX_VOLTS = 135;
    /** The 230 V range's window. */
    public static final double HIGH_MIN_VOLTS = 195, HIGH_MAX_VOLTS = 260;
    /** Once running, it holds on this far outside either window (hysteresis), volts. */
    public static final double HYSTERESIS_VOLTS = 5;

    /** Motherboard: chipset, ports and the network card, watts. */
    public static final double MOTHERBOARD_WATTS = 8;
    /** HDD spinning, and in a tick with disk activity. */
    public static final double HDD_IDLE_WATTS = 3, HDD_ACTIVE_WATTS = 6;
    /** The built-in floppy drive, motor stopped and running. */
    public static final double FLOPPY_IDLE_WATTS = 0.3, FLOPPY_MOTOR_WATTS = 3;
    /** A network modem powered over the ethernet cord (PoE, spec 12.4.5), taken from the PC's rails. */
    public static final double MODEM_WATTS = 2.5;
    /** Input watts per rail watt is 1 / this. */
    public static final double EFFICIENCY = 0.8;
    /** Draw while plugged in and off (the standby rail). */
    public static final double STANDBY_WATTS = 1;

    /** The load is kept inside these, ohms; unpowered it is the leak. */
    public static final double MIN_OHMS = 10, LEAK_OHMS = 1e6;
    /** Relative change in the load below which Power Grid is not asked to re-solve (the PLC's approach). */
    public static final double DEADBAND = 0.01;

    /** Terminal indices of the socket: Power Grid's cord ends on 0 and 1. */
    public static final int TERMINAL_A = 0, TERMINAL_B = 1, TERMINALS = 2;

    /** What happened to a running supply this tick. */
    public enum Event {
        NONE,
        BROWN_OUT,
        OVER_VOLTAGE
    }

    /** The two socket nodes and the load between them. */
    public record Circuit(FloatingNode a, FloatingNode b, ElectricWire load) {

        /** The supply voltage, magnitude only: the bridge does not mind which way round. */
        public double volts() {
            return Math.abs(a.getVoltage() - b.getVoltage());
        }
    }

    /** What is installed and what it did last tick, for the draw. */
    public record Draw(boolean motherboard, PcCpuType cpu, double busyFraction, PcRamType ramA, PcRamType ramB,
            boolean hdd, boolean hddActive, boolean floppyMotor) {}

    private PcPower() {}

    /** Two terminals and the load across them, as the PLC's logic load is built. */
    public static Circuit buildCircuit(IElectricEntity.CircuitBuilder builder) {
        builder.setTerminalCount(TERMINALS);
        FloatingNode a = builder.terminalNode(TERMINAL_A);
        FloatingNode b = builder.terminalNode(TERMINAL_B);
        ElectricWire load = builder.connect((float) LEAK_OHMS, a, b);
        return new Circuit(a, b, load);
    }

    public static boolean canStart(double volts) {
        double v = Math.abs(volts);
        return inside(v, LOW_MIN_VOLTS, LOW_MAX_VOLTS) || inside(v, HIGH_MIN_VOLTS, HIGH_MAX_VOLTS);
    }

    /**
     * For a running supply: whether this voltage stops it, and how. Between the two
     * ranges it is over-voltage nearer the 120 V window and brown-out nearer the 230 V
     * one, since which range it was running on is not kept.
     */
    public static Event check(double volts) {
        double v = Math.abs(volts);
        double lowMax = LOW_MAX_VOLTS + HYSTERESIS_VOLTS, highMin = HIGH_MIN_VOLTS - HYSTERESIS_VOLTS;
        if (inside(v, LOW_MIN_VOLTS - HYSTERESIS_VOLTS, lowMax)
                || inside(v, highMin, HIGH_MAX_VOLTS + HYSTERESIS_VOLTS))
            return Event.NONE;
        if (v < LOW_MIN_VOLTS)
            return Event.BROWN_OUT;
        if (v > HIGH_MAX_VOLTS)
            return Event.OVER_VOLTAGE;
        return v - lowMax < highMin - v ? Event.OVER_VOLTAGE : Event.BROWN_OUT;
    }

    private static boolean inside(double v, double min, double max) {
        return v >= min && v <= max;
    }

    /** CPU watts blended between idle and busy by the share of the last tick it ran. */
    public static double cpuWatts(PcCpuType cpu, double busyFraction) {
        if (cpu == null)
            return 0;
        double busy = Math.max(0, Math.min(1, busyFraction));
        return cpu.idleWatts() + (cpu.busyWatts() - cpu.idleWatts()) * busy;
    }

    /** Watts at the internal rails of a running PC. Every installed stick counts, usable or not. */
    public static double railWatts(Draw d) {
        double watts = d.motherboard() ? MOTHERBOARD_WATTS : 0;
        watts += cpuWatts(d.cpu(), d.busyFraction());
        if (d.ramA() != null)
            watts += d.ramA().watts();
        if (d.ramB() != null)
            watts += d.ramB().watts();
        if (d.hdd())
            watts += d.hddActive() ? HDD_ACTIVE_WATTS : HDD_IDLE_WATTS;
        if (d.motherboard())
            watts += d.floppyMotor() ? FLOPPY_MOTOR_WATTS : FLOPPY_IDLE_WATTS;
        return watts;
    }

    /** Rail watts with a modem linked to the ethernet port or not: the modem draws only while the PC runs. */
    public static double railWatts(Draw d, boolean modemLinked) {
        return railWatts(d) + (modemLinked ? MODEM_WATTS : 0);
    }

    /** Watts taken from the cord: the rails through the supply when running, standby when off. */
    public static double inputWatts(boolean running, double railWatts) {
        return running ? railWatts / EFFICIENCY : STANDBY_WATTS;
    }

    /** R = V^2 / P, clamped; the leak when there is no voltage or no draw. */
    public static double loadOhms(double volts, double watts) {
        double v = Math.abs(volts);
        if (v < 1e-3 || watts <= 0)
            return LEAK_OHMS;
        return Math.max(MIN_OHMS, Math.min(LEAK_OHMS, v * v / watts));
    }

    /** Whether a new resistance differs enough from the current one to be worth a re-solve. */
    public static boolean outsideDeadband(double current, double next) {
        return !(current > 0) || Math.abs(next - current) > current * DEADBAND;
    }
}
