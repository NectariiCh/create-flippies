package com.nectariich.create_flippies.content.psu;

/**
 * The brains of a DIN-rail power supply, with no world and no solver around it:
 * given what the last Power Grid solve measured at the terminals, what the supply
 * does for the next one.
 *
 * Kept free of Minecraft and Power Grid so every rule below can be unit tested on
 * plain numbers. {@link PsuControl} is the glue: once a server tick it reads the
 * nodes and wires, calls {@link #tick}, and puts {@link #setpoint()},
 * {@link #outputOhms()}, {@link #outputOn()}, {@link #dcOk()} and
 * {@link #inputOhms()} back into the circuit. Everything is therefore a tick
 * behind the solve it reacts to, which is the same exchange the PLC makes and is
 * invisible at a twentieth of a second.
 *
 * Every threshold is a fraction of the variant's own rating, so a 120 V or a 5 V
 * supply added to {@link PsuType}'s table needs no figure here changed.
 *
 * <h2>Input</h2>
 *
 * Power Grid has no AC, so the "230 V" input is the magnitude of the DC across
 * VCC and GND. Its sign is ignored: an AC-input supply's front end is a bridge
 * rectifier, and a bridge fed DC either way round charges its bulk capacitor all
 * the same. Two comparators with hysteresis decide whether the input is usable,
 * the way a real supply's UVLO and OVP do:
 *
 * <pre>
 *   under-voltage   starts at  ≥ 0.85 × V_in   drops out at  &lt; 0.75 × V_in
 *   over-voltage    trips at   &gt; 1.20 × V_in   restarts at   ≤ 1.15 × V_in
 * </pre>
 *
 * which for 230 V is 195.5 V / 172.5 V and 276 V / 264.5 V. Below a tenth of
 * nominal the input is taken to be simply absent and the supply reads as Off
 * rather than as Input low.
 *
 * <h2>Output</h2>
 *
 * The output is a source of {@link #setpoint()} volts — the rated voltage while
 * running — behind a series resistance of {@link #outputOhms()}, and the supply
 * regulates by moving the resistance. To decide,
 * it fits the load seen at the terminals to a line, V = v0 + I × r: through the
 * last two solves when the current moved enough between them to give a slope,
 * else the line found before if the last solve still sits on it, else a plain
 * resistor, r = V / I. A resistor is then right in one tick, and a battery or
 * anything else that is a source behind a resistance in two.
 *
 * <b>Constant voltage.</b> While the line says the load takes no more than the
 * limit, the series resistance is {@link #OUTPUT_OHMS}: V+ is the rated voltage
 * at no load and droops by that resistance with the load, 0.23 V at the limit of
 * a 100 W 24 V supply — inside the 1 % load regulation a DIN supply quotes. The
 * droop is deliberate, and it is what a real supply's "parallel" setting does:
 * two supplies that each made up their own drop exactly would both be trying to
 * hold one rail at one voltage, the split between them would be anyone's, and
 * one would creep up until it hit its limit. With the droop, two running
 * supplies in constant voltage are two fixed sources behind fixed resistances —
 * a passive network that shares by those resistances and has nothing to swing.
 *
 * <b>Constant current.</b> At {@link #LIMIT_MULTIPLE} times the rated current the
 * source stays at the rated voltage and the series resistance goes up instead, to
 * whatever puts exactly the limit through the fitted line; the supply reports
 * Overload (CC) while it does. Limiting by resistance rather than by lowering the
 * source is what makes supplies safe to parallel. Every running supply's source
 * sits at its rated voltage, so however wrongly two of them read each other as
 * part of the load, there is no difference between them to drive current round
 * through the pair. Limiting by lowering the source, the
 * one that guessed low pulls the rail down under the other, and the two swing
 * hundreds of amps between them every tick.
 *
 * <b>Quick to limit, slow to let go.</b> The series resistance rises to what the
 * limit needs in one tick, but falls by at most half a tick
 * ({@link #RECOVERY_FACTOR}), so coming out of a limit or a soft start ramps
 * over a few ticks — about seven from a cold start to a rated load. While the
 * fitted line keeps moving from one solve to the next, which is what a paralleled
 * supply changing its own resistance in the same tick looks like, it falls no
 * further than would put the limit into the rail as last measured either; a line
 * that held still is trusted. Without that second rule two supplies taking load
 * off each other read each other's moves as their own load, overshoot by turns,
 * and carry a quarter over the limit for a second; with it neither goes over.
 * Whatever the line says, a solve that carried more than the limit puts the
 * supply straight into Overload (CC).
 *
 * <b>Soft start.</b> A start — from cold, out of a hiccup, or onto a rail that has
 * fallen back below this supply — puts the source at the rated voltage behind
 * whatever drives the limit into the rail as it was measured,
 * (V_out − V) / I_lim: into a dead short exactly the limit, and onto a rail
 * another supply already holds at the rated voltage {@link #OUTPUT_OHMS} and
 * nothing. Without it a start into a short would put 480 A through the wiring for
 * a tick before the limit caught up.
 *
 * <b>Output diode.</b> A supply's output stage cannot sink current, and a DIN
 * supply meant for paralleling has a diode in its output to make sure. So a solve
 * that finds current flowing <em>into</em> V+ — something else holding the rail
 * above this supply — opens the output as a reverse-biased diode would, and it
 * stays open while the rail is at or above the rated voltage. The supply is still
 * running, and DC OK still reads the rail.
 *
 * <b>Hiccup.</b> A load so heavy that holding the limit leaves less than a tenth
 * of the rated voltage is a short, and a supply that sat in a short at full
 * current would cook. So it turns the output off for {@link #HICCUP_TICKS} (a
 * second), then soft-starts again; a short that is still there is found again on
 * the first tick back and the cycle repeats.
 *
 * <h2>DC OK</h2>
 *
 * A sourcing switch from V+ to the DC OK pin, closed while the supply runs and the
 * measured output is within 0.75–1.125 × V_out — 18–27 V for a 24 V supply, the
 * window the usual DC OK relay contact is specified for. It is rated about
 * 50 mA. Rather than limit its current, which Power Grid can only do a tick late,
 * it is protected the way the PLC's outputs are: above {@link #DC_OK_TRIP_AMPS}
 * it latches open and stays open until the supply next starts. A shorted DC OK
 * line therefore costs one tick of {@code V / DC_OK_OHMS} and then nothing.
 *
 * <h2>Input draw</h2>
 *
 * What the supply takes from the input is what it delivers over its efficiency,
 * plus its no-load consumption: P_in = P_out / η + {@link #NO_LOAD_WATTS}, η
 * running from {@link #LIGHT_EFFICIENCY} at a tenth of the rating up to
 * {@link #FULL_EFFICIENCY} at full load (see {@link #efficiency}). It is drawn
 * through a resistor across the input of V_in² / P_in, re-valued every tick from
 * the last solve's figures, so the draw is a tick behind the load. When the
 * supply is not running and has no usable input, the resistor is
 * {@link #OPEN_OHMS}: a megohm, which takes a twentieth of a watt at 230 V.
 */
