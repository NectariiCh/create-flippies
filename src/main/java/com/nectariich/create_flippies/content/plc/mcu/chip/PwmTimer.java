package com.nectariich.create_flippies.content.plc.mcu.chip;

import java.util.Arrays;

import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * A timer driving PWM outputs: TIM1 (CH2N on PB0, CH3N on PB1, with the partial
 * remap the board uses, and CH4 on PA11, the STLED line, which is TIM1_CH4 in either
 * mapping) or TIM4 (CH1 on PB6, CH2 on PB7). Both count at 72 MHz:
 * TIM1 sits on APB2 at 72 MHz, TIM4 on APB1 at 36 MHz with the x2 timer multiplier.
 *
 * The register model is what STM32duino's HardwareTimer programs, so frequencies and
 * duties come out quantised exactly as the real ones do:
 *
 * <ul>
 * <li>{@link #setOverflowHz}: {@code period = 72 MHz / f}, {@code PSC + 1 = period / 65536 + 1},
 * {@code ARR + 1 = period / (PSC + 1)} (at least 1). A frequency of 0 divides by zero,
 * which UDIV answers with 0, so the timer ends up at its fastest; as on the chip.
 * <li>{@link #setCompare}: {@code CCR = (ARR + 1) * value / (2^bits - 1)} in 32-bit
 * unsigned arithmetic, written to a 16-bit register.
 * <li>PWM mode 1: the output is active while {@code CNT < CCR}, so the duty is
 * {@code min(CCR, ARR + 1) / (ARR + 1)}. On TIM1's complementary outputs, enabled on
 * their own (CCxE = 0, CCxNE = 1), RM0008 gives OCxN = OCxREF xor CCxNP, so they are
 * not inverted; CH4, which has no complementary output, is enabled through CC4E and
 * is not inverted either.
 * </ul>
 *
 * Changing the frequency of a running timer keeps its phase origin, so the counter's
 * phase jumps a little, as rewriting ARR without preload does. A timer that is
 * destroyed (STM32duino's pwm_stop deletes the whole HardwareTimer) stops every
 * channel on it; their pins stay in alternate function, undriven, which reads low.
 */
public final class PwmTimer {

    private final Stm32f103 chip;
    private final int gate;
    private final int[] channelPins;

    private boolean exists;
    private boolean running;
    private long prescaler = 1;
    private long reloadPlusOne = 0x10000;
    private long origin;
    private final long[] compare;
    private final boolean[] pwm;

    PwmTimer(Stm32f103 chip, int gate, int... channelPins) {
        this.chip = chip;
        this.gate = gate;
        this.channelPins = channelPins;
        this.compare = new long[channelPins.length];
        this.pwm = new boolean[channelPins.length];
    }

    void reset() {
        exists = false;
        running = false;
        prescaler = 1;
        reloadPlusOne = 0x10000;
        origin = 0;
        Arrays.fill(compare, 0);
        Arrays.fill(pwm, false);
    }

    /** The channel index driving this Arduino pin, or -1. */
    public int channelOf(int pin) {
        for (int i = 0; i < channelPins.length; i++)
            if (channelPins[i] == pin)
                return i;
        return -1;
    }

    /** Whether the HardwareTimer object exists (its clock is on from construction to deletion). */
    public boolean exists() {
        return exists;
    }

    public boolean running() {
        return running;
    }

    /** Constructs the timer: clock on, registers at reset values. */
    public void create() {
        if (exists)
            return;
        reset();
        exists = true;
        chip.gate(gate, true);
    }

    /** Deletes the timer: every channel stops and the clock goes off. */
    public void destroy() {
        if (!exists)
            return;
        exists = false;
        running = false;
        for (int ch = 0; ch < channelPins.length; ch++) {
            pwm[ch] = false;
            compare[ch] = 0;
        }
        refresh();
        chip.gate(gate, false);
    }

    public boolean isPwm(int channel) {
        return pwm[channel];
    }

    /** setMode(channel, PWM1, pin): the channel's pin goes to alternate function. */
    public void setPwmMode(int channel) {
        pwm[channel] = true;
        chip.gpio().setMode(channelPins[channel], Gpio.Mode.ALTERNATE);
        refresh();
    }

    public void setOverflowHz(long hz) {
        long periodCycles = hz <= 0 ? 0 : Stm32f103cb.CPU_HZ / hz;
        long psc = periodCycles / 0x10000 + 1;
        long ticks = periodCycles / psc;
        prescaler = psc;
        reloadPlusOne = Math.max(1, ticks);
        refresh();
    }

    public void setCompare(int channel, long value, int bits) {
        long denominator = (1L << bits) - 1;
        long product = (reloadPlusOne * (value & 0xFFFF_FFFFL)) & 0xFFFF_FFFFL;
        compare[channel] = (denominator <= 0 ? product : product / denominator) & 0xFFFF;
        refresh();
    }

    public void resume() {
        if (!running) {
            running = true;
            origin = chip.now();
        }
        refresh();
    }

    /** The PWM frequency the counter runs at, in hertz. */
    public double frequencyHz() {
        return (double) Stm32f103cb.CPU_HZ / (prescaler * reloadPlusOne);
    }

    private void refresh() {
        Gpio gpio = chip.gpio();
        for (int ch = 0; ch < channelPins.length; ch++) {
            int pin = channelPins[ch];
            if (gpio.mode(pin) != Gpio.Mode.ALTERNATE)
                continue;
            if (running && pwm[ch]) {
                long period = prescaler * reloadPlusOne;
                long high = Math.min(compare[ch], reloadPlusOne) * prescaler;
                gpio.setWave(pin, period, high, origin);
            } else {
                gpio.setWave(pin, 0, 0, 0);
            }
        }
    }
}
