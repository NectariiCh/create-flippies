package com.nectariich.create_flippies.content.plc;

import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;

/**
 * The PLC's board around the microcontroller, as arithmetic: the supply monitor, the
 * input front ends, the output stage, the per-pin current limit and the power budget.
 *
 * Kept free of Minecraft, Forge and Power Grid on purpose, like everything under
 * {@code mcu}. What the board decides — is it powered, is this input high, may this
 * output conduct, has that pin just blown its limit, how much is the whole thing
 * drawing — is the part of the device most worth testing and the part least
 * connected to the game, so {@link PlcSmallControl} reads the voltages and currents
 * off its wires, hands them in here, and applies what comes back. The class can then
 * be compiled and exercised with {@code javac} alone.
 *
 * <h2>Channels</h2>
 *
 * Every I/O terminal is one channel of the current limit, in this order: DI0..DI7,
 * DO0..DO7, STLED, RESET. VCC and GND are not channels. They are the supply rails and
 * carry the sum of every output's load current — eight outputs at a few hundred
 * milliamps each is several amps through VCC on a healthy board — so a one amp limit
 * on them would trip on nothing more than the board doing its job. A real PLC protects
 * its supply with a fuse upstream, which is the circuit breaker's business here.
 *
 * <h2>The current limit</h2>
 *
 * Every channel is limited to {@link #PIN_LIMIT_AMPS}. A channel that is found
 * carrying more is latched off: an output's driver is opened, an input's front end is
 * disconnected and the MCU sees it low. The latch holds until the board is reset —
 * the RESET terminal being asserted, the menu's Reset or Upload, or the supply
 * browning out — which is how a smart high-side switch with a latching fault output
 * behaves when its diagnostic pin is wired into the reset supervisor, and it means a
 * shorted output stays off instead of hammering the short twenty times a second.
 *
 * Currents are read from the previous Power Grid solve. An output turned on into a
 * dead short therefore carries the short for one tick before it is latched — fifty
 * milliseconds, comfortably inside what a real driver's thermal shutdown would allow.
 */
public final class PlcBoard {

    // ------------------------------------------------------------ supply ---

    /** The supply monitor releases the buck converter at this many volts... */
    public static final double POWER_ON_VOLTS = 18.0;
    /** ...and browns the board out below this many. The gap is the hysteresis. */
    public static final double BROWN_OUT_VOLTS = 16.0;
    /** IEC 61131-2's operating range for a 24 V DC supply: -20 % to +20 %. */
    public static final double RANGE_MIN_VOLTS = 19.2;
    public static final double RANGE_MAX_VOLTS = 28.8;
    /** Past this the menu says overvoltage. It keeps running: there is no destruction mechanic. */
    public static final double OVERVOLTAGE_VOLTS = 30.0;

    // ------------------------------------------------------------ inputs ---

    /**
     * Each input front end, terminal to GND: about 3.5 mA at 24 V, which is IEC
     * 61131-2's type 3 input — enough current through the optocoupler's LED to be
     * immune to a leaky proximity switch, little enough not to load one.
     */
    public static final double INPUT_OHMS = 6800;
    /** An input reads ON at this many volts above GND or more (type 3: 11 V minimum, 15 V chosen)... */
    public static final double INPUT_ON_VOLTS = 15.0;
    /** ...and OFF at this many or less. Between the two it holds whatever it was. */
    public static final double INPUT_OFF_VOLTS = 5.0;

    // ----------------------------------------------------------- outputs ---

    /** On-resistance of one high-side switch, ISO8200-class: 0.12 ohm. */
    public static final double OUTPUT_ON_OHMS = 0.12;

    /** The per-channel current limit; see the class comment. */
    public static final double PIN_LIMIT_AMPS = 1.0;

    // ------------------------------------------------------------- power ---