public final class PsuRegulator {

    /** What the supply is doing, for the goggles. */
    public enum State {
        /** No input at all. */
        OFF,
        /** Input present but under the start threshold, or fallen below drop-out. */
        INPUT_LOW,
        /** Over-voltage shutdown. */
        INPUT_HIGH,
        /** Regulating its rated voltage. */
        RUNNING,
        /** Holding the current limit at a reduced voltage. */
        OVERLOAD,
        /** Off for a while after finding a short. */
        HICCUP
    }

    // ------------------------------------------------------------ input ---

    /** Fractions of the nominal input; see the class comment. */
    public static final double START_FRACTION = 0.85;
    public static final double DROPOUT_FRACTION = 0.75;
    public static final double OVERVOLTAGE_FRACTION = 1.20;
    public static final double OVERVOLTAGE_RESTART_FRACTION = 1.15;

    /** Below this fraction of nominal the input counts as absent: Off, not Input low. */
    public static final double PRESENT_FRACTION = 0.10;

    // ----------------------------------------------------------- output ---

    /**
     * The least resistance in series with the output, in ohms: what the supply
     * looks like while it regulates its voltage.
     */
    public static final double OUTPUT_OHMS = 0.05;

    /** Where the constant-current limit sits, as a multiple of the rated current. */
    public static final double LIMIT_MULTIPLE = 1.1;

