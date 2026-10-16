package com.nectariich.create_flippies.content.plc.mcu;

/**
 * What a cycle was spent on. The VM charges every cycle it executes to exactly one
 * of these, and the power model prices each class separately, so the split has
 * to say where the energy goes, not merely where the time goes.
 */
public enum CycleClass {
    /** Integer data processing: add, logic, shifts, compares, moves. */
    ALU,
    /** MUL / MLA / UMULL. */
    MUL,
    /** UDIV / SDIV, 2 to 12 cycles on the M3. */
    DIV,
    /** LDR family against SRAM or flash. */
    LOAD,
    /** STR family against SRAM. */
    STORE,
    /** Taken branches, calls and returns: pipeline refills from flash. */
    BRANCH,
    /** Software floating point (libgcc / newlib): the M3 has no FPU. */
    SOFT_FLOAT,
    /** Peripheral register traffic on APB1/APB2 (GPIO, timers, ADC, EXTI, AFIO). */
    PERIPHERAL,
    /** Exception entry/exit and handler bodies (SysTick, EXTI, timer ISRs). */
    INTERRUPT,
    /** Busy-wait spinning: delay(), delayMicroseconds(), pulseIn() polling, a fast-forwarded idle loop. */
    WAIT
}
