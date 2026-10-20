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
 */
public final class PcPower {

    /** The start window, volts: the supply comes up only inside it. */
    public static final double START_MIN_VOLTS = 95, START_MAX_VOLTS = 270;
    /** The run window: once running, it holds on from 90 to 280 V (hysteresis). */
    public static final double RUN_MIN_VOLTS = 90, RUN_MAX_VOLTS = 280;

    /** Motherboard: chipset, ports and the network card, watts. */
    public static final double MOTHERBOARD_WATTS = 8;
    /** HDD spinning, and in a tick with disk activity. */
    public static final double HDD_IDLE_WATTS = 3, HDD_ACTIVE_WATTS = 6;
    /** The built-in floppy drive, motor stopped and running. */
    public static final double FLOPPY_IDLE_WATTS = 0.3, FLOPPY_MOTOR_WATTS = 3;
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
        return v >= START_MIN_VOLTS && v <= START_MAX_VOLTS;
    }

    /** For a running supply: whether this voltage stops it, and how. */
    public static Event check(double volts) {
        double v = Math.abs(volts);
        if (v > RUN_MAX_VOLTS)
            return Event.OVER_VOLTAGE;
        if (v < RUN_MIN_VOLTS)
            return Event.BROWN_OUT;
        return Event.NONE;
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
