package com.nectariich.create_flippies.content.plc.mcu.lang;

import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.CycleLedger;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.McuEnvironment;
import com.nectariich.create_flippies.content.plc.mcu.Memory;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * The Cortex-M3 running a linked {@link Image}: a suspendable bytecode interpreter
 * whose every observable byte lives in {@link SimMemory}.
 *
 * <h2>State</h2>
 * Frames live in SRAM, from the top down: parameters, locals and temporaries at
 * fixed offsets from the frame base, and at the top of each frame the caller's
 * frame pointer and a return token, as PUSH {..., lr} would leave them. A return
 * reads both back and compares them with a shadow copy the VM keeps, so a buffer
 * overrun that tramples a frame faults on return, as a corrupted LR does on the
 * chip. The operand stack (the values an expression is part-way through) stands for
 * the register file and is the only state outside SRAM. Running the stack pointer
 * below 0x2000_0000 is a stack-overflow HardFault.
 *
 * <h2>Time</h2>
 * Every instruction carries its Cortex-M3 cost (see {@link CodeGen}); the VM adds
 * the data-dependent parts (division, flash wait states, taken branches, call and
 * return pushes and pops) and charges it all to the shared {@link CycleLedger},
 * batching per class in a local array and flushing before anything outside the VM
 * can look at the clock. {@link #run} stops at the first instruction boundary at
 * or after its target cycle, or when the host deadline (checked every 4096
 * instructions) passes, and resumes exactly there.
 *
 * <h2>Arithmetic</h2>
 * As the Cortex-M3 and libgcc compute it: integers wrap (two's complement, as GCC
 * emits); SDIV/UDIV truncate toward zero and, with CCR.DIV_0_TRP clear as
 * STM32duino leaves it, return 0 for a zero divisor, so {@code x % 0} (SDIV then
 * MLS) is {@code x}; {@code INT_MIN / -1} is {@code INT_MIN}. 64-bit division is a
 * libgcc call instead, whose zero-divisor path gives a saturated quotient
 * ({@code LLONG_MAX}/{@code LLONG_MIN}/0 by the dividend's sign, all ones unsigned)
 * and a remainder of 0. A shift by a register
 * uses the amount's bottom byte, as LSL/LSR/ASR do: 32..255 shift everything out
 * (ASR fills with the sign); 64-bit shifts behave alike at 64. float and double are
 * IEEE single and double with round-to-nearest, exactly libgcc's soft-float results;
 * float-to-integer conversion truncates and saturates (NaN gives 0), as
 * __aeabi_f2iz and friends do for out-of-range values.
 *
 * <h2>Idle loops</h2>
 * Each backward branch is a {@code LOOP} instruction with an id. When one completes
 * an iteration that changed no byte of SRAM, called no builtin that reported a side
 * effect, took no interrupt, and ended with the same frame and operand stack it
 * began with, every following iteration is the same computation on the same state
 * and will do the same thing until something outside changes: the environment
 * (bounded by {@link McuEnvironment#nextEventCycle()} and the end of the run), the
 * clock (bounded by the next tick of the finest timer the iteration read), or an
 * interrupt. So the VM skips a whole number of iterations up to the nearest bound,
 * charging each skipped iteration exactly what the modelled one cost, class by
 * class (the core does not sleep in a polling loop: it runs flat out from flash, and
 * the CPU load and power must say so); the loop then observes time at exactly the
 * phase it would have. An iteration that straddled a tick of a timer it
 * read is never used as the model, since the next one would read a new value.
 */
final class Vm implements CpuCore, Runtime.Host {

    /**
     * The register file's stand-in. A frame is at least 8 bytes of SRAM, so 20 KB holds
     * at most ~2560 of them; a call nested in an expression leaves a value or two
     * behind per level, which the chip would have pushed onto that same stack.
     * Running out of either is the stack overflow the chip would have had too.
     */
    private static final int OPSTACK = 6144;
    private static final int CALLSTACK = 2600;
    private static final int CHECK_EVERY = 4096;
    private static final int EXC_FRAME = 32;

    private static final int ALU = CycleClass.ALU.ordinal(), MULC = CycleClass.MUL.ordinal(), DIVC = CycleClass.DIV.ordinal(),
            LOADC = CycleClass.LOAD.ordinal(), STOREC = CycleClass.STORE.ordinal(), BRANCHC = CycleClass.BRANCH.ordinal(),
            SOFTC = CycleClass.SOFT_FLOAT.ordinal(), WAITC = CycleClass.WAIT.ordinal(), INTC = CycleClass.INTERRUPT.ordinal();
    private static final CycleClass[] CLASSES = CycleClass.values();

    private final Image img;
    private final int[] code;
    private final McuEnvironment env;
    private final CycleLedger ledger;
    final SimMemory mem;
    private final byte[] ram;
    final Heap heap;
    private final Runtime rt;
    private final Intrinsics.Op[] intrinsicOps = Intrinsics.Op.values();

    // ---- registers ----
    private final long[] stk = new long[OPSTACK];
    private int sp;
    private int pc;
    private int fp;
    private int ssp;
    private int fn;
    private final int[] csRet = new int[CALLSTACK];
    private final int[] csFn = new int[CALLSTACK];
    private final int[] csFp = new int[CALLSTACK];
    private final boolean[] csIsr = new boolean[CALLSTACK];
    private int depth;

    // ---- time ----
    private final long[] acc = new long[CLASSES.length];
    private long cyc;

    // ---- status ----
    private boolean halted;
    private HardFault fault;
    private int faultLine;
    private int minSsp;

    // ---- interrupts ----
    private boolean irqEnabled = true;
    private boolean inIsr;
    private final long[] pendingHandler = new long[256];
    private final boolean[] pending = new boolean[256];
    private int pendingCount;
    private Blocker isrSavedBlocker;
    private VmInvocation isrSavedInv;
    private CodeGen.CallDesc isrSavedDesc;

    // ---- parked builtin ----
    private Blocker parked;
    private VmInvocation parkedInv;
    private CodeGen.CallDesc parkedDesc;
    private final VmInvocation mainInv = new VmInvocation();
    private final VmInvocation isrInv = new VmInvocation();
    private final long[] intrArgs = new long[8];

    // ---- idle detection ----
    long sideEffects;
    private long irqTaken;
    private long runEpoch;
    private long timeReadSeq;
    private final long[] resValue = new long[4];
    private final long[] resSeq = new long[4];
    private final boolean[] snapValid;
    private final long[] snapCycle, snapChanges, snapSide, snapIrq, snapRun, snapTime;
    private final int[] snapSp, snapFp, snapDepth;
    /** Per loop id: every class's cycle total (ledger plus unflushed) at the snapshot. */
    private final long[][] snapClass;
    /** The idle-loop fast-forward; tests turn it off to prove it changes nothing but host time. */
    boolean fastForward = true;
    /** Iterations skipped by the idle fast-forward since reset, for tests and diagnostics. */
    long skippedIterations;
    long skippedCycles;
    /** Instructions interpreted since reset. */
    long instructions;

    Vm(Image img, McuEnvironment env, CycleLedger ledger) {
        this.img = img;
        this.code = img.code;
        this.env = env;
        this.ledger = ledger;
        this.mem = new SimMemory(img.flashImage);
        this.ram = mem.sram;
        this.heap = new Heap(mem, SimMemory.SRAM_BASE + (long) img.freeListOff, SimMemory.SRAM_BASE + (long) img.heapEndOff,
                SimMemory.SRAM_BASE + (long) img.heapStart);
        this.rt = new Runtime(mem, heap, this, SimMemory.SRAM_BASE + (long) img.dummyCharOff, img.emptyStrAddr);
        int n = Math.max(1, img.loopCount);
        snapValid = new boolean[n];
        snapCycle = new long[n];
        snapChanges = new long[n];
        snapSide = new long[n];
        snapIrq = new long[n];
        snapRun = new long[n];
        snapTime = new long[n];
        snapSp = new int[n];
        snapFp = new int[n];
        snapDepth = new int[n];
        snapClass = new long[n][CLASSES.length];
        reset();
    }

    // ================= CpuCore =================

    @Override
    public void reset() {
        // Everything but .noinit starts from zero (a fresh CPU's SRAM is all zero, standing
        // in for power-up contents); .noinit keeps whatever the SRAM held, which across a
        // warm reset is what the sketch left there, if the chip carried the SRAM over.
        int ni = Math.max(0, Math.min(img.noinitStart, ram.length));
        int ne = Math.max(ni, Math.min(img.noinitStart + img.noinitSize, ram.length));
        java.util.Arrays.fill(ram, 0, ni, (byte) 0);
        java.util.Arrays.fill(ram, ne, ram.length, (byte) 0);
        // .data from its load image in flash; .bss is the zero the RAM was just cleared to.
        System.arraycopy(img.flashImage, img.dataLoad, ram, img.dataStart, img.dataSize);
        mem.changes = 0;
        heap.reset();
        sp = 0;
        depth = 0;
        halted = false;
        fault = null;
        faultLine = 0;
        irqEnabled = true;
        inIsr = false;
        isrIrq = -1;
        java.util.Arrays.fill(pending, false);
        pendingCount = 0;
        parked = null;
        parkedInv = null;
        isrSavedBlocker = null;
        java.util.Arrays.fill(acc, 0);
        java.util.Arrays.fill(snapValid, false);
        sideEffects = 0;
        irqTaken = 0;
        timeReadSeq = 0;
        java.util.Arrays.fill(resValue, 0);
        java.util.Arrays.fill(resSeq, 0);
        skippedIterations = 0;
        skippedCycles = 0;
        instructions = 0;
        // _start's frame at the top of SRAM: MSP is loaded from the vector table's first word.
        ssp = SimMemory.SRAM_SIZE;
        minSsp = ssp;
        fn = img.startFn;
        int fs = img.fnFrame[fn];
        ssp -= fs;
        fp = ssp;
        minSsp = ssp;
        pc = img.fnEntry[fn];
    }

    @Override
    public boolean halted() {
        return halted;
    }

    @Override
    public HardFault fault() {
        return fault;
    }

    @Override
    public int currentLine() {
        if (halted)
            return faultLine;
        return lineAt(pc);
    }

    private int lineAt(int at) {
        if (at < 0 || at >= img.lines.length)
            return 0;
        return img.lines[at];
    }

    @Override
    public void pendInterrupt(int irq, long handler) {
        if (irq < 0 || irq >= pending.length)
            return;
        if (!pending[irq]) {
            pending[irq] = true;
            pendingCount++;
        }
        pendingHandler[irq] = handler;
    }

    @Override
    public boolean interruptsEnabled() {
        return irqEnabled;
    }

    /**
     * Whether an interrupt handler is running now. Not part of the frozen {@code CpuCore};
     * the chip finds it by name at boot (see {@code Stm32f103.handlerProbe}) so TIM3's tone
     * output can be held back while an EXTI handler runs, as the NVIC's priorities would.
     */
    public boolean inInterrupt() {
        return inIsr;
    }

    @Override
    public Memory memory() {
        return mem;
    }

    @Override
    public int stackPeak() {
        return SimMemory.SRAM_SIZE - minSsp;
    }

    @Override
    public int heapPeak() {
        return (int) heap.peak;
    }

    // ================= Runtime.Host =================

    @Override
    public long stackPointer() {
        // _sbrk compares against MSP inside malloc, a few words below the caller's frame.
        return SimMemory.SRAM_BASE + (long) ssp - 24;
    }

    @Override
    public void charge(CycleClass cls, long cycles) {
        if (cycles <= 0)
            return;
        acc[cls.ordinal()] += cycles;
        cyc += cycles;
    }

    // ================= time =================

    private void flush() {
        long total = 0;
        for (int i = 0; i < acc.length; i++)
            total += acc[i];
        if (total == 0) {
            cyc = ledger.now();
            return;
        }
        if (inIsr) {
            ledger.charge(CycleClass.INTERRUPT, total);
        } else {
            for (int i = 0; i < acc.length; i++)
                if (acc[i] != 0)
                    ledger.charge(CLASSES[i], acc[i]);
        }
        java.util.Arrays.fill(acc, 0);
        cyc = ledger.now();
    }

    private void idleFor(long cycles) {
        if (cycles <= 0)
            return;
        acc[WAITC] += cycles;
        cyc += cycles;
    }

    // ================= run =================

    @Override
    public Exit run(long untilCycle, long hostDeadlineNanos) {
        if (halted)
            return Exit.HALTED;
        runEpoch++;
        cyc = ledger.now();
        int outer = 0;
        try {
            while (true) {
                if (cyc >= untilCycle) {
                    flush();
                    return Exit.REACHED;
                }
                // The interpreter checks the clock itself; this catches a run that keeps coming back
                // here (an interrupt storm, a builtin that parks and wakes at once).
                if ((++outer & 255) == 0 && System.nanoTime() > hostDeadlineNanos) {
                    flush();
                    return Exit.DEADLINE;
                }
                if (pendingCount > 0 && irqEnabled && !inIsr) {
                    takeInterrupt();
                    continue;
                }
                if (parked != null) {
                    if (!serviceBlocker(untilCycle)) {
                        flush();
                        return Exit.REACHED;
                    }
                    continue;
                }
                int r = interpret(untilCycle, hostDeadlineNanos);
                if (r == 1) {
                    flush();
                    return Exit.DEADLINE;
                }
            }
        } catch (HardFault f) {
            enterFault(f);
            flush();
            return Exit.HALTED;
        } catch (ArrayIndexOutOfBoundsException e) {
            if (sp >= OPSTACK - 8 || depth >= CALLSTACK - 1) {
                enterFault(new HardFault(HardFault.Kind.STACK_OVERFLOW, SimMemory.SRAM_BASE + (long) ssp, "stack overflow"));
            } else {
                enterFault(new HardFault(HardFault.Kind.USAGE_FAULT, -1, "emulator error: " + e));
            }
            flush();
            return Exit.HALTED;
        } catch (RuntimeException | StackOverflowError e) {
            // A bug in the emulator or in a builtin must stop this CPU, not the server tick running it.
            enterFault(new HardFault(HardFault.Kind.USAGE_FAULT, -1, "emulator error: " + e));
            flush();
            return Exit.HALTED;
        }
    }

    private void enterFault(HardFault f) {
        halted = true;
        fault = f;
        faultLine = lineAt(pc);
        parked = null;
    }

    /** Parks on a Blocker: skips to its next poll (or the next external event), polls. False if the run target came first. */
    private boolean serviceBlocker(long until) {
        long np = parked.nextPollCycle();
        if (cyc < np) {
            long ev = env.nextEventCycle();
            long bound = until;
            if (ev > cyc && ev < bound)
                bound = ev;
            long target = Math.min(np, bound);
            if (target > cyc)
                idleFor(target - cyc);
            if (cyc < np && cyc >= until)
                return false;
        }
        flush();
        boolean done;
        VmInvocation inv = parkedInv;
        Blocker b = parked;
        done = b.poll(inv);
        cyc = ledger.now();
        if (done) {
            parked = null;
            pushResult(parkedDesc, inv);
            parkedInv = null;
            parkedDesc = null;
            return true;
        }
        if (b.nextPollCycle() <= cyc) {
            long ev = env.nextEventCycle();
            if (ev <= cyc) {
                // Nothing will change before the run ends: wait it out.
                if (until > cyc)
                    idleFor(until - cyc);
                return false;
            }
        }
        return true;
    }

    private void takeInterrupt() {
        int irq = -1;
        for (int i = 0; i < pending.length; i++) {
            if (pending[i]) {
                irq = i;
                break;
            }
        }
        if (irq < 0) {
            pendingCount = 0;
            return;
        }
        pending[irq] = false;
        pendingCount--;
        long handler = pendingHandler[irq];
        int f = img.functionAt(handler);
        flush();
        // Stack the exception frame: r0-r3, r12, lr, pc, xPSR.
        int frame = ssp - EXC_FRAME;
        if (frame < 0)
            throw new HardFault(HardFault.Kind.STACK_OVERFLOW, SimMemory.SRAM_BASE + (long) frame, "stack overflow while entering an interrupt");
        for (int i = 0; i < 8; i++)
            write32(frame + 4 * i, i == 6 ? token(pc) : i == 7 ? 0x01000000 : i == 5 ? 0xFFFFFFF9 : 0);
        ssp = frame;
        if (ssp < minSsp)
            minSsp = ssp;
        ledger.charge(CycleClass.INTERRUPT, 12 + extiDispatchCycles(irq));
        cyc = ledger.now();
        irqTaken++;
        isrIrq = irq;
        if (f < 0) {
            // Not a function: the core would vector into garbage.
            throw new HardFault(HardFault.Kind.USAGE_FAULT, handler & 0xFFFFFFFFL,
                    "interrupt handler 0x" + Long.toHexString(handler & 0xFFFFFFFFL) + " is not a function");
        }
        isrSavedBlocker = parked;
        isrSavedInv = parkedInv;
        isrSavedDesc = parkedDesc;
        parked = null;
        parkedInv = null;
        parkedDesc = null;
        inIsr = true;
        int nArgs = img.fnParamOff[f].length;
        for (int i = 0; i < nArgs; i++)
            stk[sp++] = 0;
        enter(f, pc, true);
    }

    /** The irq of the handler running, for its exit cost. */
    private int isrIrq = -1;

    /**
     * What STM32duino runs between the exception entry and the sketch's handler for
     * an EXTI line (irq = line number; nothing else is pended through
     * {@link #pendInterrupt}). EXTIn_IRQHandler (interrupt.cpp) calls
     * HAL_GPIO_EXTI_IRQHandler: an EXTI->PR read over APB2, a test, the PR clear and
     * a call, ~50 cycles with HAL_GPIO_EXTI_Callback's own entry; that runs
     * get_pin_id, a shift loop of one iteration (~4 cycles) per line number, loads
     * gpio_irq_conf[id] and calls through std::function::operator() and _M_invoke to
     * the sketch's function. The shared vectors call HAL_GPIO_EXTI_IRQHandler once
     * per line of their group, ~12 cycles for each line not pending:
     * EXTI9_5_IRQHandler five times, EXTI15_10_IRQHandler six. TIM3's tone handler
     * goes through the same HAL and std::function layers, which the chip's
     * ToneTimer.ISR_CYCLES already includes.
     */
    static int extiDispatchCycles(int line) {
        if (line < 0 || line > 15)
            return 0;
        int group = line <= 4 ? 0 : line <= 9 ? 5 * 12 : 6 * 12;
        return 50 + group + 4 * line;
    }

    /** The unwinding back out through std::function and the HAL after the handler returns. */
    static final int EXTI_RETURN_CYCLES = 12;

    private static int token(int retPc) {
        return 0x08000000 | ((retPc & 0x7FFFF) << 1) | 1;
    }

    private void write32(int off, int v) {
        if ((int) SimMemory.I32.get(ram, off) != v) {
            SimMemory.I32.set(ram, off, v);
            mem.changes++;
        }
    }

    /** Builds a frame for function {@code f} with its arguments popped from the operand stack. */
    private void enter(int f, int retPc, boolean isr) {
        int fs = img.fnFrame[f];
        int nfp = ssp - fs;
        if (nfp < 0)
            throw new HardFault(HardFault.Kind.STACK_OVERFLOW, SimMemory.SRAM_BASE + (long) nfp,
                    "stack overflow: calling " + img.fnName[f] + " needs " + fs + " bytes below 0x"
                            + Long.toHexString(SimMemory.SRAM_BASE + (long) ssp));
        if (depth >= CALLSTACK)
            throw new HardFault(HardFault.Kind.STACK_OVERFLOW, SimMemory.SRAM_BASE + (long) nfp, "stack overflow");
        int[] po = img.fnParamOff[f];
        int[] ps = img.fnParamSize[f];
        int np = po.length;
        sp -= np;
        for (int i = 0; i < np; i++) {
            long v = stk[sp + i];
            int o = nfp + po[i];
            switch (ps[i]) {
                case 1 -> {
                    byte b = (byte) v;
                    if (ram[o] != b) {
                        ram[o] = b;
                        mem.changes++;
                    }
                }
                case 2 -> {
                    short s = (short) v;
                    if ((short) SimMemory.I16.get(ram, o) != s) {
                        SimMemory.I16.set(ram, o, s);
                        mem.changes++;
                    }
                }
                case 8 -> {
                    if ((long) SimMemory.I64.get(ram, o) != v) {
                        SimMemory.I64.set(ram, o, v);
                        mem.changes++;
                    }
                }
                default -> write32(o, (int) v);
            }
        }
        write32(nfp + fs - 8, SimMemory.SRAM_BASE + fp);
        write32(nfp + fs - 4, token(retPc));
        csRet[depth] = retPc;
        csFn[depth] = fn;
        csFp[depth] = fp;
        csIsr[depth] = isr;
        depth++;
        fp = nfp;
        ssp = nfp;
        if (ssp < minSsp)
            minSsp = ssp;
        fn = f;
        pc = img.fnEntry[f];
        int saved = img.fnSaved[f];
        // BL: 1 + P, plus the flash wait states of the refill (see CodeGen.TAKEN).
        acc[BRANCHC] += 4 + Stm32f103cb.FLASH_WAIT_STATES;
        acc[STOREC] += 1 + saved;
        acc[ALU] += 1;
        cyc += 6 + Stm32f103cb.FLASH_WAIT_STATES + saved;
        np = 0;
    }

    /** Pops the current frame; returns true if it was an interrupt handler's. */
    private boolean leave() {
        int fs = img.fnFrame[fn];
        int saved = img.fnSaved[fn];
        int linkFp = (int) SimMemory.I32.get(ram, fp + fs - 8);
        int linkLr = (int) SimMemory.I32.get(ram, fp + fs - 4);
        depth--;
        int retPc = csRet[depth];
        if (linkLr != token(retPc) || linkFp != SimMemory.SRAM_BASE + csFp[depth]) {
            throw new HardFault(HardFault.Kind.USAGE_FAULT, (long) linkLr & 0xFFFFFFFFL,
                    "return from " + img.fnName[fn] + " through a corrupted stack frame (the saved return address was overwritten)");
        }
        ssp = fp + fs;
        fp = csFp[depth];
        fn = csFn[depth];
        pc = retPc;
        acc[LOADC] += 1 + saved;
        acc[BRANCHC] += 3 + Stm32f103cb.FLASH_WAIT_STATES;
        acc[ALU] += 1;
        cyc += 5 + Stm32f103cb.FLASH_WAIT_STATES + saved;
        return csIsr[depth];
    }

    private void exitIsr() {
        ssp += EXC_FRAME;
        flush();
        ledger.charge(CycleClass.INTERRUPT, 12 + (isrIrq >= 0 && isrIrq <= 15 ? EXTI_RETURN_CYCLES : 0));
        isrIrq = -1;
        inIsr = false;
        cyc = ledger.now();
        parked = isrSavedBlocker;
        parkedInv = isrSavedInv;
        parkedDesc = isrSavedDesc;
        isrSavedBlocker = null;
        isrSavedInv = null;
        isrSavedDesc = null;
    }

    // ================= the interpreter =================

    private static int clz(int v) {
        return Integer.numberOfLeadingZeros(v);
    }

    private static int divCycles(int a, int b) {
        int diff = clz(b) - clz(a);
        return 2 + Math.max(0, Math.min(10, (diff + 2) / 3));
    }

    private static int ldivCycles(long a, long b) {
        int diff = Long.numberOfLeadingZeros(b) - Long.numberOfLeadingZeros(a);
        if ((a >>> 32) == 0 && (b >>> 32) == 0)
            return 20;
        return 30 + Math.max(0, Math.min(100, diff * 2));
    }

    private long loadSlow(int a, int n, boolean signed) {
        long before = mem.flashReads;
        long v = switch (n) {
            case 1 -> mem.read8(a & 0xFFFFFFFFL);
            case 2 -> mem.read16(a & 0xFFFFFFFFL);
            case 4 -> mem.read32(a & 0xFFFFFFFFL);
            default -> mem.read64(a & 0xFFFFFFFFL);
        };
        if (mem.flashReads != before) {
            // FLASH_ACR LATENCY=2: data reads from flash wait two cycles (the prefetch buffer only helps instruction fetch).
            acc[LOADC] += 2;
            cyc += 2;
        }
        if (signed)
            v = n == 1 ? (byte) v : (short) v;
        return v;
    }

    /**
     * Runs until the target cycle (0), the host deadline (1), or something the outer
     * loop must handle: an interrupt to take, a builtin that parked, a fault.
     */
    private int interpret(long until, long deadline) {
        final int[] code = this.code;
        final long[] stk = this.stk;
        final byte[] ram = this.ram;
        final long[] acc = this.acc;
        int pc = this.pc;
        int sp = this.sp;
        int fp = this.fp;
        long cyc = this.cyc;
        int budget = CHECK_EVERY;
        long count = 0;
        int status = 0;
        try {
            loop:
            while (true) {
                if (cyc >= until) {
                    status = 0;
                    break;
                }
                if (--budget == 0) {
                    budget = CHECK_EVERY;
                    if (System.nanoTime() > deadline) {
                        status = 1;
                        break;
                    }
                }
                count++;
                int w = code[pc];
                int c = w >>> 12;
                cyc += c;
                acc[(w >>> 8) & 0xF] += c;
                switch (w & 0xFF) {
                    case Op.NOP -> pc++;
                    case Op.ICONST -> {
                        stk[sp++] = code[pc + 1];
                        pc += 2;
                    }
                    case Op.LCONST -> {
                        stk[sp++] = (code[pc + 1] & 0xFFFFFFFFL) | ((long) code[pc + 2] << 32);
                        pc += 3;
                    }
                    case Op.LADDR -> {
                        stk[sp++] = SimMemory.SRAM_BASE + fp + code[pc + 1];
                        pc += 2;
                    }
                    case Op.LD8U -> {
                        int a = (int) stk[sp - 1];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o < SimMemory.SRAM_SIZE) {
                            stk[sp - 1] = ram[o] & 0xFF;
                        } else {
                            stk[sp - 1] = (int) syncLoad(pc, cyc, a, 1, false);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.LD8S -> {
                        int a = (int) stk[sp - 1];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o < SimMemory.SRAM_SIZE) {
                            stk[sp - 1] = ram[o];
                        } else {
                            stk[sp - 1] = (int) syncLoad(pc, cyc, a, 1, true);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.LD16U -> {
                        int a = (int) stk[sp - 1];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 2) {
                            stk[sp - 1] = (short) SimMemory.I16.get(ram, o) & 0xFFFF;
                        } else {
                            stk[sp - 1] = (int) syncLoad(pc, cyc, a, 2, false);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.LD16S -> {
                        int a = (int) stk[sp - 1];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 2) {
                            stk[sp - 1] = (short) SimMemory.I16.get(ram, o);
                        } else {
                            stk[sp - 1] = (int) syncLoad(pc, cyc, a, 2, true);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.LD32 -> {
                        int a = (int) stk[sp - 1];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 4) {
                            stk[sp - 1] = (int) SimMemory.I32.get(ram, o);
                        } else {
                            stk[sp - 1] = (int) syncLoad(pc, cyc, a, 4, false);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.LD64 -> {
                        int a = (int) stk[sp - 1];
                        if ((a & 3) != 0)
                            throw unalignedDoubleword(a, "LDRD");
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 8) {
                            stk[sp - 1] = (long) SimMemory.I64.get(ram, o);
                        } else {
                            stk[sp - 1] = syncLoad(pc, cyc, a, 8, false);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.LD64U -> {
                        int a = (int) stk[sp - 1];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 8) {
                            stk[sp - 1] = (long) SimMemory.I64.get(ram, o);
                        } else {
                            stk[sp - 1] = syncLoad(pc, cyc, a, 8, false);
                            cyc = this.cyc;
                        }
                        pc++;
                    }
                    case Op.ST8 -> {
                        long v = stk[--sp];
                        int a = (int) stk[--sp];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o < SimMemory.SRAM_SIZE) {
                            byte b = (byte) v;
                            if (ram[o] != b) {
                                ram[o] = b;
                                mem.changes++;
                            }
                        } else {
                            this.pc = pc;
                            mem.write8(a & 0xFFFFFFFFL, (int) v);
                        }
                        pc++;
                    }
                    case Op.ST16 -> {
                        long v = stk[--sp];
                        int a = (int) stk[--sp];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 2) {
                            short s = (short) v;
                            if ((short) SimMemory.I16.get(ram, o) != s) {
                                SimMemory.I16.set(ram, o, s);
                                mem.changes++;
                            }
                        } else {
                            this.pc = pc;
                            mem.write16(a & 0xFFFFFFFFL, (int) v);
                        }
                        pc++;
                    }
                    case Op.ST32 -> {
                        long v = stk[--sp];
                        int a = (int) stk[--sp];
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 4) {
                            int iv = (int) v;
                            if ((int) SimMemory.I32.get(ram, o) != iv) {
                                SimMemory.I32.set(ram, o, iv);
                                mem.changes++;
                            }
                        } else {
                            this.pc = pc;
                            mem.write32(a & 0xFFFFFFFFL, (int) v);
                        }
                        pc++;
                    }
                    case Op.ST64, Op.ST64U -> {
                        long v = stk[--sp];
                        int a = (int) stk[--sp];
                        if ((a & 3) != 0 && (w & 0xFF) == Op.ST64) {
                            this.pc = pc;
                            throw unalignedDoubleword(a, "STRD");
                        }
                        int o = a - SimMemory.SRAM_BASE;
                        if (o >= 0 && o <= SimMemory.SRAM_SIZE - 8) {
                            if ((long) SimMemory.I64.get(ram, o) != v) {
                                SimMemory.I64.set(ram, o, v);
                                mem.changes++;
                            }
                        } else {
                            this.pc = pc;
                            mem.write64(a & 0xFFFFFFFFL, v);
                        }
                        pc++;
                    }
                    case Op.LLD8U -> {
                        stk[sp++] = ram[fp + code[pc + 1]] & 0xFF;
                        pc += 2;
                    }
                    case Op.LLD8S -> {
                        stk[sp++] = ram[fp + code[pc + 1]];
                        pc += 2;
                    }
                    case Op.LLD16U -> {
                        stk[sp++] = (short) SimMemory.I16.get(ram, fp + code[pc + 1]) & 0xFFFF;
                        pc += 2;
                    }
                    case Op.LLD16S -> {
                        stk[sp++] = (short) SimMemory.I16.get(ram, fp + code[pc + 1]);
                        pc += 2;
                    }
                    case Op.LLD32 -> {
                        stk[sp++] = (int) SimMemory.I32.get(ram, fp + code[pc + 1]);
                        pc += 2;
                    }
                    case Op.LLD64 -> {
                        stk[sp++] = (long) SimMemory.I64.get(ram, fp + code[pc + 1]);
                        pc += 2;
                    }
                    case Op.LST8 -> {
                        int o = fp + code[pc + 1];
                        byte b = (byte) stk[--sp];
                        if (ram[o] != b) {
                            ram[o] = b;
                            mem.changes++;
                        }
                        pc += 2;
                    }
                    case Op.LST16 -> {
                        int o = fp + code[pc + 1];
                        short s = (short) stk[--sp];
                        if ((short) SimMemory.I16.get(ram, o) != s) {
                            SimMemory.I16.set(ram, o, s);
                            mem.changes++;
                        }
                        pc += 2;
                    }
                    case Op.LST32 -> {
                        int o = fp + code[pc + 1];
                        int v = (int) stk[--sp];
                        if ((int) SimMemory.I32.get(ram, o) != v) {
                            SimMemory.I32.set(ram, o, v);
                            mem.changes++;
                        }
                        pc += 2;
                    }
                    case Op.LST64 -> {
                        int o = fp + code[pc + 1];
                        long v = stk[--sp];
                        if ((long) SimMemory.I64.get(ram, o) != v) {
                            SimMemory.I64.set(ram, o, v);
                            mem.changes++;
                        }
                        pc += 2;
                    }
                    case Op.LINC -> {
                        int o = fp + code[pc + 1];
                        int v = (int) SimMemory.I32.get(ram, o) + code[pc + 2];
                        SimMemory.I32.set(ram, o, v);
                        if (code[pc + 2] != 0)
                            mem.changes++;
                        pc += 3;
                    }
                    case Op.POP -> {
                        sp--;
                        pc++;
                    }
                    case Op.DUP -> {
                        stk[sp] = stk[sp - 1];
                        sp++;
                        pc++;
                    }
                    case Op.DUPX1 -> {
                        // a b -> b a b
                        long b = stk[sp - 1];
                        stk[sp] = b;
                        stk[sp - 1] = stk[sp - 2];
                        stk[sp - 2] = b;
                        sp++;
                        pc++;
                    }
                    case Op.SWAP -> {
                        long t = stk[sp - 1];
                        stk[sp - 1] = stk[sp - 2];
                        stk[sp - 2] = t;
                        pc++;
                    }
                    // ---- 32-bit integer ----
                    case Op.ADD -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] + (int) stk[sp];
                        pc++;
                    }
                    case Op.SUB -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] - (int) stk[sp];
                        pc++;
                    }
                    case Op.MUL -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] * (int) stk[sp];
                        pc++;
                    }
                    case Op.SDIV, Op.SREM -> {
                        sp--;
                        int a = (int) stk[sp - 1], b = (int) stk[sp];
                        int d = divCycles(a < 0 ? -a : a, b < 0 ? -b : b);
                        acc[DIVC] += d;
                        cyc += d;
                        int q = b == 0 ? 0 : (b == -1 ? -a : a / b);
                        if ((w & 0xFF) == Op.SDIV) {
                            stk[sp - 1] = q;
                        } else {
                            acc[MULC] += 1;
                            cyc += 1;
                            stk[sp - 1] = a - q * b;
                        }
                        pc++;
                    }
                    case Op.UDIV, Op.UREM -> {
                        sp--;
                        int a = (int) stk[sp - 1], b = (int) stk[sp];
                        int d = divCycles(a, b);
                        acc[DIVC] += d;
                        cyc += d;
                        int q = b == 0 ? 0 : Integer.divideUnsigned(a, b);
                        if ((w & 0xFF) == Op.UDIV) {
                            stk[sp - 1] = q;
                        } else {
                            acc[MULC] += 1;
                            cyc += 1;
                            stk[sp - 1] = a - q * b;
                        }
                        pc++;
                    }
                    case Op.AND -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] & (int) stk[sp];
                        pc++;
                    }
                    case Op.OR -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] | (int) stk[sp];
                        pc++;
                    }
                    case Op.XOR -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] ^ (int) stk[sp];
                        pc++;
                    }
                    case Op.SHL -> {
                        // LSL by register: the bottom byte of the amount; 32..255 shift everything out.
                        sp--;
                        int n = (int) stk[sp] & 0xFF;
                        stk[sp - 1] = n >= 32 ? 0 : (int) stk[sp - 1] << n;
                        pc++;
                    }
                    case Op.SAR -> {
                        sp--;
                        int n = (int) stk[sp] & 0xFF;
                        stk[sp - 1] = (int) stk[sp - 1] >> Math.min(n, 31);
                        pc++;
                    }
                    case Op.SHR -> {
                        sp--;
                        int n = (int) stk[sp] & 0xFF;
                        stk[sp - 1] = n >= 32 ? 0 : (int) stk[sp - 1] >>> n;
                        pc++;
                    }
                    case Op.NEG -> {
                        stk[sp - 1] = -(int) stk[sp - 1];
                        pc++;
                    }
                    case Op.NOT -> {
                        stk[sp - 1] = ~(int) stk[sp - 1];
                        pc++;
                    }
                    case Op.ADDI -> {
                        stk[sp - 1] = (int) stk[sp - 1] + code[pc + 1];
                        pc += 2;
                    }
                    case Op.EQ -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] == (int) stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.NE -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] != (int) stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LT -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] < (int) stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LE -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] <= (int) stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.GT -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] > (int) stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.GE -> {
                        sp--;
                        stk[sp - 1] = (int) stk[sp - 1] >= (int) stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.ULT -> {
                        sp--;
                        stk[sp - 1] = Integer.compareUnsigned((int) stk[sp - 1], (int) stk[sp]) < 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.ULE -> {
                        sp--;
                        stk[sp - 1] = Integer.compareUnsigned((int) stk[sp - 1], (int) stk[sp]) <= 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.UGT -> {
                        sp--;
                        stk[sp - 1] = Integer.compareUnsigned((int) stk[sp - 1], (int) stk[sp]) > 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.UGE -> {
                        sp--;
                        stk[sp - 1] = Integer.compareUnsigned((int) stk[sp - 1], (int) stk[sp]) >= 0 ? 1 : 0;
                        pc++;
                    }
                    // ---- 64-bit integer ----
                    case Op.LADD -> {
                        sp--;
                        stk[sp - 1] += stk[sp];
                        pc++;
                    }
                    case Op.LSUB -> {
                        sp--;
                        stk[sp - 1] -= stk[sp];
                        pc++;
                    }
                    case Op.LMUL -> {
                        sp--;
                        stk[sp - 1] *= stk[sp];
                        pc++;
                    }
                    case Op.LSDIV, Op.LSREM -> {
                        sp--;
                        long a = stk[sp - 1], b = stk[sp];
                        int d = ldivCycles(Math.abs(a), Math.abs(b));
                        acc[DIVC] += d;
                        cyc += d;
                        // 64-bit division is libgcc's __aeabi_ldivmod, not SDIV: on a zero
                        // divisor its test_div_by_zero (bpabi.S) saturates the quotient by the
                        // dividend's sign and leaves the zero divisor in r2:r3, which is the
                        // remainder GCC reads for %.
                        long q = b == 0 ? (a > 0 ? Long.MAX_VALUE : a < 0 ? Long.MIN_VALUE : 0) : (b == -1 ? -a : a / b);
                        stk[sp - 1] = (w & 0xFF) == Op.LSDIV ? q : b == 0 ? 0 : a - q * b;
                        pc++;
                    }
                    case Op.LUDIV, Op.LUREM -> {
                        sp--;
                        long a = stk[sp - 1], b = stk[sp];
                        int d = ldivCycles(a, b);
                        acc[DIVC] += d;
                        cyc += d;
                        // __aeabi_uldivmod: all ones for a non-zero dividend over zero, remainder 0.
                        long q = b == 0 ? (a != 0 ? -1L : 0) : Long.divideUnsigned(a, b);
                        stk[sp - 1] = (w & 0xFF) == Op.LUDIV ? q : b == 0 ? 0 : a - q * b;
                        pc++;
                    }
                    case Op.LAND -> {
                        sp--;
                        stk[sp - 1] &= stk[sp];
                        pc++;
                    }
                    case Op.LOR -> {
                        sp--;
                        stk[sp - 1] |= stk[sp];
                        pc++;
                    }
                    case Op.LXOR -> {
                        sp--;
                        stk[sp - 1] ^= stk[sp];
                        pc++;
                    }
                    case Op.LSHL -> {
                        sp--;
                        int n = (int) stk[sp] & 0xFF;
                        stk[sp - 1] = n >= 64 ? 0 : stk[sp - 1] << n;
                        pc++;
                    }
                    case Op.LSAR -> {
                        sp--;
                        int n = (int) stk[sp] & 0xFF;
                        stk[sp - 1] = stk[sp - 1] >> Math.min(n, 63);
                        pc++;
                    }
                    case Op.LSHR -> {
                        sp--;
                        int n = (int) stk[sp] & 0xFF;
                        stk[sp - 1] = n >= 64 ? 0 : stk[sp - 1] >>> n;
                        pc++;
                    }
                    case Op.LNEG -> {
                        stk[sp - 1] = -stk[sp - 1];
                        pc++;
                    }
                    case Op.LNOT -> {
                        stk[sp - 1] = ~stk[sp - 1];
                        pc++;
                    }
                    case Op.LEQ -> {
                        sp--;
                        stk[sp - 1] = stk[sp - 1] == stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LNE -> {
                        sp--;
                        stk[sp - 1] = stk[sp - 1] != stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LLT -> {
                        sp--;
                        stk[sp - 1] = stk[sp - 1] < stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LLE -> {
                        sp--;
                        stk[sp - 1] = stk[sp - 1] <= stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LGT -> {
                        sp--;
                        stk[sp - 1] = stk[sp - 1] > stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LGE -> {
                        sp--;
                        stk[sp - 1] = stk[sp - 1] >= stk[sp] ? 1 : 0;
                        pc++;
                    }
                    case Op.LULT -> {
                        sp--;
                        stk[sp - 1] = Long.compareUnsigned(stk[sp - 1], stk[sp]) < 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.LULE -> {
                        sp--;
                        stk[sp - 1] = Long.compareUnsigned(stk[sp - 1], stk[sp]) <= 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.LUGT -> {
                        sp--;
                        stk[sp - 1] = Long.compareUnsigned(stk[sp - 1], stk[sp]) > 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.LUGE -> {
                        sp--;
                        stk[sp - 1] = Long.compareUnsigned(stk[sp - 1], stk[sp]) >= 0 ? 1 : 0;
                        pc++;
                    }
                    // ---- float (IEEE single, as libgcc's soft-float computes it) ----
                    case Op.FADD -> {
                        sp--;
                        stk[sp - 1] = fbits(f(stk[sp - 1]) + f(stk[sp]));
                        pc++;
                    }
                    case Op.FSUB -> {
                        sp--;
                        stk[sp - 1] = fbits(f(stk[sp - 1]) - f(stk[sp]));
                        pc++;
                    }
                    case Op.FMUL -> {
                        sp--;
                        stk[sp - 1] = fbits(f(stk[sp - 1]) * f(stk[sp]));
                        pc++;
                    }
                    case Op.FDIV -> {
                        sp--;
                        stk[sp - 1] = fbits(f(stk[sp - 1]) / f(stk[sp]));
                        pc++;
                    }
                    case Op.FNEG -> {
                        stk[sp - 1] = (int) stk[sp - 1] ^ 0x80000000;
                        pc++;
                    }
                    case Op.FEQ -> {
                        sp--;
                        stk[sp - 1] = f(stk[sp - 1]) == f(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.FNE -> {
                        sp--;
                        stk[sp - 1] = f(stk[sp - 1]) != f(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.FLT -> {
                        sp--;
                        stk[sp - 1] = f(stk[sp - 1]) < f(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.FLE -> {
                        sp--;
                        stk[sp - 1] = f(stk[sp - 1]) <= f(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.FGT -> {
                        sp--;
                        stk[sp - 1] = f(stk[sp - 1]) > f(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.FGE -> {
                        sp--;
                        stk[sp - 1] = f(stk[sp - 1]) >= f(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    // ---- double ----
                    case Op.DADD -> {
                        sp--;
                        stk[sp - 1] = Double.doubleToRawLongBits(d(stk[sp - 1]) + d(stk[sp]));
                        pc++;
                    }
                    case Op.DSUB -> {
                        sp--;
                        stk[sp - 1] = Double.doubleToRawLongBits(d(stk[sp - 1]) - d(stk[sp]));
                        pc++;
                    }
                    case Op.DMUL -> {
                        sp--;
                        stk[sp - 1] = Double.doubleToRawLongBits(d(stk[sp - 1]) * d(stk[sp]));
                        pc++;
                    }
                    case Op.DDIV -> {
                        sp--;
                        stk[sp - 1] = Double.doubleToRawLongBits(d(stk[sp - 1]) / d(stk[sp]));
                        pc++;
                    }
                    case Op.DNEG -> {
                        stk[sp - 1] ^= Long.MIN_VALUE;
                        pc++;
                    }
                    case Op.DEQ -> {
                        sp--;
                        stk[sp - 1] = d(stk[sp - 1]) == d(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.DNE -> {
                        sp--;
                        stk[sp - 1] = d(stk[sp - 1]) != d(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.DLT -> {
                        sp--;
                        stk[sp - 1] = d(stk[sp - 1]) < d(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.DLE -> {
                        sp--;
                        stk[sp - 1] = d(stk[sp - 1]) <= d(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.DGT -> {
                        sp--;
                        stk[sp - 1] = d(stk[sp - 1]) > d(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    case Op.DGE -> {
                        sp--;
                        stk[sp - 1] = d(stk[sp - 1]) >= d(stk[sp]) ? 1 : 0;
                        pc++;
                    }
                    // ---- conversions ----
                    case Op.TRUNC8U -> {
                        stk[sp - 1] = stk[sp - 1] & 0xFF;
                        pc++;
                    }
                    case Op.TRUNC8S -> {
                        stk[sp - 1] = (byte) stk[sp - 1];
                        pc++;
                    }
                    case Op.TRUNC16U -> {
                        stk[sp - 1] = stk[sp - 1] & 0xFFFF;
                        pc++;
                    }
                    case Op.TRUNC16S -> {
                        stk[sp - 1] = (short) stk[sp - 1];
                        pc++;
                    }
                    case Op.I2L -> {
                        stk[sp - 1] = (int) stk[sp - 1];
                        pc++;
                    }
                    case Op.U2L -> {
                        stk[sp - 1] = stk[sp - 1] & 0xFFFFFFFFL;
                        pc++;
                    }
                    case Op.L2I -> {
                        stk[sp - 1] = (int) stk[sp - 1];
                        pc++;
                    }
                    case Op.I2F -> {
                        stk[sp - 1] = fbits((float) (int) stk[sp - 1]);
                        pc++;
                    }
                    case Op.U2F -> {
                        stk[sp - 1] = fbits((float) (stk[sp - 1] & 0xFFFFFFFFL));
                        pc++;
                    }
                    case Op.L2F -> {
                        stk[sp - 1] = fbits((float) stk[sp - 1]);
                        pc++;
                    }
                    case Op.UL2F -> {
                        stk[sp - 1] = fbits((float) SemaBase.intToFloat(stk[sp - 1], CType.ULLONG, true));
                        pc++;
                    }
                    case Op.I2D -> {
                        stk[sp - 1] = Double.doubleToRawLongBits((int) stk[sp - 1]);
                        pc++;
                    }
                    case Op.U2D -> {
                        stk[sp - 1] = Double.doubleToRawLongBits(stk[sp - 1] & 0xFFFFFFFFL);
                        pc++;
                    }
                    case Op.L2D -> {
                        stk[sp - 1] = Double.doubleToRawLongBits((double) stk[sp - 1]);
                        pc++;
                    }
                    case Op.UL2D -> {
                        stk[sp - 1] = Double.doubleToRawLongBits(SemaBase.intToFloat(stk[sp - 1], CType.ULLONG, false));
                        pc++;
                    }
                    case Op.F2I -> {
                        stk[sp - 1] = (int) f(stk[sp - 1]);
                        pc++;
                    }
                    case Op.F2U -> {
                        stk[sp - 1] = (int) SemaBase.floatToInt(f(stk[sp - 1]), CType.UINT);
                        pc++;
                    }
                    case Op.F2L -> {
                        stk[sp - 1] = (long) f(stk[sp - 1]);
                        pc++;
                    }
                    case Op.F2UL -> {
                        stk[sp - 1] = SemaBase.floatToInt(f(stk[sp - 1]), CType.ULLONG);
                        pc++;
                    }
                    case Op.D2I -> {
                        stk[sp - 1] = (int) d(stk[sp - 1]);
                        pc++;
                    }
                    case Op.D2U -> {
                        stk[sp - 1] = (int) SemaBase.floatToInt(d(stk[sp - 1]), CType.UINT);
                        pc++;
                    }
                    case Op.D2L -> {
                        stk[sp - 1] = (long) d(stk[sp - 1]);
                        pc++;
                    }
                    case Op.D2UL -> {
                        stk[sp - 1] = SemaBase.floatToInt(d(stk[sp - 1]), CType.ULLONG);
                        pc++;
                    }
                    case Op.F2D -> {
                        stk[sp - 1] = Double.doubleToRawLongBits(f(stk[sp - 1]));
                        pc++;
                    }
                    case Op.D2F -> {
                        stk[sp - 1] = fbits((float) d(stk[sp - 1]));
                        pc++;
                    }
                    case Op.TOBOOL -> {
                        stk[sp - 1] = (int) stk[sp - 1] != 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.LTOBOOL -> {
                        stk[sp - 1] = stk[sp - 1] != 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.FTOBOOL -> {
                        stk[sp - 1] = f(stk[sp - 1]) != 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.DTOBOOL -> {
                        stk[sp - 1] = d(stk[sp - 1]) != 0 ? 1 : 0;
                        pc++;
                    }
                    case Op.LNOTB -> {
                        stk[sp - 1] = stk[sp - 1] == 0 ? 1 : 0;
                        pc++;
                    }
                    // ---- control ----
                    case Op.JMP -> pc = code[pc + 1];
                    case Op.JZ -> {
                        if ((int) stk[--sp] == 0) {
                            pc = code[pc + 1];
                            acc[BRANCHC] += CodeGen.TAKEN;
                            cyc += CodeGen.TAKEN;
                        } else {
                            pc += 2;
                            acc[ALU] += 1;
                            cyc += 1;
                        }
                    }
                    case Op.JNZ -> {
                        if ((int) stk[--sp] != 0) {
                            pc = code[pc + 1];
                            acc[BRANCHC] += CodeGen.TAKEN;
                            cyc += CodeGen.TAKEN;
                        } else {
                            pc += 2;
                            acc[ALU] += 1;
                            cyc += 1;
                        }
                    }
                    case Op.LOOP -> {
                        int id = code[pc + 2];
                        pc = code[pc + 1];
                        this.pc = pc;
                        this.sp = sp;
                        this.fp = fp;
                        this.cyc = cyc;
                        idle(id, until);
                        cyc = this.cyc;
                    }
                    case Op.SWITCH -> {
                        long key = stk[--sp];
                        int n = code[pc + 1];
                        int target = code[pc + 2];
                        int lo = 0, hi = n - 1;
                        int base = pc + 3;
                        while (lo <= hi) {
                            int mid = (lo + hi) >>> 1;
                            int e = base + Op.SWITCH_ENTRY * mid;
                            long kLo = (code[e] & 0xFFFFFFFFL) | ((long) code[e + 1] << 32);
                            long kHi = (code[e + 2] & 0xFFFFFFFFL) | ((long) code[e + 3] << 32);
                            if (kHi < key) {
                                lo = mid + 1;
                            } else if (kLo > key) {
                                hi = mid - 1;
                            } else {
                                target = code[e + 4];
                                break;
                            }
                        }
                        acc[BRANCHC] += CodeGen.TAKEN;
                        cyc += CodeGen.TAKEN;
                        pc = target;
                    }
                    case Op.CALL -> {
                        this.sp = sp;
                        this.fp = fp;
                        this.cyc = cyc;
                        this.pc = pc;
                        enter(code[pc + 1], pc + 2, false);
                        pc = this.pc;
                        sp = this.sp;
                        fp = this.fp;
                        cyc = this.cyc;
                    }
                    case Op.RET, Op.RETV -> {
                        long v = (w & 0xFF) == Op.RETV ? stk[--sp] : 0;
                        this.sp = sp;
                        this.fp = fp;
                        this.cyc = cyc;
                        this.pc = pc;
                        boolean isr = leave();
                        sp = this.sp;
                        if ((w & 0xFF) == Op.RETV)
                            stk[sp++] = v;
                        pc = this.pc;
                        fp = this.fp;
                        cyc = this.cyc;
                        if (isr) {
                            this.sp = sp;
                            exitIsr();
                            cyc = this.cyc;
                            status = 2;
                            break loop;
                        }
                    }
                    case Op.CALLB -> {
                        this.sp = sp;
                        this.fp = fp;
                        this.cyc = cyc;
                        this.pc = pc + 2;
                        boolean attention = callBuiltin(img.descs[code[pc + 1]], pc);
                        pc = this.pc;
                        sp = this.sp;
                        cyc = this.cyc;
                        if (attention) {
                            status = 2;
                            break loop;
                        }
                    }
                    case Op.CALLI -> {
                        this.sp = sp;
                        this.fp = fp;
                        this.cyc = cyc;
                        this.pc = pc;
                        boolean attention = callIndirect(img.descs[code[pc + 1]], pc + 2);
                        pc = this.pc;
                        sp = this.sp;
                        fp = this.fp;
                        cyc = this.cyc;
                        if (attention) {
                            status = 2;
                            break loop;
                        }
                    }
                    case Op.INTR -> {
                        Intrinsics.Op op = intrinsicOps[code[pc + 1]];
                        int argc = code[pc + 2];
                        sp -= argc;
                        long[] args = argc <= intrArgs.length ? intrArgs : new long[argc];
                        System.arraycopy(stk, sp, args, 0, argc);
                        this.sp = sp;
                        this.ssp = this.ssp;
                        this.cyc = cyc;
                        this.pc = pc;
                        long r = rt.exec(op, args);
                        cyc = this.cyc;
                        if (!op.ret.isVoid() && !op.ret.isString())
                            stk[sp++] = r;
                        pc += 3;
                    }
                    case Op.MEMCPY -> {
                        int n = code[pc + 1];
                        long src = stk[--sp] & 0xFFFFFFFFL;
                        long dst = stk[--sp] & 0xFFFFFFFFL;
                        this.pc = pc;
                        copy(dst, src, n);
                        pc += 2;
                    }
                    // ---- GCC bit builtins (a zero argument gives the width, as CLZ does) ----
                    case Op.CLZ -> {
                        stk[sp - 1] = Integer.numberOfLeadingZeros((int) stk[sp - 1]);
                        pc++;
                    }
                    case Op.CTZ -> {
                        stk[sp - 1] = Integer.numberOfTrailingZeros((int) stk[sp - 1]);
                        pc++;
                    }
                    case Op.POPCNT -> {
                        stk[sp - 1] = Integer.bitCount((int) stk[sp - 1]);
                        acc[BRANCHC] += CodeGen.CALL_REFILL;
                        cyc += CodeGen.CALL_REFILL;
                        pc++;
                    }
                    case Op.LCLZ -> {
                        stk[sp - 1] = Long.numberOfLeadingZeros(stk[sp - 1]);
                        pc++;
                    }
                    case Op.LCTZ -> {
                        stk[sp - 1] = Long.numberOfTrailingZeros(stk[sp - 1]);
                        pc++;
                    }
                    case Op.LPOPCNT -> {
                        stk[sp - 1] = Long.bitCount(stk[sp - 1]);
                        acc[BRANCHC] += CodeGen.CALL_REFILL;
                        cyc += CodeGen.CALL_REFILL;
                        pc++;
                    }
                    // ---- variable-length arrays ----
                    case Op.ALLOCA -> {
                        long n = stk[sp - 1] & 0xFFFFFFFFL;
                        long nssp = ssp - ((n + 7) & ~7L);
                        if (nssp < 0) {
                            this.pc = pc;
                            throw new HardFault(HardFault.Kind.STACK_OVERFLOW, SimMemory.SRAM_BASE + nssp,
                                    "stack overflow: a variable-length array of " + n + " bytes below 0x"
                                            + Long.toHexString(SimMemory.SRAM_BASE + (long) ssp));
                        }
                        ssp = (int) nssp;
                        if (ssp < minSsp)
                            minSsp = ssp;
                        stk[sp - 1] = SimMemory.SRAM_BASE + ssp;
                        pc++;
                    }
                    case Op.GETSP -> {
                        stk[sp++] = SimMemory.SRAM_BASE + ssp;
                        pc++;
                    }
                    case Op.SETSP -> {
                        ssp = (int) (stk[--sp] - SimMemory.SRAM_BASE);
                        pc++;
                    }
                    case Op.MEMZERO -> {
                        int n = code[pc + 1];
                        long dst = stk[--sp] & 0xFFFFFFFFL;
                        this.pc = pc;
                        mem.fill(dst, 0, n);
                        pc += 2;
                    }
                    default -> throw new IllegalStateException("bad opcode " + (w & 0xFF) + " at " + pc);
                }
            }
        } catch (HardFault f) {
            this.pc = pc;
            this.sp = sp;
            this.cyc = cyc;
            instructions += count;
            throw f;
        }
        this.pc = pc;
        this.sp = sp;
        this.fp = fp;
        this.cyc = cyc;
        instructions += count;
        return status;
    }

    /** LDRD/STRD to an address that is not word aligned: a UsageFault, escalated to HardFault. */
    private static HardFault unalignedDoubleword(int a, String insn) {
        return new HardFault(HardFault.Kind.USAGE_FAULT, a & 0xFFFFFFFFL,
                "unaligned " + insn + " (64-bit access) at 0x" + Long.toHexString(a & 0xFFFFFFFFL));
    }

    private long syncLoad(int pc, long cyc, int a, int n, boolean signed) {
        this.pc = pc;
        this.cyc = cyc;
        return loadSlow(a, n, signed);
    }

    private void copy(long dst, long src, int n) {
        int so = SimMemory.sramOffset(src, n);
        if (so < 0 && SimMemory.sramOffset(src, 1) < 0) {
            // From flash (a const struct, a string literal): every word waits for the flash.
            long before = mem.flashReads;
            mem.move(dst, src, n);
            long waits = 2L * ((n + 3) / 4);
            if (mem.flashReads != before) {
                acc[LOADC] += waits;
                cyc += waits;
            }
            return;
        }
        mem.move(dst, src, n);
    }

    private static float f(long bits) {
        return Float.intBitsToFloat((int) bits);
    }

    private static long fbits(float v) {
        return Float.floatToRawIntBits(v);
    }

    private static double d(long bits) {
        return Double.longBitsToDouble(bits);
    }

    // ================= idle loops =================

    /**
     * At a backward branch (loop or backward goto): if the iteration since the last
     * visit changed nothing, skip whole further iterations. Inside an interrupt
     * handler this works the same way: handlers do not nest here (as STM32duino's
     * EXTI priorities are equal), so a pending interrupt cannot end a loop in a
     * handler early and is no reason not to skip; the bound is still the next
     * external event and any clock resolution the loop reads.
     */
    private void idle(int id, long until) {
        if (snapValid[id] && snapRun[id] == runEpoch && snapChanges[id] == mem.changes && snapSide[id] == sideEffects
                && snapIrq[id] == irqTaken && snapSp[id] == sp && snapFp[id] == fp && snapDepth[id] == depth) {
            long len = cyc - snapCycle[id];
            if (len > 0 && !(pendingCount > 0 && irqEnabled && !inIsr)) {
                long bound = until;
                long ev = env.nextEventCycle();
                if (ev > cyc && ev < bound)
                    bound = ev;
                boolean ok = ev > cyc;
                long start = snapCycle[id];
                for (int s = 0; s < resValue.length && ok; s++) {
                    if (resSeq[s] > snapTime[id]) {
                        long res = resValue[s];
                        if (Math.floorDiv(start, res) != Math.floorDiv(cyc, res)) {
                            ok = false;
                        } else {
                            long next = (Math.floorDiv(cyc, res) + 1) * res;
                            if (next < bound)
                                bound = next;
                        }
                    }
                }
                if (ok && fastForward) {
                    long n = (bound - cyc) / len;
                    if (n > 0) {
                        // The skipped iterations are the modelled one again, so they cost
                        // what it cost, class by class: a polling loop is the core running
                        // flat out from flash, not sleeping.
                        long[] snap = snapClass[id];
                        long rest = len;
                        for (int k = 0; k < acc.length; k++) {
                            long dk = ledger.cycles(CLASSES[k]) + acc[k] - snap[k];
                            if (dk > 0 && dk <= rest) {
                                acc[k] += n * dk;
                                rest -= dk;
                            }
                        }
                        // Anything not accounted to a class (never, with a consistent ledger) is waiting.
                        acc[WAITC] += n * rest;
                        long skip = n * len;
                        cyc += skip;
                        skippedIterations += n;
                        skippedCycles += skip;
                    }
                }
            }
            snapCycle[id] = cyc;
            snapTime[id] = timeReadSeq;
            snapClasses(id);
            return;
        }
        snapValid[id] = true;
        snapRun[id] = runEpoch;
        snapChanges[id] = mem.changes;
        snapSide[id] = sideEffects;
        snapIrq[id] = irqTaken;
        snapSp[id] = sp;
        snapFp[id] = fp;
        snapDepth[id] = depth;
        snapCycle[id] = cyc;
        snapTime[id] = timeReadSeq;
        snapClasses(id);
    }

    private void snapClasses(int id) {
        long[] snap = snapClass[id];
        for (int k = 0; k < acc.length; k++)
            snap[k] = ledger.cycles(CLASSES[k]) + acc[k];
    }

    private void noteTimeRead(long resolution) {
        if (resolution <= 0)
            return;
        timeReadSeq++;
        int free = -1;
        for (int i = 0; i < resValue.length; i++) {
            if (resValue[i] == resolution) {
                resSeq[i] = timeReadSeq;
                return;
            }
            if (resValue[i] == 0 && free < 0)
                free = i;
        }
        if (free < 0) {
            // Keep the finest resolutions.
            int coarsest = 0;
            for (int i = 1; i < resValue.length; i++)
                if (resValue[i] > resValue[coarsest])
                    coarsest = i;
            if (resValue[coarsest] < resolution) {
                // Coarser than all we track: record it as the coarsest slot's value would be lost; be conservative.
                sideEffects++;
                return;
            }
            free = coarsest;
        }
        resValue[free] = resolution;
        resSeq[free] = timeReadSeq;
    }

    // ================= builtins =================

    private boolean callBuiltin(CodeGen.CallDesc desc, int callPc) {
        int argc = desc.argc;
        VmInvocation inv = inIsr ? isrInv : mainInv;
        sp -= argc;
        inv.prepare(desc, stk, sp, argc, lineAt(callPc));
        flush();
        desc.builtin.impl.invoke(inv);
        cyc = ledger.now();
        if (inv.blocker != null) {
            parked = inv.blocker;
            parkedInv = inv;
            parkedDesc = desc;
            inv.blocker = null;
            return true;
        }
        pushResult(desc, inv);
        return pendingCount > 0 && irqEnabled && !inIsr;
    }

    private void pushResult(CodeGen.CallDesc desc, VmInvocation inv) {
        if (!desc.returnsValue)
            return;
        stk[sp++] = normalizeReturn(desc.ret, inv.ret);
    }

    static long normalizeReturn(CType t, long v) {
        CType u = t.isEnum() ? t.base : t;
        switch (u.kind) {
            case BOOL:
                return v != 0 ? 1 : 0;
            case CHAR, UCHAR:
                return v & 0xFF;
            case SCHAR:
                return (byte) v;
            case SHORT:
                return (short) v;
            case USHORT:
                return v & 0xFFFF;
            case LLONG, ULLONG, DOUBLE, LDOUBLE:
                return v;
            case FLOAT:
                return (int) v;
            default:
                return (int) v;
        }
    }

    static long normalizeArg(CType t, long v) {
        if (t == null)
            return v;
        CType u = t.isEnum() ? t.base : t;
        switch (CodeGen.kind(u)) {
            case CodeGen.K_I64, CodeGen.K_F64:
                return v;
            case CodeGen.K_F32:
                return v & 0xFFFFFFFFL;
            default:
                return u.isUnsigned() || u.isPointer() || u.kind == CType.Kind.NULLPTR ? v & 0xFFFFFFFFL : (int) v;
        }
    }

    /** A call through a function pointer: to a sketch function or a builtin. */
    private boolean callIndirect(CodeGen.CallDesc desc, int retPc) {
        int argc = desc.argc;
        long target = stk[sp - argc - 1];
        int f = img.functionAt(target);
        if (f >= 0) {
            boolean sameShape = img.fnParamOff[f].length == argc && img.fnReturnsValue[f] == desc.returnsValue;
            if (!sameShape)
                throw new HardFault(HardFault.Kind.USAGE_FAULT, target & 0xFFFFFFFFL,
                        "call through a function pointer of the wrong type (" + img.fnName[f] + ")");
            // Drop the pointer from beneath the arguments.
            System.arraycopy(stk, sp - argc, stk, sp - argc - 1, argc);
            sp--;
            enter(f, retPc, false);
            return false;
        }
        int b = img.builtinAt(target);
        if (b >= 0) {
            Sym.Builtin sb = img.builtins[b];
            if (sb.type.params.size() != argc && !sb.type.variadic)
                throw new HardFault(HardFault.Kind.USAGE_FAULT, target & 0xFFFFFFFFL, "call through a function pointer of the wrong type");
            System.arraycopy(stk, sp - argc, stk, sp - argc - 1, argc);
            sp--;
            CodeGen.CallDesc d = new CodeGen.CallDesc();
            d.builtin = sb;
            d.argc = argc;
            d.argTypes = new CType[argc];
            for (int i = 0; i < argc; i++)
                d.argTypes[i] = i < sb.type.params.size() ? sb.type.params.get(i) : desc.argTypes[i];
            d.ret = sb.type.base;
            d.returnsValue = desc.returnsValue;
            this.pc = retPc;
            return callBuiltin(d, retPc - 2);
        }
        throw new HardFault(HardFault.Kind.USAGE_FAULT, target & 0xFFFFFFFFL,
                target == 0 ? "call through a null function pointer" : "call to 0x" + Long.toHexString(target & 0xFFFFFFFFL) + ", which is not a function");
    }

    /** What a builtin sees of its call. */
    private final class VmInvocation implements Invocation {
        /**
         * The converted arguments. It starts at 16 slots and grows to fit the widest
         * call seen: a printf-family call can pass any number of varargs, and on the
         * board those are just more words on the stack.
         */
        long[] args = new long[16];
        int argc;
        long ret;
        Blocker blocker;
        int line;

        void prepare(CodeGen.CallDesc desc, long[] stk, int base, int n, int line) {
            if (n > args.length)
                args = new long[Math.max(n, args.length * 2)];
            for (int i = 0; i < n; i++)
                args[i] = normalizeArg(desc.argTypes[i], stk[base + i]);
            argc = n;
            ret = 0;
            blocker = null;
            this.line = line;
        }

        @Override
        public int argCount() {
            return argc;
        }

        @Override
        public long arg(int index) {
            return args[index];
        }

        @Override
        public float argFloat(int index) {
            return Float.intBitsToFloat((int) args[index]);
        }

        @Override
        public double argDouble(int index) {
            return Double.longBitsToDouble(args[index]);
        }

        @Override
        public void returnValue(long value) {
            ret = value;
        }

        @Override
        public void returnFloat(float value) {
            ret = Float.floatToRawIntBits(value) & 0xFFFFFFFFL;
        }

        @Override
        public void returnDouble(double value) {
            ret = Double.doubleToRawLongBits(value);
        }

        @Override
        public Memory memory() {
            return mem;
        }

        @Override
        public CycleLedger ledger() {
            return ledger;
        }

        @Override
        public McuEnvironment environment() {
            return env;
        }

        @Override
        public void charge(CycleClass cls, long cycles) {
            ledger.charge(inIsr ? CycleClass.INTERRUPT : cls, cycles);
        }

        @Override
        public void block(Blocker b) {
            blocker = b;
        }

        @Override
        public void noteTimeRead(long resolutionCycles) {
            Vm.this.noteTimeRead(resolutionCycles);
        }

        @Override
        public void noteSideEffect() {
            sideEffects++;
        }

        @Override
        public void setInterruptsEnabled(boolean enabled) {
            if (enabled != irqEnabled)
                sideEffects++;
            irqEnabled = enabled;
        }

        @Override
        public boolean interruptsEnabled() {
            return irqEnabled;
        }

        @Override
        public int sourceLine() {
            return line;
        }
    }
}