    /** The 24 V to 3.3 V buck converter's efficiency at the board's light load. */
    public static final double BUCK_EFFICIENCY = 0.85;
    /** The logic rail. */
    public static final double LOGIC_VOLTS = 3.3;
    /** The 8 MHz HSE crystal oscillator circuit, on the 3.3 V rail. */
    public static final double HSE_AMPS = 0.0005;
    /** The regulator's own quiescent draw, referred to its 3.3 V output. */
    public static final double REGULATOR_QUIESCENT_AMPS = 0.00025;
    /**
     * Each optocoupler's output pull-up on the 3.3 V side, which conducts while its
     * input is ON: 3.3 V across 4.7 kilohm, 0.7 mA. The eight inputs and RESET each
     * have one.
     */
    public static final double OPTO_PULLUP_OHMS = 4700;
    /** The output drivers' quiescent current from 24 V: their logic and charge pumps. */
    public static final double DRIVER_IDLE_AMPS = 0.003;
    /** And per channel switched on, weighted by duty. */
    public static final double DRIVER_PER_CHANNEL_AMPS = 0.0005;
    /**
     * What an unpowered board still draws: the supply monitor's divider and the
     * buck's input-side leakage, as one megohm across the rails. It also stands in
     * for the logic load wire being open, which Power Grid cannot represent exactly.
     */
    public static final double STANDBY_OHMS = 1_000_000;
    /** The logic load is never made heavier than this, whatever the arithmetic says. */
    public static final double MIN_LOAD_OHMS = 10;

    // ---------------------------------------------------------- channels ---

    public static final int INPUTS = 8;
    /** DO0..DO7 and STLED: the same output stage, nine times. */
    public static final int OUTPUTS = 9;

    public static final int CH_DI0 = 0;
    public static final int CH_DO0 = 8;
    public static final int CH_STLED = 16;
    public static final int CH_RESET = 17;
    public static final int CHANNELS = 18;

    /** Bits of {@link #faults()} set for every channel. */
    public static final int ALL_CHANNELS = (1 << CHANNELS) - 1;

    private boolean supplyOn;
    private final boolean[] inputHigh = new boolean[INPUTS];
    private boolean resetAsserted;
    private int faults;

    /** Whether the supply monitor has the board running. */
    public boolean supplyOn() {
        return supplyOn;
    }

    /** Whether the RESET terminal is holding the MCU in reset. */
    public boolean resetAsserted() {
        return resetAsserted;
    }

    /** One bit per channel, set while that channel is latched off. */
    public int faults() {
        return faults;
    }

    public boolean faulted(int channel) {
        return (faults & (1 << channel)) != 0;
    }

    /** What the input front end presents to the optocoupler, before the latch. */
    public boolean inputHigh(int input) {
        return inputHigh[input];
    }

    /**
     * What the MCU sees on an input: the front end's level, unless the channel has
     * been latched off, in which case its front end is disconnected and the pin's
     * pull-down holds it low.
     */
    public boolean mcuInput(int input) {
        return inputHigh[input] && !faulted(CH_DI0 + input);
    }

    /** Restores a saved latch, masked to the channels that exist. */
    public void restoreFaults(int mask) {
        faults = mask & ALL_CHANNELS;
    }

    /** @return whether anything was latched */
    public boolean clearFaults() {
        boolean any = faults != 0;
        faults = 0;
        return any;
    }

    /**
     * The supply monitor, given the voltage across VCC and GND.
     *
     * A brown-out is a power cycle, and a power cycle clears every latch: the latches
     * are logic on the 3.3 V rail and lose their state with it.
     *
     * @return whether the board browned out on this call
     */
    public boolean updateSupply(double volts) {
        boolean was = supplyOn;
        supplyOn = hysteresis(was, volts, POWER_ON_VOLTS, BROWN_OUT_VOLTS);
        if (was && !supplyOn) {
            clearFaults();
            return true;
        }
        return false;
    }

    /** Each input front end, given its terminal's voltage above GND. */
    public void updateInputs(double[] voltsAboveGround) {
        for (int i = 0; i < INPUTS; i++)
            inputHigh[i] = hysteresis(inputHigh[i], voltsAboveGround[i], INPUT_ON_VOLTS, INPUT_OFF_VOLTS);
    }

    /**
     * The RESET front end, given its terminal's voltage above GND. It is the same
     * front end as an input, with the same thresholds — except that a latched RESET
     * channel is disconnected and so can never assert.
     *
     * The latches are cleared on the edge where reset is asserted, not on every tick
     * it is held: the outputs are off for as long as it is held anyway, and clearing
     * once leaves an input that is still being overdriven latched again on the next
     * tick rather than flickering.
     *
     * @return whether reset was asserted on this call, having not been before
     */
    public boolean updateReset(double voltsAboveGround) {
        boolean was = resetAsserted;
        boolean level = hysteresis(was, voltsAboveGround, INPUT_ON_VOLTS, INPUT_OFF_VOLTS);
        resetAsserted = level && !faulted(CH_RESET);
        if (resetAsserted && !was) {
            clearFaults();
            return true;
        }
        return false;
    }