    /**
     * How close to the limit the last solve's current has to be for the supply to
     * count as limiting when it judges a short. Not 1, because the source's own
     * fraction of a milliohm and the wiring keep a soft start into a short a hair
     * under the limit, and that is the tick that should find it.
     */
    public static final double AT_LIMIT_FRACTION = 0.9;

    /** At the limit and under this fraction of the rated voltage is a short. */
    public static final double SHORT_FRACTION = 0.10;

    /** How long a hiccup keeps the output off, in ticks: a second. */
    public static final int HICCUP_TICKS = 20;

    /**
     * The least the series resistance may fall to in one tick, as a fraction of
     * what it was; see "Quick to limit, slow to let go" in the class comment.
     */
    public static final double RECOVERY_FACTOR = 0.5;

    /**
     * Current into V+ beyond this fraction of the limit is the supply being made
     * to sink, which its output diode does not allow.
     */
    public static final double REVERSE_FRACTION = 0.01;

    /**
     * How far the current has to move between two solves, as a fraction of the
     * limit, before the pair is trusted for the load's slope rather than noise.
     */
    public static final double SLOPE_FRACTION = 0.05;

    /**
     * How close to the last fitted load line, as a fraction of the rated output, a
     * solve has to sit for that line still to be taken as the load.
     */
    public static final double ON_LINE_FRACTION = 0.002;

    /**
     * How close in slope, as a fraction, two fitted lines have to be to count as
     * the same load; see "Quick to limit, slow to let go" in the class comment.
     */
    public static final double STEADY_FRACTION = 0.01;

    /** Below this current the load is taken to be open and the output unloaded. */
    private static final double MIN_AMPS = 1e-6;

    // ------------------------------------------------------------ DC OK ---

    /** The DC OK window, as fractions of the rated output: 18–27 V at 24 V. */
    public static final double DC_OK_LOW_FRACTION = 0.75;
    public static final double DC_OK_HIGH_FRACTION = 1.125;

    /** The DC OK switch's on-resistance, in ohms: a quarter volt at its rated 50 mA. */
    public static final double DC_OK_OHMS = 5;

    /** Above this the DC OK switch latches open until the supply next starts. */
    public static final double DC_OK_TRIP_AMPS = 0.1;

    // ------------------------------------------------------- input draw ---

    public static final double FULL_EFFICIENCY = 0.90;
    public static final double LIGHT_EFFICIENCY = 0.80;

    /** The load fraction {@link #LIGHT_EFFICIENCY} is quoted at. */
    public static final double LIGHT_LOAD = 0.10;

    /** What a running supply draws with nothing on its output, in watts. */
    public static final double NO_LOAD_WATTS = 0.3;

    /** The input load of a supply that is drawing nothing: effectively open. */
    public static final double OPEN_OHMS = 1e6;

    /** The least the input load is ever set to; a guard, never reached in range. */
    private static final double MIN_INPUT_OHMS = 1;

    // ----------------------------------------------------------- rating ---

    private final double inputVolts;
    private final double outputVolts;
    private final double watts;
    private final double limitAmps;

    // ------------------------------------------------------------ state ---

    private boolean underVoltage = true;
    private boolean overVoltage;
    /** Whether the converter is running, which it can be with its output blocked. */
    private boolean running;
    /** Whether the output diode is reverse biased: running, but the output open. */
    private boolean blocked;
    private boolean dcOk;
    private boolean dcOkLatched;
    private int hiccupLeft;
    private double setpoint;
    private double outputOhms = OUTPUT_OHMS;
    private double inputOhms = OPEN_OHMS;
    private double outputWatts;
    private double inputWatts;
    private State state = State.OFF;

    /** The solve before the last one, for the load's slope. */
    private double lastVolts;
    private double lastAmps;
    /** The load line last fitted, V = loadVolts + I × loadOhms; NaN before there is one. */
    private double loadOhms = Double.NaN;
    private double loadVolts = Double.NaN;
    /** Whether the last fit gave the same line as the one before it. */
    private boolean loadSteady;

