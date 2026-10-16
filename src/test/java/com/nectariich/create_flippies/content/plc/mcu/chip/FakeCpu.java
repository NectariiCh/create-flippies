package com.nectariich.create_flippies.content.plc.mcu.chip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.Builtin;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.CycleLedger;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.McuEnvironment;
import com.nectariich.create_flippies.content.plc.mcu.Memory;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.arduino.ArduinoCore;

/**
 * A stand-in for the language agent's CPU: runs a "sketch" written as a list of
 * builtin calls (setup steps once, loop steps forever), with the contract CpuCore
 * promises — interrupts between steps and while a blocker is parked, blockers polled
 * at their next poll cycle (MAX bounded by the environment's next event), skipped time
 * charged as WAIT, faults halting. Enough to test the chip and the core without a
 * compiler.
 */
final class FakeCpu implements CpuCore {

    /** One step of the fake sketch: usually a builtin call. */
    interface Step {
        void run(FakeCpu cpu);
    }

    /** A program whose CPU runs the given steps. */
    static final class Program implements CompiledProgram {
        final List<Step> setup = new ArrayList<>();
        final List<Step> loop = new ArrayList<>();
        final Map<Long, Step> handlers = new HashMap<>();
        FakeCpu last;
        int flashBytes = 12_000;

        @Override public int flashBytes() { return flashBytes; }
        @Override public int staticRamBytes() { return 1000; }
        @Override public String source() { return "fake"; }

        @Override
        public CpuCore createCpu(McuEnvironment environment, CycleLedger ledger) {
            last = new FakeCpu(this, environment, ledger);
            return last;
        }
    }

    final Program program;
    final McuEnvironment env;
    final CycleLedger ledger;
    final FakeMemory memory = new FakeMemory();

    private boolean inSetup;
    private int index;
    private boolean enabled = true;
    private final Map<Integer, Long> pending = new LinkedHashMap<>();
    private Blocker parked;
    private Call parkedCall;
    private HardFault fault;
    boolean idleWhenDone;
    /** What {@link #inInterrupt()} answers: a step can claim the CPU is in an interrupt handler. */
    boolean inHandler;

    long lastReturn;
    double lastDouble;
    float lastFloat;
    int sideEffects;
    int timeReads;
    int interruptsTaken;

    FakeCpu(Program program, McuEnvironment env, CycleLedger ledger) {
        this.program = program;
        this.env = env;
        this.ledger = ledger;
    }

    @Override
    public void reset() {
        inSetup = true;
        index = 0;
        enabled = true;
        pending.clear();
        parked = null;
        fault = null;
    }

    @Override
    public Exit run(long untilCycle, long hostDeadlineNanos) {
        while (ledger.now() < untilCycle) {
            if (fault != null)
                return Exit.HALTED;
            if (System.nanoTime() > hostDeadlineNanos)
                return Exit.DEADLINE;
            try {
                if (enabled && !pending.isEmpty()) {
                    int irq = pending.keySet().iterator().next();
                    long handler = pending.remove(irq);
                    ledger.charge(CycleClass.INTERRUPT, 12);
                    Step h = program.handlers.get(handler);
                    if (h != null)
                        h.run(this);
                    ledger.charge(CycleClass.INTERRUPT, 12);
                    interruptsTaken++;
                    continue;
                }
                if (parked != null) {
                    long t = parked.nextPollCycle();
                    if (t == Long.MAX_VALUE)
                        t = env.nextEventCycle();
                    long now = ledger.now();
                    if (t > now) {
                        long to = Math.min(t, untilCycle);
                        ledger.charge(CycleClass.WAIT, to - now);
                        if (to < t)
                            continue;
                    }
                    if (parked.poll(parkedCall)) {
                        parked = null;
                        parkedCall = null;
                    } else if (ledger.now() == now && parked.nextPollCycle() <= now) {
                        ledger.charge(CycleClass.WAIT, 1); // guard against a blocker that never advances
                    }
                    continue;
                }
                List<Step> steps = inSetup ? program.setup : program.loop;
                if (index >= steps.size()) {
                    if (inSetup) {
                        inSetup = false;
                        index = 0;
                        continue;
                    }
                    if (steps.isEmpty() || idleWhenDone) {
                        ledger.charge(CycleClass.WAIT, untilCycle - ledger.now());
                        continue;
                    }
                    index = 0;
                    ledger.charge(CycleClass.BRANCH, 3);
                    continue;
                }
                steps.get(index++).run(this);
            } catch (HardFault f) {
                fault = f;
                return Exit.HALTED;
            }
        }
        return Exit.REACHED;
    }

    /** Calls a builtin by prototype prefix (e.g. "void digitalWrite(") with raw argument slots. */
    long call(String prototypePrefix, long... args) {
        Builtin b = find(prototypePrefix);
        Call c = new Call(args);
        b.invoke(c);
        return lastReturn;
    }

