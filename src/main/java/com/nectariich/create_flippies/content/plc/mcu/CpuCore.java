package com.nectariich.create_flippies.content.plc.mcu;

/**
 * The Cortex-M3 running one image: registers, SRAM and the call stack, plus
 * interrupt dispatch. It knows nothing about pins; the chip drives it.
 */
public interface CpuCore {

    /** Why {@link #run} came back. */
    enum Exit {
        /** Reached the cycle it was asked to run to. */
        REACHED,
        /** The host-time deadline passed first: emulated time is running behind. */
        DEADLINE,
        /** The CPU is in its HardFault loop; see {@link #fault()}. */
        HALTED
    }

    /**
     * Cold start: .data copied, .bss zeroed, static constructors run, heap and
     * stack set up, then setup() once and loop() forever, as STM32duino's main()
     * does. Interrupts enabled, nothing pending, nothing parked.
     */
    void reset();

    /**
     * Executes until the ledger reaches {@code untilCycle}, the CPU halts, or
     * {@code System.nanoTime()} passes {@code hostDeadlineNanos}. Must never
     * overshoot {@code untilCycle} by more than one instruction or builtin.
     */
    Exit run(long untilCycle, long hostDeadlineNanos);

    boolean halted();

    /** The fault that halted the CPU, or null. */
    HardFault fault();

    /** The sketch line executing now, or that faulted; 0 if unknown. */
    int currentLine();

    /**
     * Queues interrupt {@code irq} (an EXTI line, a timer) whose handler is the
     * function pointer value {@code handler} as the sketch passed it. Taken at the
     * next instruction boundary while interrupts are enabled, including while a
     * {@link Blocker} is parked, and runs to completion; handlers do not nest.
     * Pending the same irq twice before it runs keeps one, as the NVIC does.
     */
    void pendInterrupt(int irq, long handler);

    boolean interruptsEnabled();

    Memory memory();

    /** Peak bytes of stack used since reset. */
    int stackPeak();

    /** Peak bytes of heap in use since reset (malloc/new/String buffers). */
    int heapPeak();
}
