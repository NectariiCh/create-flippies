package com.nectariich.create_flippies.content.plc.mcu.chip;

import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * TIM3 as STM32duino's tone() uses it: a timer whose update interrupt toggles one
 * GPIO in software, plus the core's {@code TimerTone_pinInfo} (which pin, how many
 * toggles are left). One tone at a time, on any pin.
 *
 * The square wave is made by an interrupt, not by the timer's output, and that has
 * consequences the model keeps:
 * <ul>
 * <li>every half period costs an ISR ({@link #ISR_CYCLES}), so a high tone loads the
 * CPU (65 kHz is about a quarter of it);
 * <li>with interrupts disabled the toggling stops; the update flag stays pending and
 * one toggle happens when they are enabled again;
 * <li>the same inside an EXTI handler, which outranks TIM3 (priority 6 against 14):
 * the pin freezes until the handler returns, then one toggle (see
 * {@link Stm32f103#sync()}). The chip notices the return at its next stop, which a
 * running tone puts at most half a period away, so that one toggle can be late by up
 * to a half period;
 * <li>in the HardFault loop nothing toggles at all, the pin freezes where it was.
 * </ul>
 *
 * Events are replayed lazily, in order and at their own cycle stamps, whenever the
 * chip catches up ({@link Stm32f103#sync()}): before any builtin looks at a pin or the
 * clock, and every time the CPU stops, which the chip makes it do at each update
 * event. Pin high time is therefore exact, and each ISR's cycles land in the ledger
 * at its own event, give or take the instruction that crossed it.
 */
public final class ToneTimer {

    /**
     * One update interrupt: 12 cycles of exception entry, HAL_TIM_IRQHandler testing
     * and clearing its five flags (~40), HardwareTimer's callback dispatch through a
     * std::function (~25), tonePeriodElapsedCallback's count and toggle (~23), 12 to
     * return.
     */
    public static final int ISR_CYCLES = 112;

    private final Stm32f103 chip;

    /** {@code TimerTone_pinInfo.pin}; -1 is NC. */
    private int pin = -1;
    /** {@code TimerTone_pinInfo.count}: toggles left, -1 for forever. */
    private int count;
    private boolean exists;
    private boolean running;
    private long halfPeriod;
    private long nextEvent = Long.MAX_VALUE;
    private boolean updatePending;

    ToneTimer(Stm32f103 chip) {
        this.chip = chip;
    }

    void reset() {
        pin = -1;
        count = 0;
        exists = false;
        running = false;
        halfPeriod = 0;
        nextEvent = Long.MAX_VALUE;
        updatePending = false;
    }

    public int pin() {
        return pin;
    }

    public void setPin(int pin) {
        this.pin = pin;
    }

    public int count() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean exists() {
        return exists;
    }

    public boolean running() {
        return running;
    }

    /** Whether an update interrupt is waiting behind PRIMASK. */
    boolean updatePending() {
        return updatePending;
    }

    /** The next update event, or MAX while paused. */
    long nextEvent() {
        return running ? nextEvent : Long.MAX_VALUE;
    }

    /**
     * The first update event after {@code t}, projecting past events not yet replayed
     * (between catch-ups {@link #nextEvent()} can lie behind the present), or MAX.
     */
    long nextEventAfter(long t) {
        if (!running || halfPeriod <= 0)
            return Long.MAX_VALUE;
        if (nextEvent > t)
            return nextEvent;
        return nextEvent + ((t - nextEvent) / halfPeriod + 1) * halfPeriod;
    }

    /** {@code new HardwareTimer(TIM3)}: clock on. */
    public void create() {
        if (exists)
            return;
        exists = true;
        chip.gate(Stm32f103.GATE_TIM3, true);
    }

    /** {@code delete TimerTone}: counter stopped, clock off. */
    public void destroy() {
        if (!exists)
            return;
        running = false;
        exists = false;
        updatePending = false;
        nextEvent = Long.MAX_VALUE;
        chip.gate(Stm32f103.GATE_TIM3, false);
    }

    /**
     * {@code setOverflow(2 * f, HERTZ_FORMAT)}: the update period, quantised through
     * PSC and ARR exactly as HardwareTimer computes them.
     */
    public void setOverflowHz(long hz) {
        long periodCycles = hz <= 0 ? 0 : Stm32f103cb.CPU_HZ / hz;
        long psc = periodCycles / 0x10000 + 1;
        long ticks = Math.max(1, periodCycles / psc);
        long half = psc * ticks;
        if (running && half != halfPeriod && nextEvent != Long.MAX_VALUE)
            nextEvent = nextEvent - halfPeriod + half;
        halfPeriod = half;
    }

    /** Starts counting; the first update comes one period later, from a counter that starts at 0. */
    public void resume() {
        if (running)
            return;
        running = true;
        nextEvent = chip.now() + halfPeriod;
    }

    public void pause() {
        running = false;
        updatePending = false;
        nextEvent = Long.MAX_VALUE;
    }

    /** The tone's frequency (half the update rate), or 0 when it is not toggling. */
    double toneHz() {
        return running && pin >= 0 && count != 0 && halfPeriod > 0
                ? Stm32f103cb.CPU_HZ / (2.0 * halfPeriod) : 0;
    }

    /**
     * Replays the update events up to {@code now}. Returns how many ISRs ran, for the
     * chip to charge. {@code interruptsEnabled} is whether TIM3's interrupt can be
     * taken: false behind PRIMASK and while an EXTI handler runs, and then the events
     * leave the update pending instead.
     */
    int advance(long now, boolean interruptsEnabled) {
        if (!running || halfPeriod <= 0)
            return 0;
        int isrs = 0;
        if (!interruptsEnabled) {
            if (nextEvent <= now) {
                updatePending = true;
                long missed = (now - nextEvent) / halfPeriod + 1;
                nextEvent += missed * halfPeriod;
            }
            return 0;
        }
        while (nextEvent <= now) {
            isr(nextEvent);
            isrs++;
            nextEvent += halfPeriod;
        }
        return isrs;
    }

    /** Interrupts were enabled again: a pending update is taken at once. Returns ISRs run. */
    int takePending(long now) {
        if (!updatePending)
            return 0;
        updatePending = false;
        isr(now);
        return 1;
    }

    /** tonePeriodElapsedCallback. */
    private void isr(long t) {
        if (pin < 0)
            return;
        Gpio gpio = chip.gpio();
        if (count != 0) {
            if (count > 0)
                count--;
            gpio.writeOdrAt(pin, !gpio.odr(pin), t);
        } else {
            gpio.writeOdrAt(pin, false, t);
        }
    }
}