    static Builtin find(String prototypePrefix) {
        for (Builtin b : ArduinoCore.INSTANCE.builtins())
            if (b.prototype().startsWith(prototypePrefix))
                return b;
        throw new IllegalArgumentException("no builtin " + prototypePrefix);
    }

    static Step step(String prototypePrefix, long... args) {
        return cpu -> cpu.call(prototypePrefix, args);
    }

    @Override public boolean halted() { return fault != null; }
    @Override public HardFault fault() { return fault; }
    @Override public int currentLine() { return 42; }

    @Override
    public void pendInterrupt(int irq, long handler) {
        pending.putIfAbsent(irq, handler);
    }

    @Override public boolean interruptsEnabled() { return enabled; }

    /** The optional question the chip asks a CPU (see {@code Stm32f103.handlerProbe}). */
    public boolean inInterrupt() {
        return inHandler;
    }

    @Override public Memory memory() { return memory; }
    @Override public int stackPeak() { return 64; }
    @Override public int heapPeak() { return 0; }

    final class Call implements Invocation {
        final long[] args;

        Call(long[] args) {
            this.args = args;
        }

        @Override public int argCount() { return args.length; }
        @Override public long arg(int i) { return args[i]; }
        @Override public float argFloat(int i) { return Float.intBitsToFloat((int) args[i]); }
        @Override public double argDouble(int i) { return Double.longBitsToDouble(args[i]); }
        @Override public void returnValue(long v) { lastReturn = v; }
        @Override public void returnFloat(float v) { lastFloat = v; lastReturn = Float.floatToRawIntBits(v); }
        @Override public void returnDouble(double v) { lastDouble = v; lastReturn = Double.doubleToRawLongBits(v); }
        @Override public Memory memory() { return memory; }
        @Override public CycleLedger ledger() { return ledger; }
        @Override public McuEnvironment environment() { return env; }
        @Override public void charge(CycleClass cls, long cycles) { ledger.charge(cls, cycles); }

        @Override
        public void block(Blocker blocker) {
            parked = blocker;
            parkedCall = this;
        }

        @Override public void noteTimeRead(long resolutionCycles) { timeReads++; }
        @Override public void noteSideEffect() { sideEffects++; }
        @Override public void setInterruptsEnabled(boolean e) { enabled = e; }
        @Override public boolean interruptsEnabled() { return enabled; }
        @Override public int sourceLine() { return 1; }
    }

    /** SRAM and a small flash region, little-endian, faulting like the frozen Memory contract says. */
    static final class FakeMemory implements Memory {
        final byte[] sram = new byte[Stm32f103cb.SRAM_SIZE];
        final byte[] flash = new byte[4096];

        private int index(long a, boolean write) {
            if (a >= Stm32f103cb.SRAM_BASE && a < Stm32f103cb.SRAM_BASE + sram.length)
                return (int) (a - Stm32f103cb.SRAM_BASE);
            if (a >= Stm32f103cb.FLASH_BASE && a < Stm32f103cb.FLASH_BASE + flash.length) {
                if (write)
                    throw new HardFault(HardFault.Kind.MEM_MANAGE, a, "store to flash");
                return -1 - (int) (a - Stm32f103cb.FLASH_BASE);
            }
            throw new HardFault(HardFault.Kind.BUS_FAULT, a, "bus fault");
        }

        @Override
        public int read8(long a) {
            int i = index(a, false);
            return (i >= 0 ? sram[i] : flash[-1 - i]) & 0xFF;
        }

        @Override public int read16(long a) { return read8(a) | read8(a + 1) << 8; }
        @Override public int read32(long a) { return read16(a) | read16(a + 2) << 16; }
        @Override public long read64(long a) { return (read32(a) & 0xFFFF_FFFFL) | (long) read32(a + 4) << 32; }

        @Override
        public void write8(long a, int v) {
            sram[index(a, true)] = (byte) v;
        }

        @Override public void write16(long a, int v) { write8(a, v); write8(a + 1, v >> 8); }
        @Override public void write32(long a, int v) { write16(a, v); write16(a + 2, v >> 16); }
        @Override public void write64(long a, long v) { write32(a, (int) v); write32(a + 4, (int) (v >> 32)); }

        @Override
        public String readCString(long a, int max) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < max; k++) {
                int b = read8(a + k);
                if (b == 0)
                    break;
                sb.append((char) b);
            }
            return sb.toString();
        }

        long putString(long a, String s) {
            for (int k = 0; k < s.length(); k++)
                write8(a + k, s.charAt(k));
            write8(a + s.length(), 0);
            return a;
        }

        String getString(long a) {
            return readCString(a, 4096);
        }
    }
}
