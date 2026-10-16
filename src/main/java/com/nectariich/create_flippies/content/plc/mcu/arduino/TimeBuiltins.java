package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103;

/**
 * Time, as STM32duino keeps it: millis() is HAL's uwTick, counted by the 1 kHz
 * SysTick interrupt; micros() adds the SysTick down-counter's progress through the
 * current millisecond; delay() spins on millis(); delayMicroseconds() spins on the
 * DWT cycle counter. So millis() stands still while interrupts are masked (one tick
 * is caught up when they come back), a delay() with interrupts masked never ends, and
 * delayMicroseconds() does not care — all as on the chip.
 */
final class TimeBuiltins {

    static void register(List<Fn> out) {
        out.add(new Fn("unsigned long millis(void)", 0, c -> {
            Cost.call(c);
            c.charge(CycleClass.LOAD, 2);
            Stm32f103 chip = CoreState.chip(c);
            c.noteTimeRead(Stm32f103.CYCLES_PER_MS);
            c.returnValue(chip.uwTick() & 0xFFFF_FFFFL);
        }));
        out.add(new Fn("unsigned long micros(void)", 80, c -> {
            chargeMicros(c);
            c.noteTimeRead(72);
            c.returnValue(micros(CoreState.chip(c)));
        }));
        out.add(new Fn("void delay(unsigned long ms)", 40, TimeBuiltins::delay));
        out.add(new Fn("void delayMicroseconds(unsigned long us)", 40, TimeBuiltins::delayMicroseconds));
        out.add(new Fn("void yield(void)", 0, Cost::call));

        // the HAL and core functions millis()/micros()/delay() are made of, which sketches
        // written for STM32duino call directly
        Fn.Body tick = c -> {
            Cost.call(c);
            c.charge(CycleClass.LOAD, 2);
            c.noteTimeRead(Stm32f103.CYCLES_PER_MS);
            c.returnValue(CoreState.chip(c).uwTick() & 0xFFFF_FFFFL);
        };
        out.add(new Fn("unsigned long HAL_GetTick(void)", 0, tick));
        out.add(new Fn("unsigned long getCurrentMillis(void)", 0, tick));
        out.add(new Fn("unsigned long getCurrentMicros(void)", 60, c -> {
            chargeMicros(c);
            c.noteTimeRead(72);
            c.returnValue(micros(CoreState.chip(c)));
        }));
        out.add(new Fn("void HAL_Delay(unsigned long ms)", 0, TimeBuiltins::halDelay));
    }

    /**
     * HAL_Delay: unlike delay(), it adds one tick to the wait (uwTickFreq) unless the
     * wait is HAL_MAX_DELAY, so HAL_Delay(0) still waits for the next tick, and
     * HAL_Delay(n) lasts between n and n + 1 ms.
     */
    private static void halDelay(Invocation c) {
        Cost.call(c);
        Cost.call(c); // HAL_GetTick
        c.charge(CycleClass.ALU, 4);
        long ms = c.arg(0) & 0xFFFF_FFFFL;
        if (ms < 0xFFFF_FFFFL)
            ms++;
        Stm32f103 chip = CoreState.chip(c);
        long start = chip.uwTick() & 0xFFFF_FFFFL;
        c.block(new Delay(chip, start, ms));
    }

    /**
     * getCurrentMicros: reads COUNTFLAG, uwTick, LOAD and VAL (private peripheral bus),
     * re-reads if the counter wrapped, then {@code m * 1000 + u * 1000 / (LOAD + 1)}.
     */
    static void chargeMicros(Invocation c) {
        Cost.call(c);
        Cost.call(c); // HAL_GetTick inside
        Cost.mix(c, 12, 4, 0, 2, 8);
        c.charge(CycleClass.MUL, 2);
        c.charge(CycleClass.DIV, 6);
    }

    /** What getCurrentMicros returns now, uint32. */
    static long micros(Stm32f103 chip) {
        long m = chip.uwTick() & 0xFFFF_FFFFL;
        long u = chip.sysTickElapsed() + 1;
        return (m * 1000 + u * 1000 / Stm32f103.CYCLES_PER_MS) & 0xFFFF_FFFFL;
    }

    private static void delay(Invocation c) {
        Cost.call(c);
        c.charge(CycleClass.ALU, 2);
        long ms = c.arg(0) & 0xFFFF_FFFFL;
        if (ms == 0)
            return;
        Stm32f103 chip = CoreState.chip(c);
        Cost.call(c);
        long start = chip.uwTick() & 0xFFFF_FFFFL;
        c.block(new Delay(chip, start, ms));
    }

    /**
     * {@code do { yield(); } while (getCurrentMillis() - start < ms);} — finished on the
     * first look after uwTick has moved {@code ms} past where it started.
     */
    static final class Delay implements Blocker {

        private final Stm32f103 chip;
        private final long start;
        private final long ms;

        Delay(Stm32f103 chip, long start, long ms) {
            this.chip = chip;
            this.start = start;
            this.ms = ms;
        }

        @Override
        public long nextPollCycle() {
            if (!chip.interruptsEnabled())
                return Long.MAX_VALUE;
            // does not catch up (that would charge cycles); estimates from the last caught-up tick
            long now = chip.now();
            long next = chip.peekNextTick();
            long ticks = chip.peekUwTick() & 0xFFFF_FFFFL;
            long left = ms - ((ticks - start) & 0xFFFF_FFFFL);
            if (left <= 0)
                return now;
            return Math.max(now, next + (left - 1) * Stm32f103.CYCLES_PER_MS);
        }

        @Override
        public boolean poll(Invocation call) {
            long ticks = chip.uwTick() & 0xFFFF_FFFFL;
            return ((ticks - start) & 0xFFFF_FFFFL) >= ms;
        }
    }

    /**
     * The DWT loop: {@code cycles = us * 72} as a signed 32-bit count, then spin while
     * {@code CYCCNT - start < cycles}. A count that overflows to negative returns at once.
     */
    private static void delayMicroseconds(Invocation c) {
        c.charge(CycleClass.ALU, 4);
        c.charge(CycleClass.MUL, 1);
        c.charge(CycleClass.PERIPHERAL, 2);
        int cycles = (int) ((c.arg(0) & 0xFFFF_FFFFL) * 72);
        if (cycles <= 0)
            return;
        Stm32f103 chip = CoreState.chip(c);
        long end = chip.now() + cycles;
        c.block(new Blocker() {
            @Override
            public long nextPollCycle() {
                return end;
            }

            @Override
            public boolean poll(Invocation call) {
                return call.ledger().now() >= end;
            }
        });
    }

    private TimeBuiltins() {}
}
