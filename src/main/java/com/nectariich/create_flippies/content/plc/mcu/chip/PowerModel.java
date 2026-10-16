package com.nectariich.create_flippies.content.plc.mcu.chip;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * The STM32F103xB's 3.3 V current, priced per cycle class, calibrated to DS5319.
 *
 * <h2>Calibration</h2>
 *
 * DS5319's run-mode table gives, typical, running from flash at 72 MHz: about 36 mA
 * with every peripheral clocked and about 27 mA with none. Split the 27 mA into a
 * static part and a part proportional to clocked cycles:
 *
 * <ul>
 * <li>static, {@link #STATIC_AMPS} = 1.5 mA: regulator quiescent and leakage at room
 * temperature, the 8 MHz HSE crystal oscillator and the PLL;
 * <li>dynamic, 25.5 mA at 72 MHz for the datasheet's test code, which is a mix of
 * data processing, loads and branches — we take its weighted average as 1.10 of an
 * ALU cycle. One weighted cycle is therefore 25.5 mA / 72 MHz / 1.10 at 3.3 V, about
 * 1.06 nJ; with the static share, an average cycle costs about 1.24 nJ, inside the
 * 1.2–1.65 nJ the run-mode figures imply.
 * </ul>
 *
 * The weights say what a class does to the core's switching activity relative to an
 * ALU op: loads and stores toggle the bus matrix and SRAM or the flash array (1.15,
 * 1.1), taken branches refill the prefetch buffer from flash (1.25), APB peripheral
 * accesses cross the bridges (1.3), exception entry and exit stack eight registers
 * (1.2), and a busy-wait loop is cheap but not free (0.95; STM32duino never sleeps in
 * delay()). The HardFault loop is {@code b .}, all BRANCH.
 *
 * The 9 mA between "all peripherals" and "none" is spread over the peripherals; this
 * board only ever clocks the few in {@link #GATE_AMPS}, whose values are estimates in
 * the proportions of DS5319's peripheral current table (timers and ports of the order
 * of half a milliamp to a milliamp at 72 MHz). The ADC's conversion current is
 * charged only for the time an analogRead() has it powered, since STM32duino
 * initialises and de-initialises it on every call.
 *
 * States without a running clock use flat currents, estimates for a part held in
 * reset on its HSI, being programmed, or locked up on blank flash.
 */
final class PowerModel {

    static final double VDD = 3.3;

    static final double JOULES_PER_WEIGHTED_CYCLE = 25.5e-3 / Stm32f103cb.CPU_HZ / 1.10 * VDD;

    /** Weight per {@link CycleClass}, by ordinal. */
    static final double[] WEIGHT = new double[CycleClass.values().length];

    static {
        WEIGHT[CycleClass.ALU.ordinal()] = 1.0;
        WEIGHT[CycleClass.MUL.ordinal()] = 1.05;
        WEIGHT[CycleClass.DIV.ordinal()] = 1.0;
        WEIGHT[CycleClass.LOAD.ordinal()] = 1.15;
        WEIGHT[CycleClass.STORE.ordinal()] = 1.1;
        WEIGHT[CycleClass.BRANCH.ordinal()] = 1.25;
        WEIGHT[CycleClass.SOFT_FLOAT.ordinal()] = 1.0;
        WEIGHT[CycleClass.PERIPHERAL.ordinal()] = 1.3;
        WEIGHT[CycleClass.INTERRUPT.ordinal()] = 1.2;
        WEIGHT[CycleClass.WAIT.ordinal()] = 0.95;
    }

    static final double STATIC_AMPS = 1.5e-3;

    /** Clock-gated peripheral currents, by {@code Stm32f103.GATE_*}: GPIOA, GPIOB, GPIOC, AFIO, TIM1, TIM3, TIM4. */
    static final double[] GATE_AMPS = { 0.45e-3, 0.45e-3, 0.45e-3, 0.20e-3, 1.10e-3, 0.75e-3, 0.75e-3 };

    /** ADC1 clocked and converting (digital interface plus the analog block's supply). */
    static final double ADC_ACTIVE_AMPS = 1.6e-3;

    /** NRST held: HSI and regulator only. */
    static final double RESET_AMPS = 2.5e-3;

    /** Held in reset while the flash is erased and written. */
    static final double PROGRAMMING_AMPS = 7.0e-3;

    /** Blank flash: the core fetches 0xFFFFFFFF as its reset vector and locks up, still on the 8 MHz HSI. */
    static final double BLANK_LOCKUP_AMPS = 3.8e-3;

    /**
     * Average watts over a window of {@code windowCycles}: the cycles charged per
     * class, the static draw, peripheral clocks for the cycles each was on, and ADC
     * activity.
     */
    static double windowWatts(long[] classCycles, long windowCycles, long[] gateOnCycles, long adcCycles) {
        if (windowCycles <= 0)
            return 0;
        double joules = 0;
        for (int c = 0; c < classCycles.length; c++)
            joules += classCycles[c] * WEIGHT[c] * JOULES_PER_WEIGHTED_CYCLE;
        double ampCycles = STATIC_AMPS * windowCycles + ADC_ACTIVE_AMPS * adcCycles;
        for (int g = 0; g < gateOnCycles.length; g++)
            ampCycles += GATE_AMPS[g] * gateOnCycles[g];
        joules += ampCycles * VDD / Stm32f103cb.CPU_HZ;
        return joules / ((double) windowCycles / Stm32f103cb.CPU_HZ);
    }

    private PowerModel() {}
}
