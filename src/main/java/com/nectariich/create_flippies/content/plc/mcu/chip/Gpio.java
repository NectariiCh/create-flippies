package com.nectariich.create_flippies.content.plc.mcu.chip;

import java.util.Arrays;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * GPIOA/B/C and the EXTI controller, at register level, indexed by Arduino pin
 * number ({@link ArduinoPins}).
 *
 * <h2>What a pin is</h2>
 *
 * A mode (the CRL/CRH configuration plus the pull chosen through ODR), an output data
 * bit, and — for PA0..PA7 only — the level the board's 24 V input front end presents.
 * Everything a sketch can observe is derived from those: {@link #idr} is what the
 * input data register reads, {@link #driveHigh} is what the pad drives into the
 * board's output stage.
 *
 * <ul>
 * <li>Push-pull output: the pad is ODR, and IDR reads it back through the Schmitt
 * trigger, so it reads ODR.
 * <li>Open-drain output: ODR low sinks; ODR high releases. Released, the pad is only
 * high if something outside pulls it up. Only the input front ends do, so an
 * open-drain DI pin reads the board level, any other released pin floats and reads
 * low — and the DO driver, whose input has its own pull-down (ISO8200-class high-side
 * switches all have one), sees low. Open-drain therefore never turns a DO on, which is
 * what the real board would do.
 * <li>Alternate function: the pad follows a timer's output compare, see
 * {@link PwmTimer}. A timer that is not running drives nothing, which reads low.
 * <li>Analog: the Schmitt trigger is switched off, so IDR reads 0 whatever the pad
 * voltage; the output buffer is off as well.
 * <li>Input (floating, pull-up/pull-down): the front end wins where there is one;
 * elsewhere the pull decides, and floating reads low (the model has no noise).
 * </ul>
 *
 * <h2>The F1's pull is ODR</h2>
 *
 * Unlike every later STM32, the F1 has no PUPDR: CNF = 10 selects "input with
 * pull-up/pull-down", and the pin's ODR bit chooses which. STM32duino's
 * {@code pin_PullConfig} calls {@code LL_GPIO_SetPinPull}, which on the F1 writes ODR (1 up,
 * 0 down), and nothing else touches ODR when a pin becomes a floating input or an
 * output. So {@link Mode#INPUT_PULL} has no direction of its own: {@link #idr} reads
 * the pull from {@code odr[]}. Three consequences a sketch can see, all as on the
 * chip: {@code pinMode(DO0, INPUT_PULLUP); pinMode(DO0, OUTPUT);} drives DO0 high at
 * once; {@code digitalWrite} on a pulled input flips the pull; and a pin set up as
 * INPUT_PULLDOWN after an output HIGH starts its output life LOW again.
 *
 * The SWJ pins come out of reset with pulls the debug port applies on its own, not
 * through ODR (whose reset value is 0); they have modes of their own,
 * {@link Mode#DEBUG_PULLUP} and {@link Mode#DEBUG_PULLDOWN}, so that taking one over
 * as an output starts it LOW, as on the chip.
 *
 * <h2>High-time accounting</h2>
 *
 * Every change to a pin happens at a cycle stamp. Before it is applied the pin's
 * high time since the last stamp is added up, exactly, including a timer waveform's
 * whole and partial periods. That is what turns a window into {@link PinDrive#highFraction()}:
 * hardware PWM, tone() and bit-banged pulses all come out as their true average, and
 * a 1 Hz PWM is high or low for the whole of a 50 ms window rather than smeared to
 * its duty.
 *
 * <h2>EXTI</h2>
 *
 * One pin per line, the line being the pin's bit number, as AFIO_EXTICR allows; a
 * second attach on the same bit of another port takes the line over, as STM32duino's
 * does. The line watches the pin's IDR, so an edge comes from the board (applied at
 * the start of a window), from the sketch writing a pin it also watches, or from a
 * waveform on it. Interrupts go to the CPU as irq = line number.
 */
public final class Gpio {

    /**
     * Pin configuration, as CRL/CRH express it. {@link #INPUT_PULL} is CNF = 10, whose
     * direction is the pin's ODR bit; the two DEBUG modes are an SWJ pin still in its
     * debug function after reset, pulled by the debug port itself.
     */
    public enum Mode {
        INPUT, INPUT_PULL, ANALOG, OUTPUT_PP, OUTPUT_OD, ALTERNATE, DEBUG_PULLUP, DEBUG_PULLDOWN;

        /** Whether the pad's output buffer is enabled in this mode. */
        public boolean isOutput() {
            return this == OUTPUT_PP || this == OUTPUT_OD || this == ALTERNATE;
        }
    }

    public static final int EDGE_RISING = 1;
    public static final int EDGE_FALLING = 2;
    public static final int EDGE_BOTH = EDGE_RISING | EDGE_FALLING;

    private static final int N = ArduinoPins.COUNT;

    private final Stm32f103 chip;

    private final Mode[] mode = new Mode[N];
    private final boolean[] odr = new boolean[N];
    /** The board's input front-end level; survives resets, because the board does. */
    private final boolean[] frontEnd = new boolean[N];
    /** Whether the sketch has looked at this pin's level since reset (see {@link Stm32f103#nextEventCycle()}). */
    private final boolean[] observed = new boolean[N];

    /** Timer waveform on an alternate-function pin: period and high time in cycles, phase origin. Period 0 = undriven. */
    private final long[] wavePeriod = new long[N];
    private final long[] waveHigh = new long[N];
    private final long[] waveOrigin = new long[N];

    private final long[] segmentStart = new long[N];
    private final long[] highCycles = new long[N];

    private final int[] linePin = new int[16];
    private final int[] lineEdges = new int[16];
    private final long[] lineHandler = new long[16];
    private final boolean[] lineLevel = new boolean[16];

    /** Bumped on every change to any pin's level or mode; lets a parked blocker notice an ISR's write. */
    private long changeSerial;

    Gpio(Stm32f103 chip) {
        this.chip = chip;
        reset(0);
    }

    /**
     * The state after NRST: every pin a floating input except the five SWJ pins, which
     * come out of reset in their debug function with the pulls the debug port needs
     * (PA13 SWDIO and PA15 JTDI up, PA14 SWCLK down, PB4 NJTRST up; PB3 JTDO is only
     * driven while a debugger shifts data, so it floats). The front-end levels stay.
     */
    void reset(long now) {
        Arrays.fill(mode, Mode.INPUT);
        setResetPull(0x0D, Mode.DEBUG_PULLUP);
        setResetPull(0x0E, Mode.DEBUG_PULLDOWN);
        setResetPull(0x0F, Mode.DEBUG_PULLUP);
        setResetPull(0x14, Mode.DEBUG_PULLUP);
        Arrays.fill(odr, false);
        Arrays.fill(observed, false);
        Arrays.fill(wavePeriod, 0);
        Arrays.fill(waveHigh, 0);
        Arrays.fill(waveOrigin, 0);
        Arrays.fill(segmentStart, now);
        Arrays.fill(highCycles, 0);
        Arrays.fill(linePin, -1);
        Arrays.fill(lineEdges, 0);
        Arrays.fill(lineHandler, 0);
        Arrays.fill(lineLevel, false);
        changeSerial++;
    }

    /**
     * A system reset in the middle of a window: every pin's high time up to
     * {@code now} is kept for the window's drive figures, then everything resets.
     */
    void resetKeepingWindow(long now) {
        for (int pin = 0; pin < N; pin++)
            accumulate(pin, now);
        long[] kept = highCycles.clone();
        reset(now);
        System.arraycopy(kept, 0, highCycles, 0, N);
    }

    private void setResetPull(int portPin, Mode pull) {
        int pin = ArduinoPins.fromPortPin(portPin >> 4, portPin & 0xF);
        if (pin >= 0)
            mode[pin] = pull;
    }

    public static boolean valid(long pin) {
        return pin >= 0 && pin < N;
    }

    /** Whether the board has a 24 V input front end on this pin (DI0..DI7). */
    public static boolean hasFrontEnd(int pin) {
        return pin >= ArduinoPins.FIRST_DI && pin < ArduinoPins.FIRST_DI + ArduinoPins.IO_PER_BANK;
    }

    /** 0 = GPIOA, 1 = GPIOB, 2 = GPIOC. */
    public static int port(int pin) {
        return ArduinoPins.PORT_PIN[pin] >> 4;
    }

    /** The bit within the port, which is also the pin's EXTI line. */
    public static int bit(int pin) {
        return ArduinoPins.PORT_PIN[pin] & 0xF;
    }

    public Mode mode(int pin) {
        return mode[pin];
    }

    public boolean odr(int pin) {
        return odr[pin];
    }

    public long changeSerial() {
        return changeSerial;
    }

    // ---- changes -----------------------------------------------------------------

    /**
     * Reconfigures a pin now, leaving ODR as it is (a floating input, an output, analog,
     * alternate function — or {@link Mode#INPUT_PULL} keeping the pull it has). Returns
     * whether anything changed.
     */
    public boolean setMode(int pin, Mode newMode) {
        return configure(pin, newMode, odr[pin]);
    }

    /**
     * Makes the pin a pulled input (CNF = 10) pulling up or down: on the F1 that is the
     * mode plus the ODR bit, set together as HAL_GPIO_Init does (BSRR/BRR, then CRL/CRH).
     * Returns whether anything changed.
     */
    public boolean setInputPull(int pin, boolean up) {
        return configure(pin, Mode.INPUT_PULL, up);
    }

    /**
     * The mode and ODR changed as one step: the high time up to now is closed off, and
     * an EXTI edge fires only if IDR differs once both have changed, not on the way.
     */
    private boolean configure(int pin, Mode newMode, boolean newOdr) {
        if (mode[pin] == newMode && odr[pin] == newOdr)
            return false;
        long t = chip.now();
        accumulate(pin, t);
        boolean before = idr(pin, t);
        mode[pin] = newMode;
        odr[pin] = newOdr;
        if (newMode != Mode.ALTERNATE) {
            wavePeriod[pin] = 0;
            waveHigh[pin] = 0;
        }
        changed(pin, t, before);
        return true;
    }

    /** Writes the output data bit now (BSRR). Returns whether it changed. */
    public boolean writeOdr(int pin, boolean level) {
        return writeOdrAt(pin, level, chip.now());
    }

    /** Writes the output data bit at an earlier stamp; used by timer ISRs replayed in order. */
    boolean writeOdrAt(int pin, boolean level, long t) {
        if (odr[pin] == level)
            return false;
        accumulate(pin, t);
        boolean before = idr(pin, t);
        odr[pin] = level;
        changed(pin, t, before);
        return true;
    }

    /** The board's input front end changing its output; applied now, at the start of a window. */
    void setFrontEnd(int pin, boolean level) {
        if (frontEnd[pin] == level)
            return;
        long t = chip.now();
        accumulate(pin, t);
        boolean before = idr(pin, t);
        frontEnd[pin] = level;
        changed(pin, t, before);
    }

    /** A timer output compare taking over (or letting go of, with period 0) an alternate-function pin. */
    void setWave(int pin, long period, long high, long origin) {
        if (wavePeriod[pin] == period && waveHigh[pin] == high && waveOrigin[pin] == origin)
            return;
        long t = chip.now();
        accumulate(pin, t);
        boolean before = idr(pin, t);
        wavePeriod[pin] = period;
        waveHigh[pin] = high;
        waveOrigin[pin] = origin;
        changed(pin, t, before);
    }

    private void changed(int pin, long t, boolean idrBefore) {
        changeSerial++;
        boolean after = idr(pin, t);
        if (after != idrBefore)
            edge(pin, after);
    }

    // ---- reading -----------------------------------------------------------------

    /** IDR as the sketch reads it now; marks the pin as observed. */
    public boolean read(int pin) {
        observed[pin] = true;
        return idr(pin, chip.now());
    }

    public void markObserved(int pin) {
        observed[pin] = true;
    }

    boolean observed(int pin) {
        return observed[pin];
    }

    /** The input data register bit at cycle {@code t}. */
    public boolean idr(int pin, long t) {
        switch (mode[pin]) {
            case OUTPUT_PP:
                return odr[pin];
            case OUTPUT_OD:
                return odr[pin] && external(pin, false);
            case ALTERNATE:
                return waveLevel(pin, t);
            case ANALOG:
                return false;
            case INPUT_PULL:
                return external(pin, odr[pin]);
            case DEBUG_PULLUP:
                return external(pin, true);
            case DEBUG_PULLDOWN:
            case INPUT:
            default:
                return external(pin, false);
        }
    }

    private boolean external(int pin, boolean pull) {
        return hasFrontEnd(pin) ? frontEnd[pin] : pull;
    }

    /** The pad voltage the ADC samples: the front end's output on a DI pin, otherwise nothing drives it. */
    public double padVolts(int pin) {
        return hasFrontEnd(pin) && frontEnd[pin] ? 3.3 : 0.0;
    }

    /** Whether the pad drives the board's output stage high at cycle {@code t}. */
    public boolean driveHigh(int pin, long t) {
        if (mode[pin] == Mode.OUTPUT_PP)
            return odr[pin];
        if (mode[pin] == Mode.ALTERNATE)
            return waveLevel(pin, t);
        return false;
    }

    private boolean waveLevel(int pin, long t) {
        long period = wavePeriod[pin];
        if (period <= 0)
            return false;
        return Math.floorMod(t - waveOrigin[pin], period) < waveHigh[pin];
    }

    /** Whether the pin's level changes on its own (a running waveform that is neither 0 % nor 100 %). */
    boolean hasToggleWave(int pin) {
        return mode[pin] == Mode.ALTERNATE && wavePeriod[pin] > 0
                && waveHigh[pin] > 0 && waveHigh[pin] < wavePeriod[pin];
    }

    /** The first cycle after {@code t} at which the pin's waveform switches, or MAX. */
    long nextWaveEdge(int pin, long t) {
        if (!hasToggleWave(pin))
            return Long.MAX_VALUE;
        long period = wavePeriod[pin];
        long phase = Math.floorMod(t - waveOrigin[pin], period);
        return phase < waveHigh[pin] ? t + (waveHigh[pin] - phase) : t + (period - phase);
    }

    /**
     * The cycle after {@code t} at which the pin's IDR may next change without the
     * sketch touching it, or MAX. Tone toggles not yet replayed are projected forward.
     */
    public long nextLevelChange(int pin, long t) {
        long next = nextWaveEdge(pin, t);
        ToneTimer tone = chip.toneTimer();
        if (tone.pin() == pin && mode[pin] == Mode.OUTPUT_PP)
            next = Math.min(next, tone.nextEventAfter(t));
        return next;
    }

    /**
     * Whether a tone toggle on this pin is due at or before {@code t} but has not been
     * replayed yet: a poller should catch up and look now.
     */
    public boolean levelChangeDue(int pin, long t) {
        ToneTimer tone = chip.toneTimer();
        return tone.pin() == pin && mode[pin] == Mode.OUTPUT_PP && tone.nextEvent() <= t;
    }

    // ---- high-time accounting ----------------------------------------------------

    private void accumulate(int pin, long t) {
        long from = segmentStart[pin];
        if (t <= from)
            return;
        highCycles[pin] += highBetween(pin, from, t);
        segmentStart[pin] = t;
    }

    private long highBetween(int pin, long from, long to) {
        if (mode[pin] == Mode.OUTPUT_PP)
            return odr[pin] ? to - from : 0;
        if (mode[pin] == Mode.ALTERNATE && wavePeriod[pin] > 0)
            return waveHighUpTo(pin, to) - waveHighUpTo(pin, from);
        return 0;
    }

    /** High cycles of the waveform from its origin up to {@code t}; differences of it are exact. */
    private long waveHighUpTo(int pin, long t) {
        long period = wavePeriod[pin];
        long high = Math.min(waveHigh[pin], period);
        long rel = t - waveOrigin[pin];
        return Math.floorDiv(rel, period) * high + Math.min(Math.floorMod(rel, period), high);
    }

    void beginWindow(long start) {
        for (int pin = 0; pin < N; pin++) {
            segmentStart[pin] = start;
            highCycles[pin] = 0;
        }
    }

    /** Closes the window at {@code end} and reports what each pin did over it. */
    PinDrive drive(int pin, long start, long end, double hz) {
        accumulate(pin, end);
        boolean out = mode[pin].isOutput();
        boolean level = driveHigh(pin, end);
        long length = end - start;
        double fraction = length > 0 ? Math.min(1.0, (double) highCycles[pin] / length) : (level ? 1 : 0);
        return new PinDrive(out, level, fraction, hz);
    }

    double waveHz(int pin) {
        return hasToggleWave(pin) ? (double) Stm32f103cb.CPU_HZ / wavePeriod[pin] : 0;
    }

    // ---- EXTI --------------------------------------------------------------------

    /** Routes the pin's bit to its EXTI line (taking it from another port if need be) and unmasks it. */
    public void attach(int pin, int edges, long handler) {
        int line = bit(pin);
        linePin[line] = pin;
        lineEdges[line] = edges;
        lineHandler[line] = handler;
        lineLevel[line] = idr(pin, chip.now());
    }

    /** Masks the pin's line, if the line is still routed to this pin. */
    public boolean detach(int pin) {
        int line = bit(pin);
        if (linePin[line] != pin)
            return false;
        linePin[line] = -1;
        lineHandler[line] = 0;
        lineEdges[line] = 0;
        return true;
    }

    public boolean attached(int pin) {
        return linePin[bit(pin)] == pin;
    }

    private void edge(int pin, boolean level) {
        int line = bit(pin);
        if (linePin[line] != pin)
            return;
        lineLevel[line] = level;
        int wanted = level ? EDGE_RISING : EDGE_FALLING;
        if ((lineEdges[line] & wanted) != 0)
            chip.pendExti(line, lineHandler[line]);
    }

    /**
     * Raises the interrupts a free-running waveform caused on a watched pin between
     * {@code from} (exclusive) and {@code to} (inclusive). Several edges pend once,
     * as the EXTI pending bit and the NVIC both coalesce them.
     */
    void waveEdges(long from, long to) {
        for (int line = 0; line < 16; line++) {
            int pin = linePin[line];
            if (pin < 0 || !hasToggleWave(pin) || to <= from)
                continue;
            long period = wavePeriod[pin];
            long origin = waveOrigin[pin];
            boolean rising = firstAfter(origin, period, from) <= to;
            boolean falling = firstAfter(origin + waveHigh[pin], period, from) <= to;
            lineLevel[line] = idr(pin, to);
            if ((rising && (lineEdges[line] & EDGE_RISING) != 0)
                    || (falling && (lineEdges[line] & EDGE_FALLING) != 0))
                chip.pendExti(line, lineHandler[line]);
        }
    }

    /** The first edge of a series {@code origin + k * period} strictly after {@code t}. */
    private static long firstAfter(long origin, long period, long t) {
        return origin + (Math.floorDiv(t - origin, period) + 1) * period;
    }

    /** The next waveform edge on any pin that has an EXTI line watching it, or MAX. */
    long nextWatchedWaveEdge(long t) {
        long next = Long.MAX_VALUE;
        for (int line = 0; line < 16; line++) {
            int pin = linePin[line];
            if (pin >= 0)
                next = Math.min(next, nextLevelChange(pin, t));
        }
        return next;
    }
}