    /**
     * @param inputVolts  nominal input, the 230 of a 230 V supply
     * @param outputVolts rated output
     * @param watts       rated output power
     */
    public PsuRegulator(double inputVolts, double outputVolts, double watts) {
        if (!(inputVolts > 0 && outputVolts > 0 && watts > 0))
            throw new IllegalArgumentException("A supply needs a positive rating");
        this.inputVolts = inputVolts;
        this.outputVolts = outputVolts;
        this.watts = watts;
        this.limitAmps = watts / outputVolts * LIMIT_MULTIPLE;
    }

    /**
     * One tick: takes what the last solve measured and decides what the next one
     * gets.
     *
     * @param measuredInput  VCC − GND; only its magnitude counts
     * @param measuredOutput V+ − V−
     * @param outputAmps     the current out of V+, positive when sourcing
     * @param dcOkAmps       the current out of the DC OK pin
     */
    public void tick(double measuredInput, double measuredOutput, double outputAmps, double dcOkAmps) {
        double in = Math.abs(measuredInput);
        if (!Double.isFinite(in))
            in = 0;
        if (!Double.isFinite(measuredOutput))
            measuredOutput = 0;
        if (!Double.isFinite(outputAmps))
            outputAmps = 0;

        // The DC OK switch is judged on the solve it was closed for, before anything
        // below decides whether it stays closed
        if (dcOk && Math.abs(dcOkAmps) > DC_OK_TRIP_AMPS)
            dcOkLatched = true;
        double delivered = outputOn() ? Math.max(0, measuredOutput * outputAmps) : 0;

        updateComparators(in);
        if (underVoltage || overVoltage) {
            stop();
            hiccupLeft = 0;
            state = overVoltage ? State.INPUT_HIGH
                    : in < PRESENT_FRACTION * inputVolts ? State.OFF : State.INPUT_LOW;
            outputWatts = 0;
            drawFor(in, 0, false);
            return;
        }

        if (hiccupLeft > 0) {
            hiccupLeft--;
            stop();
            state = State.HICCUP;
            outputWatts = 0;
            drawFor(in, 0, true);
            return;
        }

        if (!running) {
            // A cold start, which also forgives a latched DC OK
            dcOkLatched = false;
            start(measuredOutput);
            outputWatts = 0;
            drawFor(in, 0, true);
            return;
        }

        if (blocked) {
            // The diode conducts again once the rail falls below this supply
            start(measuredOutput);
        } else if (outputAmps < -REVERSE_FRACTION * limitAmps) {
            // Something else holds the rail above this supply
            blocked = true;
            state = State.RUNNING;
        } else if (outputAmps >= AT_LIMIT_FRACTION * limitAmps && measuredOutput < SHORT_FRACTION * outputVolts) {
            // A short: at the limit with next to nothing across it
            stop();
            hiccupLeft = HICCUP_TICKS;
            state = State.HICCUP;
            outputWatts = 0;
            drawFor(in, 0, true);
            return;
        } else {
            regulate(measuredOutput, outputAmps);
        }

        dcOk = !dcOkLatched && measuredOutput >= DC_OK_LOW_FRACTION * outputVolts
                && measuredOutput <= DC_OK_HIGH_FRACTION * outputVolts;
        outputWatts = delivered;
        drawFor(in, delivered, true);
    }

    private void updateComparators(double in) {
        if (underVoltage) {
            if (in >= START_FRACTION * inputVolts)
                underVoltage = false;
        } else if (in < DROPOUT_FRACTION * inputVolts) {
            underVoltage = true;
        }
        if (overVoltage) {
            if (in <= OVERVOLTAGE_RESTART_FRACTION * inputVolts)
                overVoltage = false;
        } else if (in > OVERVOLTAGE_FRACTION * inputVolts) {
            overVoltage = true;
        }
    }