    /**
     * Latches every channel carrying more than {@link #PIN_LIMIT_AMPS}, either way.
     *
     * Only while the board is powered: the latch is logic on the 3.3 V rail, and an
     * unpowered board has no logic to hold one. Its outputs are off then, so the only
     * current a channel can carry is what is forced into an input, which is the input
     * resistor's to bear.
     *
     * An output's figure must be its on-state current, {@link #onStateAmps}, not
     * the average its wire carries: a PWM output into a 1.5 A load is passing 1.5 A
     * on every pulse, whatever its duty.
     *
     * @param amps one per channel, in {@code CH_} order
     * @return whether any channel was newly latched
     */
    public boolean checkOvercurrent(double[] amps) {
        if (!supplyOn)
            return false;
        int before = faults;
        for (int channel = 0; channel < CHANNELS; channel++)
            if (Math.abs(amps[channel]) > PIN_LIMIT_AMPS)
                faults |= 1 << channel;
        if (faulted(CH_RESET))
            resetAsserted = false;
        return faults != before;
    }

    /**
     * How much of the window an output stage conducts, 0..1.
     *
     * Only an MCU pin configured as an output and driving high turns its driver on. A
     * pin in any input mode — floating, pulled up, pulled down, analog — leaves it
     * off, which matters for INPUT_PULLUP in particular: on an STM32 that mode is
     * selected by setting the pin's ODR bit, and a board that read ODR rather than
     * the mode would switch the load on. Anything faster than the tick — PWM, tone(),
     * bit-banging — arrives as the fraction of the window it spent high and is
     * presented to Power Grid as that average.
     *
     * Off whatever the pin says while the board is unpowered, the MCU is held in reset
     * or being programmed, there is no program, or the channel is latched. A HardFault
     * leaves the outputs where they were, because STM32duino's handler is a
     * {@code while(1)} that never touches the GPIOs — the chip reports the frozen
     * drive, and it is applied like any other.
     *
     * @param channel {@link #CH_DO0}..{@link #CH_STLED}
     */
    public double outputDuty(int channel, PinDrive drive, McuState state) {
        if (!supplyOn || resetAsserted || faulted(channel) || drive == null || !drive.output())
            return 0;
        if (state != McuState.RUNNING && state != McuState.HARD_FAULT)
            return 0;
        double duty = drive.highFraction();
        if (!(duty > 0))
            return 0;
        return Math.min(duty, 1);
    }

    // -------------------------------------------------- the averaged output ---
    //
    // An output switched faster than the tick — PWM, tone(), bit-banging — has to
    // reach Power Grid as one steady resistance from VCC to the pin, and the
    // question is which one. Scaling the switch's own conductance by the duty
    // (R_on / duty) looks natural and is nearly useless: R_on is 0.12 ohm, any real
    // load is ohms to kilohms, and the switch stays negligible in series however
    // much it is divided, so a quarter duty into 24 ohm still passed 98 % of the
    // full-on current. The switch is not what limits the current; the load is.
    //
    // So the series resistance is chosen from the load. The load is read off the
    // previous solve as the pin's voltage above GND over the current — exact for a
    // resistive load returned to GND, whatever the switch was set to at the time —
    // and the switch is given whatever makes the mean current come out right:
    //
    //     I_avg = duty · V / (R_on + R_L)   =>   R_sw = (R_on + R_L) / duty − R_L
    //
    // Mean current rather than mean power (which would need the square root of the
    // duty) because it keeps the supply side honest: what Power Grid draws from VCC
    // is V · I_avg, which is exactly the real board's average draw, so a PWM'd
    // output costs its source what it really costs. The load's own share is then
    // under-stated inside Power Grid — the rest is dropped across the inflated
    // switch — which is why the menu works out what is delivered and dissipated
    // from the on-state current instead of from the averaged wire; see
    // onStateAmps(), driverLoss() and delivered().
    //
    // The same reasoning applies to the current limit. A real high-side switch sees
    // the on-state current on every pulse, not the average, so a PWM output into a
    // 1.5 A load must trip at any duty; the limit is checked against onStateAmps().

    /**
     * Below this many amps through an output the reading is too small to say what
     * the load is: an open pin, or a switch that was off during the solve.
     */
    public static final double LOAD_SENSE_AMPS = 0.001;

