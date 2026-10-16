package com.nectariich.create_flippies.content.plc.mcu.chip;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.CycleLedger;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuEnvironment;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.PlcCore;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * The STM32F103CBT6 on the small PLC: power, reset and programming, the peripherals a
 * base Arduino sketch can reach (GPIO and EXTI, SysTick, TIM1/TIM4 PWM, TIM3 for
 * tone(), ADC1), the run-window loop and the power model. The CPU itself is the
 * {@link CpuCore} the flashed image makes; this class owns its clock.
 *
 * <h2>Time</h2>
 *
 * One {@link CycleLedger} is the chip's only clock; cycle 0 is the last reset.
 * Peripherals are lazy: SysTick ticks and tone() toggles are replayed in order, at
 * their own cycle stamps, whenever the chip {@link #sync() catches up} — before any
 * builtin reads a pin or the clock, and after every stretch the CPU runs. Their ISR
 * cycles are charged to {@link CycleClass#INTERRUPT} at that point, so the window
 * loop never lets the CPU run past the next SysTick or tone update: each ISR is then
 * charged at its own cycle (give or take the instruction that crossed it), taking its
 * time out of whatever the CPU was doing. That matters most when the CPU was only
 * waiting — a delay(), a parked pulseIn, a fast-forwarded polling loop — because on
 * the chip an ISR preempts such a wait without lengthening it; charging a stretch's
 * ISRs in one lump at its end would instead add them on top, and a fast tone() would
 * make delay(30) last 32 ms and push every following event late. Because interrupt
 * masking only changes through the core's {@code __enable_irq}/{@code __disable_irq}
 * builtins, which catch up first, the replay knows exactly which ticks were taken and
 * which were left pending: with interrupts off, millis() stops and one tick is taken
 * when they come back on, as on the chip.
 *
 * <h2>Priorities</h2>
 *
 * STM32duino gives SysTick priority 0 (TICK_INT_PRIORITY), EXTI 6 (EXTI_IRQ_PRIO)
 * and TIM3 14 (TIM_IRQ_PRIO). SysTick therefore preempts an EXTI handler and keeps
 * millis() going through it, but tone()'s TIM3 update does not: while the CPU is in an
 * EXTI handler the update stays pending and the tone pin freezes, and one toggle is
 * taken when the handler returns, as after {@code __enable_irq}. Whether the CPU is in
 * a handler is asked of the CPU, see {@link #handlerProbe}.
 *
 * <h2>The window</h2>
 *
 * {@link #runFor} runs one game tick. Windows are laid end to end on the cycle axis;
 * an instruction or ISR that ends past one window's end simply shortens the next, and
 * a builtin that runs for many windows' worth of cycles (it is charged in one go)
 * keeps the CPU busy through as many windows as it takes, with outputs holding what
 * they did over it, so emulated time never runs ahead of the world's. (The core's
 * long byte loops — memset, str*, printf — do not do that: they are charged in pieces
 * that end at {@link #segmentEnd()}, so interrupts and the window's end preempt them
 * as they preempt newlib's code on the chip.) The
 * CPU is stopped where a hardware event must reach it at its own time: a SysTick or
 * tone update (above), a waveform edge on a pin an EXTI line watches. Everything else
 * it learns through {@link #nextEventCycle()}, which bounds its idle fast-forward. When the
 * host budget runs out the rest of the window is dropped: emulated time dilates and
 * {@link #speed()} says so. The budget is checked here as well as inside the CPU,
 * because a window cut into many short stretches (a fast tone(), an EXTI line on a
 * PWM pin) can run each stretch in fewer instructions than the CPU's own check
 * waits for.
 *
 * <h2>States</h2>
 *
 * Unpowered, programming, held in reset and blank all leave the CPU absent and every
 * pin a floating input, so every output is off. Leaving any of them boots from flash:
 * fresh SRAM, peripherals at reset values, cycle 0, setup().
 */
public final class Stm32f103 implements PlcCore, McuEnvironment {

    public static final long CYCLES_PER_MS = Stm32f103cb.CPU_HZ / 1000;

    /**
     * One SysTick interrupt: 12 cycles in, HAL_IncTick (load, add, store uwTick and
     * the weak osSystickHandler call, ~26), 12 out.
     */
    public static final int SYSTICK_ISR_CYCLES = 50;

    /**
     * The cycles from SYSRESETREQ to the first instruction of the new boot: the
     * internal reset pulse (at least 20 us on the F1), then SystemClock_Config
     * restarting HSE and the PLL. We round the end up to a whole millisecond of the
     * ledger, which keeps the new boot's SysTick on the same millisecond grid as the
     * old one: the CPU's idle fast-forward relies on time reads changing only at
     * multiples of their resolution.
     */
    public static final long RESET_PULSE_CYCLES = 20 * (Stm32f103cb.CPU_HZ / 1_000_000L);

    /**
     * A stretch of the window loop at least this long is expensive enough on the host
     * that reading the clock after it costs nothing; shorter ones read it every
     * {@link #SHORT_SEGMENTS_PER_CLOCK_READ}th time.
     */
    private static final long LONG_SEGMENT_CYCLES = 2048;
    private static final int SHORT_SEGMENTS_PER_CLOCK_READ = 16;

    /** A backstop on {@link #sync()}'s catch-up passes; see there. */
    private static final int MAX_SYNC_PASSES = 1 << 16;

    /** Programming throughput over SWD: page erase plus half-word writes, about 25 KB/s. */
    public static final double FLASH_BYTES_PER_SECOND = 25 * 1024;

    public static final int GATE_GPIOA = 0;
    public static final int GATE_GPIOB = 1;
    public static final int GATE_GPIOC = 2;
    public static final int GATE_AFIO = 3;
    public static final int GATE_TIM1 = 4;
    public static final int GATE_TIM3 = 5;
    public static final int GATE_TIM4 = 6;
    private static final int GATES = 7;

    private final CycleLedger ledger = new CycleLedger();
    private final Gpio gpio = new Gpio(this);
    private final PwmTimer tim1 = new PwmTimer(this, GATE_TIM1, 8, 9, 16);
    private final PwmTimer tim4 = new PwmTimer(this, GATE_TIM4, 14, 15);
    private final ToneTimer tone = new ToneTimer(this);

    private boolean powered;
    private boolean resetHeld;
    private CompiledProgram program;
    private long programmingLeftNanos;
    private long programmingTotalNanos;

    private CpuCore cpu;
    private HardFault fault;
    private int faultLine;
    /** The cycle the running image last started at: 0 after power-on, later after NVIC_SystemReset. */
    private long bootCycle;
    /** NVIC_SystemReset has asked for a system reset that the window loop has yet to carry out. */
    private boolean resetRequested;
    /** Bumped whenever an interrupt is taken or becomes pending; what wakes a __WFI. */
    private long interruptSerial;
    /** Whether the CPU is running an interrupt handler (an EXTI line's); see {@link #handlerProbe}. */
    private BooleanSupplier inHandler = () -> false;
    /** Whether the last catch-up found the CPU in a handler, holding TIM3 back. */
    private boolean toneHeldByHandler;
    /** The event register WFE tests: set by SEV ... */
    private boolean eventSent;
    /** ... and by every exception since the last WFE, which {@link #interruptSerial} counts. */
    private long eventSerial;
    /** The cycle the CPU stretch in progress is to stop at; see {@link #segmentEnd()}. */
    private long segmentEnd;

    // SysTick
    private int uwTick;
    private long nextTick;
    private boolean tickPending;
    /** The cycle up to which waveform edges on watched pins have been turned into EXTI interrupts. */
    private long lastWaveCheck;

    // AFIO_MAPR SWJ_CFG
    private boolean jtagReleased;
    private boolean swdReleased;

    /** The Arduino core's own globals; they live in SRAM on the chip, so a reset drops them. */
    private Object runtimeState;

    // clock gates
    private final boolean[] gateOn = new boolean[GATES];
    private final long[] gateSince = new long[GATES];
    private final long[] gateOnCycles = new long[GATES];
    private long adcCycles;

    // window
    private long cycleCarry;
    private long scheduledEnd;
    private final PinDrive[] drives = new PinDrive[ArduinoPins.COUNT];
    private long[] lastWindowCycles = new long[CycleClass.values().length];
    private long lastWindowLength;
    private double speed = 1;
    private double cpuLoad;
    private double powerWatts;

    /** A powered-down chip with blank flash. */
    public Stm32f103() {
        Arrays.fill(drives, PinDrive.OFF);
    }

    // ---- PlcCore: power, reset, programming --------------------------------------

    @Override
    public void flash(CompiledProgram program) {
        this.program = program;
        shutdown();
        if (program == null) {
            programmingLeftNanos = 0;
            programmingTotalNanos = 0;
        } else {
            programmingTotalNanos = Math.max(1, (long) (program.flashBytes() / FLASH_BYTES_PER_SECOND * 1e9));
            programmingLeftNanos = programmingTotalNanos;
        }
        reconcile();
    }

    @Override
    public CompiledProgram program() {
        return program;
    }

    @Override
    public void setPowered(boolean powered) {
        if (this.powered == powered)
            return;
        this.powered = powered;
        reconcile();
    }

    @Override
    public boolean powered() {
        return powered;
    }

    @Override
    public void setResetHeld(boolean held) {
        if (resetHeld == held)
            return;
        resetHeld = held;
        reconcile();
    }

    @Override
    public void reset() {
        shutdown();
        reconcile();
    }

    /** How far the current programming has got, 0..1; 1 when not programming. */
    public double programmingProgress() {
        if (programmingLeftNanos <= 0 || programmingTotalNanos <= 0)
            return 1;
        return 1 - (double) programmingLeftNanos / programmingTotalNanos;
    }

    private boolean canRun() {
        return powered && programmingLeftNanos <= 0 && !resetHeld && program != null;
    }

    private void reconcile() {
        if (canRun()) {
            if (cpu == null)
                boot();
        } else if (cpu != null) {
            shutdown();
        }
    }

    private void shutdown() {
        cpu = null;
        fault = null;
        faultLine = 0;
        runtimeState = null;
        gpio.reset(ledger.now());
        tim1.reset();
        tim4.reset();
        tone.reset();
        Arrays.fill(gateOn, false);
        Arrays.fill(drives, PinDrive.OFF);
    }

    private void boot() {
        ledger.clear();
        gpio.reset(0);
        tim1.reset();
        tim4.reset();
        tone.reset();
        resetRequested = false;
        Arrays.fill(gateOn, false);
        Arrays.fill(gateOnCycles, 0);
        adcCycles = 0;
        scheduledEnd = 0;
        startCpu();
        scheduledEnd = ledger.now();
    }

    /**
     * Everything a reset leaves in its reset state, then the image started: core
     * globals, SysTick, the SWJ remap, peripheral clocks, a fresh CPU (SRAM, .data,
     * constructors) at the ledger's present.
     */
    private void startCpu() {
        long now = ledger.now();
        bootCycle = now;
        uwTick = 0;
        nextTick = now + CYCLES_PER_MS;
        tickPending = false;
        lastWaveCheck = now;
        jtagReleased = false;
        swdReleased = false;
        runtimeState = null;
        fault = null;
        faultLine = 0;
        // STM32duino's startup leaves the GPIOA/GPIOB clocks and AFIO on (pin init and the SWJ remap need them)
        gate(GATE_GPIOA, true);
        gate(GATE_GPIOB, true);
        gate(GATE_AFIO, true);
        toneHeldByHandler = false;
        eventSent = false;
        eventSerial = interruptSerial;
        cpu = program.createCpu(this, ledger);
        inHandler = handlerProbe(cpu);
        try {
            cpu.reset();
        } catch (HardFault f) {
            if (!resetRequested) {
                fault = f;
                faultLine = cpu.currentLine();
            }
        }
        if (!resetRequested && fault == null && cpu.halted())
            enterFault();
    }

    /**
     * NVIC_SystemReset, carried out by the window loop once the CPU has stopped: the
     * same reboot from flash a power-up does (fresh SRAM, peripherals and core state at
     * their reset values, setup() again, millis() from 0), but the clock and the window
     * go on — the board was never unpowered, so the pins' high time so far in this
     * window stands, and they float (off) while the chip is held in reset.
     */
    private void systemReset() {
        resetRequested = false;
        long now = ledger.now();
        cpu = null;
        for (int g = 0; g < GATES; g++)
            gate(g, false);
        gpio.resetKeepingWindow(now);
        tim1.reset();
        tim4.reset();
        tone.reset();
        long resume = Math.floorDiv(now + RESET_PULSE_CYCLES + CYCLES_PER_MS - 1, CYCLES_PER_MS) * CYCLES_PER_MS;
        ledger.charge(CycleClass.WAIT, resume - now);
        startCpu();
    }

    /**
     * What {@code NVIC_SystemReset()} does: requests the reset and returns what to
     * throw so that the CPU stops at once, as the core does in the few cycles before
     * the reset lands. The window loop then {@link #systemReset() reboots}.
     */
    public HardFault requestSystemReset() {
        resetRequested = true;
        return new HardFault(HardFault.Kind.ABORT, -1, "NVIC_SystemReset");
    }

    // ---- PlcCore: inputs, window, outputs ----------------------------------------

    @Override
    public void setInput(int arduinoPin, boolean high) {
        if (Gpio.valid(arduinoPin) && Gpio.hasFrontEnd(arduinoPin))
            gpio.setFrontEnd(arduinoPin, high);
    }

    @Override
    public void runFor(long nanos, long hostBudgetNanos) {
        long total = nanos * (Stm32f103cb.CPU_HZ / 1_000_000L) + cycleCarry;
        long requested = total / 1000;
        cycleCarry = total % 1000;

        if (powered && programmingLeftNanos > 0) {
            programmingLeftNanos -= nanos;
            if (programmingLeftNanos <= 0) {
                programmingLeftNanos = 0;
                reconcile();
            }
            idleWindow(PowerModel.PROGRAMMING_AMPS * PowerModel.VDD);
            return;
        }
        if (cpu == null) {
            double watts = !powered ? 0
                    : resetHeld ? PowerModel.RESET_AMPS * PowerModel.VDD
                    : PowerModel.BLANK_LOCKUP_AMPS * PowerModel.VDD;
            idleWindow(watts);
            return;
        }

        long start = ledger.now();
        scheduledEnd += requested;
        if (start >= scheduledEnd) {
            // Still inside an earlier window's overrun: a builtin charged in one go ran past
            // this window's end too, and the CPU is busy with it until the windows catch up
            // with its end. (The core's long loops, printf padding and strstr on long
            // strings among them, are charged in pieces and do not get here; see
            // Preemptible in the Arduino core.)
            // Nothing new runs; the last window's figures, which cover the whole overrun,
            // stand for this one too.
            return;
        }
        long[] classesBefore = ledger.snapshot();
        gpio.beginWindow(start);
        for (int g = 0; g < GATES; g++) {
            gateOnCycles[g] = 0;
            gateSince[g] = start;
        }
        adcCycles = 0;

        long deadline = System.nanoTime() + hostBudgetNanos;
        boolean dropped = false;
        int shortSegments = 0;
        while (fault == null && ledger.now() < scheduledEnd) {
            if (resetRequested) {
                // a reset requested while booting (a global constructor), or by the run below
                systemReset();
                if (System.nanoTime() > deadline) {
                    dropped = true;
                    break;
                }
                continue;
            }
            long before = ledger.now();
            long stop = Math.min(Math.min(scheduledEnd, gpio.nextWatchedWaveEdge(before)), nextIsrCycle());
            if (stop <= before)
                stop = Math.min(scheduledEnd, before + 1);
            segmentEnd = stop;
            CpuCore.Exit exit;
            try {
                exit = cpu.run(stop, deadline);
            } catch (HardFault f) {
                exit = CpuCore.Exit.HALTED;
            }
            sync();
            if (resetRequested)
                continue;
            if (exit == CpuCore.Exit.HALTED || cpu.halted()) {
                enterFault();
                break;
            }
            if (exit == CpuCore.Exit.DEADLINE) {
                dropped = true;
                break;
            }
            if (ledger.now() <= before) {
                // a CPU that reports reaching a cycle it did not reach; spin the gap rather than loop
                ledger.charge(CycleClass.WAIT, stop - before);
            }
            // The CPU reads the clock every few thousand instructions of one run; a window
            // cut into stretches shorter than that (every tone update, every edge of a PWM
            // an EXTI line watches) would never reach its check, so look here too.
            boolean longSegment = ledger.now() - before >= LONG_SEGMENT_CYCLES;
            if ((longSegment || ++shortSegments % SHORT_SEGMENTS_PER_CLOCK_READ == 0)
                    && System.nanoTime() > deadline) {
                dropped = true;
                break;
            }
        }
        if (fault != null && ledger.now() < scheduledEnd)
            ledger.charge(CycleClass.BRANCH, scheduledEnd - ledger.now()); // b . in HardFault_Handler
        if (dropped)
            scheduledEnd = ledger.now();

        long end = ledger.now();
        long length = end - start;
        long[] classesAfter = ledger.snapshot();
        for (int c = 0; c < classesAfter.length; c++)
            lastWindowCycles[c] = classesAfter[c] - classesBefore[c];
        lastWindowLength = length;
        for (int g = 0; g < GATES; g++)
            if (gateOn[g])
                gateOnCycles[g] += end - gateSince[g];

        for (int pin = 0; pin < ArduinoPins.COUNT; pin++) {
            double hz = gpio.waveHz(pin);
            if (tone.pin() == pin && gpio.mode(pin) == Gpio.Mode.OUTPUT_PP && fault == null)
                hz = tone.toneHz();
            drives[pin] = gpio.drive(pin, start, end, hz);
        }
        // A window that reached its scheduled end is on time even when it is a few cycles
        // short: the previous one overran its end by an instruction or a builtin, and this
        // one made it up. Only time dropped to the host budget is falling behind.
        speed = !dropped || requested <= 0 ? 1 : Math.min(1.0, (double) length / requested);
        long wait = lastWindowCycles[CycleClass.WAIT.ordinal()];
        cpuLoad = length > 0 ? Math.max(0, Math.min(1, 1 - (double) wait / length)) : 0;
        if (length > 0)
            powerWatts = PowerModel.windowWatts(lastWindowCycles, length, gateOnCycles, adcCycles);
    }

    private void idleWindow(double watts) {
        Arrays.fill(drives, PinDrive.OFF);
        Arrays.fill(lastWindowCycles, 0);
        lastWindowLength = 0;
        speed = 1;
        cpuLoad = 0;
        powerWatts = watts;
    }

    private void enterFault() {
        if (fault != null)
            return;
        fault = cpu.fault();
        if (fault == null)
            fault = new HardFault(HardFault.Kind.USAGE_FAULT, -1, "HardFault");
        faultLine = cpu.currentLine();
    }

    @Override
    public PinDrive pin(int arduinoPin) {
        return Gpio.valid(arduinoPin) ? drives[arduinoPin] : PinDrive.OFF;
    }

    @Override
    public McuState state() {
        if (!powered)
            return McuState.UNPOWERED;
        if (programmingLeftNanos > 0)
            return McuState.PROGRAMMING;
        if (resetHeld)
            return McuState.IN_RESET;
        if (program == null || cpu == null)
            return McuState.NO_PROGRAM;
        if (fault != null)
            return McuState.HARD_FAULT;
        return McuState.RUNNING;
    }

    @Override
    public HardFault fault() {
        return fault;
    }

    @Override
    public int faultLine() {
        return fault != null ? faultLine : 0;
    }

    @Override
    public double speed() {
        return speed;
    }

    @Override
    public double cpuLoad() {
        return cpuLoad;
    }

    @Override
    public double mcuPowerWatts() {
        return powerWatts;
    }

    @Override
    public int stackPeak() {
        return cpu != null ? cpu.stackPeak() : 0;
    }

    @Override
    public int heapPeak() {
        return cpu != null ? cpu.heapPeak() : 0;
    }

    @Override
    public long uptimeMillis() {
        return cpu != null ? (ledger.now() - bootCycle) / CYCLES_PER_MS : 0;
    }

    /** Cycles charged per {@link CycleClass} (by ordinal) over the last window; all zero when nothing ran. */
    public long[] lastWindowCycles() {
        return lastWindowCycles.clone();
    }

    /** Emulated cycles the last window covered. */
    public long lastWindowLength() {
        return lastWindowLength;
    }

    // ---- McuEnvironment ----------------------------------------------------------

    /**
     * The end of the current window, or sooner the next edge of a timer or tone
     * waveform on a pin the sketch has read: a loop polling that pin must see it.
     */
    @Override
    public long nextEventCycle() {
        long now = ledger.now();
        long next = Math.max(scheduledEnd, now);
        for (int pin = 0; pin < ArduinoPins.COUNT; pin++)
            if (gpio.observed(pin))
                next = Math.min(next, gpio.nextLevelChange(pin, now));
        return next;
    }

    // ---- what the Arduino core reaches -------------------------------------------

    /**
     * How to ask a CPU whether it is running an interrupt handler. {@link CpuCore} is
     * frozen and has no such question, so the CPU answers through an optional public
     * {@code boolean inInterrupt()} of its own class, looked up once per boot and bound
     * as a method handle (a plain reflective call would be slow until the JVM inflates
     * it, a stall of milliseconds in the first window that asks). A CPU without one is
     * taken never to be in a handler, which is the model as it was before priorities:
     * TIM3 then keeps toggling through EXTI handlers.
     */
    static BooleanSupplier handlerProbe(CpuCore cpu) {
        MethodHandle bound;
        try {
            Method m = cpu.getClass().getMethod("inInterrupt");
            if (m.getReturnType() != boolean.class)
                return () -> false;
            m.setAccessible(true); // the CPU's class may be package-private
            bound = MethodHandles.lookup().unreflect(m).bindTo(cpu).asType(MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return () -> false;
        }
        return () -> {
            try {
                return (boolean) bound.invokeExact();
            } catch (Throwable e) {
                return false;
            }
        };
    }

    /** Whether the CPU is in an (EXTI) interrupt handler now. */
    public boolean inInterruptHandler() {
        return cpu != null && inHandler.getAsBoolean();
    }

    /**
     * The cycle the CPU stretch the window loop is running will stop at: the next
     * SysTick or tone update, watched waveform edge, or the window's end. A builtin
     * that charges its time in pieces ends each piece here, so the chip gets the CPU
     * back at every event and interrupts are taken between pieces at their own cycle.
     * At or before now outside a stretch.
     */
    public long segmentEnd() {
        return segmentEnd;
    }

    /** The CPU running the flashed image, or null when none is; for tests that inspect SRAM. */
    CpuCore cpu() {
        return cpu;
    }

    public CycleLedger ledger() {
        return ledger;
    }

    public long now() {
        return ledger.now();
    }

    public Gpio gpio() {
        return gpio;
    }

    public ToneTimer toneTimer() {
        return tone;
    }

    /** The PWM timer with a channel on this pin (TIM1 for PB0/PB1/PA11, TIM4 for PB6/PB7), or null. */
    public PwmTimer pwmTimer(int pin) {
        if (tim1.channelOf(pin) >= 0)
            return tim1;
        if (tim4.channelOf(pin) >= 0)
            return tim4;
        return null;
    }

    /**
     * The Arduino core's globals for this boot, created on first use. They stand for
     * .data/.bss the core keeps in SRAM (g_digPinConfigured, the analog resolutions,
     * the PRNG state, ...), so they are dropped on every reset.
     */
    @SuppressWarnings("unchecked")
    public <T> T runtimeState(Supplier<T> create) {
        if (runtimeState == null)
            runtimeState = create.get();
        return (T) runtimeState;
    }

    /** HAL_GetTick(). */
    public int uwTick() {
        sync();
        return uwTick;
    }

    /** Cycles into the current SysTick period, 0..71999 (LOAD + 1 - VAL - 1). */
    public long sysTickElapsed() {
        sync();
        return ledger.now() - (nextTick - CYCLES_PER_MS);
    }

    /** The cycle of the next SysTick reload. */
    public long nextTickCycle() {
        sync();
        return nextTick;
    }

    /**
     * What {@link #uwTick()} would return after catching up, without catching up
     * (which charges ISR cycles): for a blocker estimating when to be polled.
     */
    public int peekUwTick() {
        if (cpu == null || fault != null || !cpu.interruptsEnabled())
            return uwTick;
        return (int) (uwTick + ticksDue());
    }

    /** What {@link #nextTickCycle()} would return after catching up, without catching up. */
    public long peekNextTick() {
        return nextTick + ticksDue() * CYCLES_PER_MS;
    }

    private long ticksDue() {
        long now = ledger.now();
        return nextTick <= now ? (now - nextTick) / CYCLES_PER_MS + 1 : 0;
    }

    public boolean interruptsEnabled() {
        return cpu == null || cpu.interruptsEnabled();
    }

    /**
     * Brings SysTick and the tone timer up to the ledger's present, replaying their
     * interrupts in order and charging them. Idempotent; every builtin that observes
     * time or a pin calls it first.
     */
    public void sync() {
        if (cpu == null || fault != null)
            return;
        // Charging an ISR moves the clock on, and another event can fall inside the span
        // just charged (a tone toggle during a SysTick, the next toggle during this one):
        // that one is due too, so go round until nothing is. It converges because no
        // source interrupts faster than its handler runs (tone() is capped at 65535 Hz,
        // a 549-cycle half period against a 112-cycle ISR); the cap is only a backstop.
        for (int pass = 0; pass < MAX_SYNC_PASSES; pass++) {
            long now = ledger.now();
            boolean enabled = cpu.interruptsEnabled();
            long isrCycles = 0;
            // TIM3 (priority 14) cannot preempt an EXTI handler (6); SysTick (0) can.
            boolean handler = enabled && (tone.running() || toneHeldByHandler) && inHandler.getAsBoolean();
            if (toneHeldByHandler && !handler) {
                // the handler has returned: the update it held back is taken now, once
                toneHeldByHandler = false;
                if (enabled)
                    isrCycles += (long) tone.takePending(now) * ToneTimer.ISR_CYCLES;
            }
            if (handler)
                toneHeldByHandler = true;
            while (nextTick <= now) {
                if (enabled) {
                    uwTick++;
                    isrCycles += SYSTICK_ISR_CYCLES;
                } else {
                    tickPending = true;
                }
                nextTick += CYCLES_PER_MS;
            }
            isrCycles += (long) tone.advance(now, enabled && !handler) * ToneTimer.ISR_CYCLES;
            gpio.waveEdges(lastWaveCheck, now);
            lastWaveCheck = now;
            if (isrCycles == 0)
                return;
            interruptSerial++;
            ledger.charge(CycleClass.INTERRUPT, isrCycles);
        }
    }

    /**
     * The cycle of the next SysTick or tone update interrupt, which {@link #runFor}
     * stops the CPU at so that {@link #sync()} charges it at its own time. Between
     * catch-ups both may lie in the past; then it is now.
     */
    private long nextIsrCycle() {
        long next = nextTick;
        if (tone.running())
            next = Math.min(next, tone.nextEvent());
        return next;
    }

    /**
     * PRIMASK has just been cleared by {@code __enable_irq}: interrupts left pending
     * while it was set are taken now, once each. Returns whether any were.
     */
    public boolean interruptsReenabled() {
        if (cpu == null || fault != null)
            return false;
        long isrCycles = 0;
        if (tickPending) {
            tickPending = false;
            uwTick++;
            isrCycles += SYSTICK_ISR_CYCLES;
        }
        // __enable_irq inside an EXTI handler: TIM3 is still outranked until it returns
        if (tone.updatePending() && !inHandler.getAsBoolean())
            isrCycles += (long) tone.takePending(ledger.now()) * ToneTimer.ISR_CYCLES;
        ledger.charge(CycleClass.INTERRUPT, isrCycles);
        if (isrCycles > 0)
            interruptSerial++;
        return isrCycles > 0;
    }

    /** An EXTI line pending its interrupt. */
    void pendExti(int line, long handler) {
        if (cpu != null && fault == null && handler != 0) {
            cpu.pendInterrupt(line, handler);
            interruptSerial++;
        }
    }

    /**
     * A count that moves whenever an interrupt is taken or an EXTI line pends one, as
     * of the last catch-up: a {@code __WFI} parked at one value wakes when it moves.
     */
    public long interruptSerial() {
        return interruptSerial;
    }

    /** SEV: sets the event register. */
    public void sendEvent() {
        eventSent = true;
    }

    /**
     * WFE's test of the event register: set by SEV and by every exception taken since
     * the last WFE (exception entry and return both set it on the M3). Returns whether
     * it was set, clearing it.
     */
    public boolean takeEvent() {
        boolean set = eventSent || eventSerial != interruptSerial;
        eventSent = false;
        eventSerial = interruptSerial;
        return set;
    }

    /**
     * Whether SysTick or TIM3 has an interrupt pending behind PRIMASK, as of the last
     * catch-up. WFI does not sleep while one is: a pending interrupt wakes the core
     * whether or not it can be taken.
     */
    public boolean interruptPending() {
        return tickPending || tone.updatePending();
    }

    /**
     * The cycle of the next SysTick or TIM3 update interrupt, without catching up:
     * when a {@code __WFI} should look again.
     */
    public long peekNextInterruptCycle() {
        return nextIsrCycle();
    }

    /** Switches a peripheral clock (RCC enable bit). */
    public void gate(int gate, boolean on) {
        if (gateOn[gate] == on)
            return;
        long now = ledger.now();
        if (on) {
            gateSince[gate] = now;
        } else {
            gateOnCycles[gate] += now - gateSince[gate];
        }
        gateOn[gate] = on;
    }

    /** The ADC powered and converting for {@code cycles}; priced by the power model. */
    public void adcActive(long cycles) {
        adcCycles += cycles;
    }

    /** AFIO_MAPR SWJ_CFG: whether JTAG (PA15, PB3, PB4) has been given back to GPIO. */
    public boolean jtagReleased() {
        return jtagReleased;
    }

    /** SWJ_CFG = 010: JTAG-DP off, SW-DP kept. */
    public void releaseJtag() {
        jtagReleased = true;
    }

    /** Whether SWD (PA13, PA14) has been given back to GPIO too. */
    public boolean swdReleased() {
        return swdReleased;
    }

    /** SWJ_CFG = 100: both debug ports off. */
    public void releaseSwd() {
        jtagReleased = true;
        swdReleased = true;
    }
}