    /**
     * Soft start into the rail as measured: the source at the rated voltage, behind
     * whatever drives the limit into that rail if nothing else moved. Forgets the
     * load, since nothing learned before the output closed says what is on it now.
     * DC OK stays as it was: it is judged on the next solve, and a latch is
     * forgiven by a cold start only, not by the output diode conducting again.
     */
    private void start(double measuredOutput) {
        running = true;
        // A rail something else already holds at or above the rating reverse-biases
        // the diode from the first solve, not one tick of sinking later
        blocked = measuredOutput >= outputVolts;
        double held = Math.min(Math.max(measuredOutput, 0), outputVolts);
        setpoint = outputVolts;
        outputOhms = Math.max(OUTPUT_OHMS, (outputVolts - held) / limitAmps);
        lastVolts = held;
        lastAmps = 0;
        loadOhms = Double.NaN;
        loadVolts = Double.NaN;
        loadSteady = false;
        state = State.RUNNING;
    }

    private void stop() {
        running = false;
        blocked = false;
        dcOk = false;
        setpoint = 0;
        outputOhms = OUTPUT_OHMS;
    }

    /** The rated voltage behind the least series resistance, or the current limit, whichever is lower. */
    private void regulate(double measuredOutput, double outputAmps) {
        fitLoad(measuredOutput, outputAmps);
        double r = loadOhms;
        double v0 = loadVolts;

        // What the load would take from the rated voltage behind the least series
        // resistance; a line at or above the rated voltage takes nothing, and the
        // output diode sees to it on the next solve if it would push back
        double wanted = Double.isInfinite(r) ? 0 : (outputVolts - v0) / (r + OUTPUT_OHMS);
        state = wanted > limitAmps ? State.OVERLOAD : State.RUNNING;

        // The least series resistance, or over the limit whatever drops the rated
        // voltage to where the line carries exactly the limit
        setpoint = outputVolts;
        double ohms = OUTPUT_OHMS;
        if (state == State.OVERLOAD)
            ohms = Math.max(OUTPUT_OHMS, (outputVolts - (v0 + limitAmps * r)) / limitAmps);
        if (ohms < outputOhms) {
            // Letting go: by at most half a tick. And unless the load has shown the
            // same line two solves running, by no more than would put the limit
            // through the new resistance were the rail to stay where it is: a line
            // that moved is a paralleled supply moving in the same tick, and taking
            // it at its word is what swings two supplies through each other. A line
            // that held — a resistor, a battery — is trusted, so a lone supply on a
            // near-rated load is not crept up on at a few percent a tick
            ohms = Math.max(ohms, outputOhms * RECOVERY_FACTOR);
            if (!loadSteady)
                ohms = Math.max(ohms, (setpoint - measuredOutput) / limitAmps);
        }
        if (outputAmps > limitAmps) {
            // Whatever the fitted line says, a solve that carried more than the limit
            // is over it. With a paralleled supply moving in the same tick the line
            // can be wrong, and then this is what keeps the take-over within the
            // limit: no less resistance than puts the limit into the rail as it is
            ohms = Math.max(ohms, (setpoint - measuredOutput) / limitAmps);
            state = State.OVERLOAD;
        }
        outputOhms = ohms;
    }

    /**
     * Fits the load to V = v0 + I × r: through this solve and the last when the
     * current moved enough to give a slope, else the line found before if this
     * solve still sits on it, else a plain resistor, else — no current at all —
     * an open circuit at whatever the rail reads.
     */
    private void fitLoad(double volts, double amps) {
        double r = Double.NaN;
        double v0 = Double.NaN;
        double dI = amps - lastAmps;
        if (Math.abs(dI) > SLOPE_FRACTION * limitAmps) {
            double slope = (volts - lastVolts) / dI;
            if (slope > 0 && Double.isFinite(slope)) {
                r = slope;
                v0 = volts - amps * slope;
            }
        }
        if (Double.isNaN(r) && Double.isFinite(loadOhms)
                && Math.abs(loadVolts + amps * loadOhms - volts) <= ON_LINE_FRACTION * outputVolts) {
            r = loadOhms;
            v0 = loadVolts;
        }
        if (Double.isNaN(r)) {
            if (amps > MIN_AMPS) {
                r = Math.max(0, volts / amps);
                v0 = 0;
            } else {
                r = Double.POSITIVE_INFINITY;
                v0 = Math.max(0, volts);
            }
        }
        loadSteady = sameLine(r, v0, loadOhms, loadVolts);
        lastVolts = volts;
        lastAmps = amps;
        loadOhms = r;
        loadVolts = v0;
    }