    /**
     * The load an output was driving on the last solve, in ohms: its pin's voltage
     * above GND over the current through its switch. NaN when that says nothing —
     * too little current to measure, or a pin pulled below GND by something other
     * than a load to GND, where the arithmetic would give a negative resistance.
     *
     * @param pinVolts the pin's voltage above GND
     * @param amps     the current through the output's switch, either sign
     */
    public static double loadOhms(double pinVolts, double amps) {
        double current = Math.abs(amps);
        if (!(current > LOAD_SENSE_AMPS) || !(pinVolts >= 0))
            return Double.NaN;
        return pinVolts / current;
    }

    /**
     * The averaged output stage as one resistance from VCC to the pin, chosen so
     * that a load of {@code loadOhms} carries {@code duty} of its on-state current;
     * see the comment above. Exactly {@link #OUTPUT_ON_OHMS} at full duty, never less
     * than that at any duty, and infinite at zero, where the caller opens the switch
     * instead.
     *
     * With no load known yet — the output was off on the last solve — it falls back
     * to scaling the switch's conductance alone, R_on / duty. That passes nearly the
     * full current for the one tick it takes to read the load, after which the
     * proper figure takes over; the current limit is unaffected, since
     * {@link #onStateAmps} works from the voltages and not from what the switch was
     * set to.
     */
    public static double outputResistance(double duty, double loadOhms) {
        if (!(duty > 0))
            return Double.POSITIVE_INFINITY;
        if (duty >= 1)
            return OUTPUT_ON_OHMS;
        if (!(loadOhms >= 0) || Double.isInfinite(loadOhms))
            return OUTPUT_ON_OHMS / duty;
        double ohms = (OUTPUT_ON_OHMS + loadOhms) / duty - loadOhms;
        return Math.max(OUTPUT_ON_OHMS, ohms);
    }

    /**
     * The current an output carries while its switch is actually closed, which is
     * what the per-pin limit is about.
     *
     * At full duty — or for an input, or anything else not switched — it is simply
     * the current measured. Below full duty the load measured on the same solve is
     * put back in series with the bare on-resistance: V / (R_on + R_L). That holds
     * whatever the averaged switch was set to during the solve, so it is right on the
     * very first tick of a PWM output too. With no load to go on, the measured
     * average is scaled back up by the duty, which is the same thing for a resistive
     * load to GND.
     *
     * @param amps        the current through the output's wire on the last solve
     * @param duty        the duty that wire was driven at during that solve
     * @param supplyVolts VCC above GND on that solve
     * @param pinVolts    the pin above GND on that solve
     */
    public static double onStateAmps(double amps, double duty, double supplyVolts, double pinVolts) {
        double current = Math.abs(amps);
        if (!(duty > 0) || duty >= 1)
            return current;
        double load = loadOhms(pinVolts, amps);
        if (Double.isNaN(load))
            return current / duty;
        return Math.max(supplyVolts, 0) / (OUTPUT_ON_OHMS + load);
    }

    /** What an output switch dissipates on average: on for {@code duty} of the time at {@code onAmps}. */
    public static double driverLoss(double duty, double onAmps) {
        return Math.max(duty, 0) * onAmps * onAmps * OUTPUT_ON_OHMS;
    }

    /**
     * What an output delivers into its load on average: on for {@code duty} of the
     * time, drawing {@code onAmps} from a supply of {@code supplyVolts}, less what
     * the switch keeps. At full duty this is the pin's voltage times its current.
     */
    public static double delivered(double duty, double onAmps, double supplyVolts) {
        return Math.max(duty, 0) * onAmps * Math.max(supplyVolts, 0) - driverLoss(duty, onAmps);
    }

    /** Where a voltage sits for a two-threshold comparator that was {@code was}. */
    public static boolean hysteresis(boolean was, double volts, double onAt, double offAt) {
        if (volts >= onAt)
            return true;
        if (volts <= offAt || Double.isNaN(volts))
            return false;
        return was;
    }

    /** What the supply looks like, for the menu. */
    public static SupplyCondition condition(boolean on, double volts) {
        if (!on)
            return SupplyCondition.OFF;
        if (volts > OVERVOLTAGE_VOLTS)
            return SupplyCondition.OVERVOLTAGE;
        if (volts > RANGE_MAX_VOLTS)
            return SupplyCondition.HIGH;
        if (volts < RANGE_MIN_VOLTS)
            return SupplyCondition.LOW;
        return SupplyCondition.NORMAL;
    }

