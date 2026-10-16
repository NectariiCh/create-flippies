package com.nectariich.create_flippies.content.plc.mcu.arduino;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;

/**
 * Charging what a core function costs on the chip.
 *
 * The figures throughout this package are estimates of what arm-none-eabi-gcc -Os
 * makes of STM32duino's source, counted the Cortex-M3 way (TRM timings): 1 cycle per
 * data-processing op, 2 per load (+2 flash wait states for constant tables the
 * prefetcher cannot hide), 1–2 per store, 3 per taken branch, BL + BX LR + the
 * push/pop of a small frame around every real call, and 2–4 per APB peripheral access
 * through the bus bridge. They are split by {@link CycleClass} so the power model
 * prices them for what they are.
 */
final class Cost {

    /**
     * BL, BX LR and a PUSH/POP of {r4, lr}: what calling a small out-of-line function adds.
     * Each of the two taken branches is 1 + P with a refill of 2, plus the two flash wait
     * states the refill pays at 72 MHz, so 5 apiece: the same price the compiled sketch's
     * own calls pay (CodeGen.TAKEN), so a core call is not cheaper than a sketch call.
     */
    static void call(Invocation c) {
        c.charge(CycleClass.BRANCH, 10);
        c.charge(CycleClass.STORE, 3);
        c.charge(CycleClass.LOAD, 3);
    }

    static void mix(Invocation c, int alu, int load, int store, int branch, int peripheral) {
        c.charge(CycleClass.ALU, alu);
        c.charge(CycleClass.LOAD, load);
        c.charge(CycleClass.STORE, store);
        c.charge(CycleClass.BRANCH, branch);
        c.charge(CycleClass.PERIPHERAL, peripheral);
    }

    /**
     * A byte loop as newlib-nano (built for size) writes memcpy, strlen and friends:
     * per byte its loads, stores, a couple of ALU ops and the taken branch back.
     */
    static void bytes(Invocation c, long n, int loads, int stores) {
        if (n <= 0)
            return;
        c.charge(CycleClass.LOAD, 2L * loads * n);
        c.charge(CycleClass.STORE, (long) stores * n);
        c.charge(CycleClass.ALU, 2 * n);
        c.charge(CycleClass.BRANCH, 3 * n);
    }

    static void softFloat(Invocation c, int cycles) {
        c.charge(CycleClass.SOFT_FLOAT, cycles);
    }

    private Cost() {}
}