    /**
     * Whether two fitted lines are the same load: open both, or within
     * {@link #STEADY_FRACTION} in slope and {@link #ON_LINE_FRACTION} of the rating
     * in offset. NaN, the line before there was one, is never the same.
     */
    private boolean sameLine(double r, double v0, double lastR, double lastV0) {
        if (Double.isNaN(lastR))
            return false;
        if (Double.isInfinite(r) || Double.isInfinite(lastR))
            return r == lastR;
        return Math.abs(r - lastR) <= STEADY_FRACTION * Math.max(Math.max(r, lastR), OUTPUT_OHMS)
                && Math.abs(v0 - lastV0) <= ON_LINE_FRACTION * outputVolts;
    }

    /**
     * Sets the input load for the power the output is delivering.
     *
     * @param alive whether the supply's own electronics are powered, which is
     *              whenever the input is usable, hiccup included
     */
    private void drawFor(double in, double delivered, boolean alive) {
        if (!alive || in <= 0) {
            inputWatts = 0;
            inputOhms = OPEN_OHMS;
            return;
        }
        inputWatts = inputWatts(delivered, watts);
        inputOhms = Math.max(MIN_INPUT_OHMS, Math.min(OPEN_OHMS, in * in / inputWatts));
    }

    /** P_out / η + the no-load draw, for a supply rated {@code rated} watts. */
    public static double inputWatts(double delivered, double rated) {
        double out = Math.max(0, delivered);
        return out / efficiency(out / rated) + NO_LOAD_WATTS;
    }

    /**
     * Efficiency at a fraction of rated load: {@link #LIGHT_EFFICIENCY} at and
     * below {@link #LIGHT_LOAD}, rising in a straight line to
     * {@link #FULL_EFFICIENCY} at full load and holding there into overload. A
     * real curve bows upward and peaks a little before full load; the straight
     * line is the same at both ends and close enough between them for what it
     * decides, which is how much the input side of a cabinet draws.
     */
    public static double efficiency(double loadFraction) {
        if (!(loadFraction > LIGHT_LOAD))
            return LIGHT_EFFICIENCY;
        if (loadFraction >= 1)
            return FULL_EFFICIENCY;
        double along = (loadFraction - LIGHT_LOAD) / (1 - LIGHT_LOAD);
        return LIGHT_EFFICIENCY + (FULL_EFFICIENCY - LIGHT_EFFICIENCY) * along;
    }

    // -------------------------------------------------------- what it is ---

    public double ratedInputVolts() {
        return inputVolts;
    }

    public double ratedOutputVolts() {
        return outputVolts;
    }

    public double ratedWatts() {
        return watts;
    }

    public double ratedAmps() {
        return watts / outputVolts;
    }

    public double limitAmps() {
        return limitAmps;
    }

    // ----------------------------------------------------- what it does ---

    public State state() {
        return state;
    }

    /** Whether the converter is running; its output may still be blocked. */
    public boolean running() {
        return running;
    }

    /**
     * Whether the output switch is closed: running, with the output diode not
     * reverse biased. Open, the output is an open circuit.
     */
    public boolean outputOn() {
        return running && !blocked;
    }

    /** Whether the output diode is holding off a rail something else holds higher. */
    public boolean blocked() {
        return blocked;
    }

    /** The source's voltage behind {@link #outputOhms()}; zero while stopped. */
    public double setpoint() {
        return setpoint;
    }

    /**
     * The resistance in series with the source: {@link #OUTPUT_OHMS} while it
     * regulates the voltage, more while it limits the current or starts.
     */
    public double outputOhms() {
        return outputOhms;
    }

    public boolean dcOk() {
        return dcOk;
    }

    /** Whether DC OK was overloaded and is held open until the next start. */
    public boolean dcOkLatched() {
        return dcOkLatched;
    }

    /** The resistance to put across the input for the next solve. */
    public double inputOhms() {
        return inputOhms;
    }

    /** What the output delivered on the last solve, in watts. */
    public double outputWatts() {
        return outputWatts;
    }

    /** What the input is being asked to supply, in watts. */
    public double inputWatts() {
        return inputWatts;
    }

    /** Ticks of hiccup still to sit out. */
    public int hiccupLeft() {
        return hiccupLeft;
    }
}