    public enum SupplyCondition {
        /** Below power-on, or browned out and not yet back above it. */
        OFF,
        /** Running, but under IEC 61131-2's 19.2 V. */
        LOW,
        NORMAL,
        /** Running, over 28.8 V but not yet 30. */
        HIGH,
        /** Over 30 V. Still running. */
        OVERVOLTAGE
    }

    // ------------------------------------------------------------- power ---

    /**
     * The board's power budget for one tick.
     *
     * The logic side — the MCU's 3.3 V domain, the rest of the 3.3 V rail, and the
     * buck converter's losses in making it — plus the output drivers' own quiescent
     * draw is what the board takes from VCC for itself, and is what the logic load
     * wire across the rails is set to draw. Everything after that is what flows
     * through the I/O pins, which already flows through Power Grid's own wires and is
     * counted here only so that the total is what the user asked for: every operation
     * the PLC does and every pin it drives.
     *
     * @param mcu             the MCU's 3.3 V domain, from the emulator's per-cycle model
     * @param board           the rest of the 3.3 V rail: HSE, regulator, opto pull-ups
     * @param buckLoss        what the buck converter turns into heat making those two
     * @param driverQuiescent the output drivers' own draw from 24 V
     * @param standby         what an unpowered board leaks across the rails
     * @param inputs          dissipated in the input front ends, DI0..DI7 and RESET
     * @param driverLoss      dissipated in the output switches' on-resistance
     * @param delivered       delivered out of DO0..DO7 and STLED into whatever they drive
     */
    public record Power(double mcu, double board, double buckLoss, double driverQuiescent, double standby,
            double inputs, double driverLoss, double delivered) {

        public static final Power NONE = new Power(0, 0, 0, 0, 0, 0, 0, 0);

        /** What the logic load wire across VCC and GND draws. */
        public double logicDraw() {
            return mcu + board + buckLoss + driverQuiescent + standby;
        }

        /** Everything through every pin. */
        public double pins() {
            return inputs + driverLoss + delivered;
        }

        public double total() {
            return logicDraw() + pins();
        }
    }

    /**
     * Works out the budget from what the board is doing.
     *
     * @param supplyVolts  across VCC and GND
     * @param mcuWatts     the MCU's 3.3 V domain over the last window
     * @param optosOn      how many optocouplers are conducting: inputs ON and not
     *                     latched, plus RESET if it is asserted
     * @param dutySum      the sum of every output's duty, 0..9
     * @param inputWatts   the sum of V·I over the input front ends
     * @param driverWatts  the sum of what the output switches dissipate
     * @param loadWatts    the sum of V·I out of the outputs into their loads
     */
    public Power power(double supplyVolts, double mcuWatts, int optosOn, double dutySum, double inputWatts,
            double driverWatts, double loadWatts) {
        double volts = Math.max(supplyVolts, 0);
        if (!supplyOn)
            return new Power(0, 0, 0, 0, volts * volts / STANDBY_OHMS, inputWatts, driverWatts, loadWatts);
        double mcu = Math.max(mcuWatts, 0);
        double board = LOGIC_VOLTS * (HSE_AMPS + REGULATOR_QUIESCENT_AMPS)
                + optosOn * LOGIC_VOLTS * LOGIC_VOLTS / OPTO_PULLUP_OHMS;
        double buckLoss = (mcu + board) * (1 / BUCK_EFFICIENCY - 1);
        double driverQuiescent = volts * (DRIVER_IDLE_AMPS + DRIVER_PER_CHANNEL_AMPS * dutySum);
        return new Power(mcu, board, buckLoss, driverQuiescent, 0, inputWatts, driverWatts, loadWatts);
    }

    /**
     * The resistance across the rails that draws {@code watts} at {@code volts}:
     * V²/P, made no heavier than {@link #MIN_LOAD_OHMS} and no lighter than
     * {@link #STANDBY_OHMS}. A board with nothing to draw, or no supply to draw it
     * from, is left at standby rather than at an infinite resistance Power Grid would
     * refuse.
     */
    public static double logicLoadResistance(double volts, double watts) {
        if (!(volts > 0.1) || !(watts > 0))
            return STANDBY_OHMS;
        double ohms = volts * volts / watts;
        return Math.max(MIN_LOAD_OHMS, Math.min(STANDBY_OHMS, ohms));
    }
}
