package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;
import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.chip.Gpio;
import com.nectariich.create_flippies.content.plc.mcu.chip.PwmTimer;
import com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103;
import com.nectariich.create_flippies.content.plc.mcu.chip.ToneTimer;

/**
 * Digital, analog and advanced I/O, external interrupts and interrupt masking, as
 * STM32duino 2.x implements them — quirks included, because a sketch that works on
 * this PLC should work on a Blue Pill and the other way round:
 *
 * <ul>
 * <li>digitalWrite/digitalRead/digitalToggle do nothing (read LOW) on a pin pinMode
 * never configured ({@code g_digPinConfigured});
 * <li>analogRead leaves its pin in analog mode, so a later digitalRead reads 0 and an
 * output stops driving until pinMode is called again;
 * <li>pinMode on a PWM pin runs pwm_stop, which deletes the whole HardwareTimer and
 * stops the other channel on the same timer too;
 * <li>analogWrite on a pin without a timer channel is a digitalWrite of value ≥ half
 * scale, after a pinMode(OUTPUT);
 * <li>tone() serves one pin at a time: after noTone(pin) the timer stays bound to that
 * pin (the counter is only paused, and the pin keeps whatever level it had), and a
 * tone on another pin is ignored until {@code noTone(pin, true)}.
 * <li>an invalid pinMode mode or analog resolution goes to Error_Handler, an endless
 * loop; we raise it as {@link HardFault.Kind#ABORT} so the menu can say so.
 * </ul>
 *
 * <h2>Which pins have hardware PWM</h2>
 *
 * analogWrite takes the timer path on the pins STM32duino's F103 PinMap_TIM gives a
 * timer channel and the board brings out as outputs: PB0 and PB1 (TIM1_CH2N/CH3N),
 * PB6 and PB7 (TIM4_CH1/CH2) — and PA11, the STLED line, which is TIM1_CH4 with or
 * without the partial remap. The board's pinout lists STLED as a plain output, but
 * the core does not read the pinout: on a Blue Pill {@code analogWrite(STLED, 100)}
 * is a 1 kHz, 39 % PWM, so it is here too, and it shares TIM1 with DO0 and DO1 (a
 * pinMode on any of the three deletes the timer and stops all of them). PinMap_TIM
 * also has channels on PA0–PA3 (TIM2), PA6 (TIM3_CH1) and PA7 (TIM1_CH1N), which are
 * DI0–DI3, DI6 and DI7 here; those are board inputs behind the 24 V front ends, so
 * PWM there could reach nothing and they are left digital (analogWrite on them is the
 * non-timer path: HIGH at half scale and up).
 */
final class IoBuiltins {

    static final int INPUT = 0;
    static final int OUTPUT = 1;
    static final int INPUT_PULLUP = 2;
    static final int INPUT_PULLDOWN = 3;
    static final int INPUT_ANALOG = 4;
    static final int OUTPUT_OPEN_DRAIN = 5;

    static final int LOW = 0;
    static final int HIGH = 1;
    static final int CHANGE = 2;
    static final int FALLING = 3;
    static final int RISING = 4;

    static final int LSBFIRST = 0;

    /**
     * ADC: STM32duino's analogRead re-initialises, calibrates, converts once and
     * de-initialises ADC1. The ADC is powered (ADON) from ADC_Enable's stabilisation
     * wait through calibration and the conversion to the de-init: about 1450 cycles.
     */
    private static final int ANALOG_READ_ACTIVE_CYCLES = 1450;

    /** One iteration of each of pulseIn's polling loops: IDR load and mask, micros() (~46), compare and branch. */
    static final int PULSE_LOOP_CYCLES = 56;

    static void register(List<Fn> out) {
        out.add(new Fn("void pinMode(unsigned long pin, unsigned long mode)", 220, c -> pinMode(c, c.arg(0), c.arg(1))));
        out.add(new Fn("void digitalWrite(unsigned long pin, unsigned long val)", 60, c -> digitalWrite(c, c.arg(0), c.arg(1) != 0)));
        out.add(new Fn("int digitalRead(unsigned long pin)", 64, c -> c.returnValue(digitalRead(c, c.arg(0)) ? HIGH : LOW)));
        out.add(new Fn("void digitalToggle(unsigned long pin)", 60, IoBuiltins::digitalToggle));
        out.add(new Fn("void digitalWriteFast(PinName pn, unsigned long val)", 16, c -> digitalWriteFast(c, c.arg(1) != 0)));
        out.add(new Fn("int digitalReadFast(PinName pn)", 16, IoBuiltins::digitalReadFast));
        out.add(new Fn("void digitalToggleFast(PinName pn)", 20, IoBuiltins::digitalToggleFast));
        out.add(new Fn("unsigned long pinNametoDigitalPin(PinName p)", 40, IoBuiltins::pinNameToDigitalPin));

        // ~2200 cycles, most of them polled waits for calibration and the conversion, which
        // interrupts preempt on the chip: charged in pieces (see Preemptible)
        out.add(new Fn("unsigned long analogRead(unsigned long pin)", 2600, Preemptible.wrap(IoBuiltins::analogRead)));
        out.add(new Fn("void analogReference(unsigned long mode)", 4, c -> Cost.call(c)));
        out.add(new Fn("void analogReadResolution(int res)", 60, IoBuiltins::analogReadResolution));
        out.add(new Fn("void analogWrite(unsigned long pin, unsigned long value)", 3600, c -> analogWrite(c, c.arg(0), c.arg(1))));
        out.add(new Fn("void analogWriteResolution(int res)", 40, IoBuiltins::analogWriteResolution));
        out.add(new Fn("void analogWriteFrequency(unsigned long freq)", 12, c -> {
            Cost.mix(c, 1, 0, 2, 6, 0);
            CoreState.of(c).writeFrequency = c.arg(0) & 0xFFFF_FFFFL;
        }));

        out.add(new Fn("void tone(unsigned char pin, unsigned int frequency)", 3800, c -> tone(c, c.arg(0), c.arg(1), 0)));
        out.add(new Fn("void tone(unsigned char pin, unsigned int frequency, unsigned long duration)", 3800,
                c -> tone(c, c.arg(0), c.arg(1), c.arg(2))));
        out.add(new Fn("void noTone(unsigned char pin)", 120, c -> noTone(c, c.arg(0), false)));
        out.add(new Fn("void noTone(unsigned char pin, bool destruct)", 120, c -> noTone(c, c.arg(0), c.arg(1) != 0)));

        out.add(new Fn("unsigned long pulseIn(unsigned long pin, unsigned long state)", 180,
                c -> pulseIn(c, c.arg(0), c.arg(1), 1_000_000L)));
        out.add(new Fn("unsigned long pulseIn(unsigned long pin, unsigned long state, unsigned long timeout)", 180,
                c -> pulseIn(c, c.arg(0), c.arg(1), c.arg(2))));
        out.add(new Fn("unsigned long pulseInLong(unsigned long pin, unsigned long state)", 180,
                c -> pulseIn(c, c.arg(0), c.arg(1), 1_000_000L)));
        out.add(new Fn("unsigned long pulseInLong(unsigned long pin, unsigned long state, unsigned long timeout)", 180,
                c -> pulseIn(c, c.arg(0), c.arg(1), c.arg(2))));
        out.add(new Fn("void shiftOut(unsigned long dataPin, unsigned long clockPin, unsigned long bitOrder, unsigned long val)", 80,
                IoBuiltins::shiftOut));
        out.add(new Fn("unsigned long shiftIn(unsigned long dataPin, unsigned long clockPin, unsigned long bitOrder)", 84,
                IoBuiltins::shiftIn));

        out.add(new Fn("void attachInterrupt(unsigned long pin, void (*callback)(void), unsigned long mode)", 1400,
                IoBuiltins::attachInterrupt));
        out.add(new Fn("void detachInterrupt(unsigned long pin)", 120, IoBuiltins::detachInterrupt));
        out.add(new Fn("void __enable_irq(void)", 0, IoBuiltins::enableIrq));
        out.add(new Fn("void __disable_irq(void)", 0, IoBuiltins::disableIrq));
        out.add(new Fn("unsigned long __get_PRIMASK(void)", 0, IoBuiltins::getPrimask));
        out.add(new Fn("void __set_PRIMASK(unsigned long priMask)", 0, IoBuiltins::setPrimask));

        // CMSIS intrinsics (inline instructions, no call)
        out.add(new Fn("void __NOP(void)", 0, c -> c.charge(CycleClass.ALU, 1)));
        out.add(new Fn("void __DMB(void)", 0, c -> c.charge(CycleClass.ALU, 1)));
        out.add(new Fn("void __DSB(void)", 0, c -> c.charge(CycleClass.ALU, 1)));
        out.add(new Fn("void __ISB(void)", 0, c -> c.charge(CycleClass.BRANCH, 3))); // pipeline refill
        out.add(new Fn("void __WFI(void)", 0, IoBuiltins::waitForInterrupt));
        out.add(new Fn("void __WFE(void)", 0, IoBuiltins::waitForEvent));
        out.add(new Fn("void __SEV(void)", 0, c -> {
            c.charge(CycleClass.ALU, 1);
            CoreState.chip(c).sendEvent();
        }));
        out.add(new Fn("void NVIC_SystemReset(void)", 24, IoBuiltins::systemReset));
    }

    // ---- digital -----------------------------------------------------------------

    static void pinMode(Invocation c, long pinArg, long modeArg) {
        Cost.call(c);
        c.charge(CycleClass.ALU, 6);
        c.charge(CycleClass.LOAD, 4); // digitalPinToPinName: table in flash
        if (!Gpio.valid(pinArg))
            return;
        int pin = (int) pinArg;
        Stm32f103 chip = CoreState.chip(c);
        CoreState core = CoreState.of(c);
        chip.sync();
        Gpio gpio = chip.gpio();
        long serial = gpio.changeSerial();

        if (core.pwmConfigured[pin]) {
            PwmTimer timer = chip.pwmTimer(pin);
            if (timer != null && timer.exists()) {
                // pwm_stop: ~HardwareTimer, HAL_TIM_Base_DeInit, RCC clock off
                Cost.mix(c, 110, 60, 40, 60, 40);
                timer.destroy();
            }
            core.pwmConfigured[pin] = false;
        }

        Gpio.Mode mode;
        int pull = PULL_NONE;
        switch ((int) modeArg) {
            case INPUT: mode = Gpio.Mode.INPUT; break;
            case OUTPUT: mode = Gpio.Mode.OUTPUT_PP; break;
            case INPUT_PULLUP: mode = Gpio.Mode.INPUT_PULL; pull = PULL_UP; break;
            case INPUT_PULLDOWN: mode = Gpio.Mode.INPUT_PULL; pull = PULL_DOWN; break;
            case INPUT_ANALOG: mode = Gpio.Mode.ANALOG; break;
            case OUTPUT_OPEN_DRAIN: mode = Gpio.Mode.OUTPUT_OD; break;
            default:
                throw new HardFault(HardFault.Kind.ABORT, -1,
                        "Error_Handler: pinMode(" + pin + ", " + modeArg + "): no such mode");
        }
        pinFunction(c, chip, pin, mode, pull);
        if (!core.digitalConfigured[pin]) {
            core.digitalConfigured[pin] = true;
            c.noteSideEffect();
        }
        if (gpio.changeSerial() != serial)
            c.noteSideEffect();
    }

    /** {@link #pinFunction}'s pull argument: leave ODR alone, as GPIO_NOPULL does. */
    static final int PULL_NONE = -1;
    static final int PULL_DOWN = 0;
    static final int PULL_UP = 1;

    /** {@link #pinFunction(Invocation, Stm32f103, int, Gpio.Mode, int)} with no pull. */
    static void pinFunction(Invocation c, Stm32f103 chip, int pin, Gpio.Mode mode) {
        pinFunction(c, chip, pin, mode, PULL_NONE);
    }

    /**
     * STM32duino's pin_function: on the F1 it first gives a JTAG/SWD pin back to GPIO
     * (pinF1_DisconnectDebug rewrites AFIO_MAPR), then HAL_GPIO_Init: about 3–5 µs.
     * A pull ({@link #PULL_UP}/{@link #PULL_DOWN}) is pin_PullConfig's
     * LL_GPIO_SetPinPull, which on the F1 is a write of ODR; {@link #PULL_NONE} leaves
     * ODR as it is, so a later switch to output drives whatever the last pull left.
     */
    static void pinFunction(Invocation c, Stm32f103 chip, int pin, Gpio.Mode mode, int pull) {
        disconnectDebug(c, chip, pin);
        if (Gpio.port(pin) == 2)
            chip.gate(Stm32f103.GATE_GPIOC, true);
        Cost.mix(c, 130, 60, 25, 65, 50);
        if (pull == PULL_NONE)
            chip.gpio().setMode(pin, mode);
        else
            chip.gpio().setInputPull(pin, pull == PULL_UP);
    }

    private static void disconnectDebug(Invocation c, Stm32f103 chip, int pin) {
        int port = Gpio.port(pin);
        int bit = Gpio.bit(pin);
        boolean jtagOnly = (port == 0 && bit == 15) || (port == 1 && (bit == 3 || bit == 4));
        boolean swd = port == 0 && (bit == 13 || bit == 14);
        if (jtagOnly && !chip.jtagReleased()) {
            Cost.mix(c, 6, 2, 0, 3, 6);
            chip.releaseJtag();
        } else if (swd && !chip.swdReleased()) {
            Cost.mix(c, 6, 2, 0, 3, 6);
            chip.releaseSwd();
        }
    }

    static void digitalWrite(Invocation c, long pinArg, boolean high) {
        Cost.call(c);
        Cost.mix(c, 10, 6, 0, 2, 0);
        if (!Gpio.valid(pinArg))
            return;
        int pin = (int) pinArg;
        if (!CoreState.of(c).digitalConfigured[pin])
            return;
        Stm32f103 chip = CoreState.chip(c);
        chargePortLookup(c);
        c.charge(CycleClass.PERIPHERAL, 2); // BSRR / BRR
        chip.sync();
        if (chip.gpio().writeOdr(pin, high))
            c.noteSideEffect();
    }

    static boolean digitalRead(Invocation c, long pinArg) {
        Cost.call(c);
        Cost.mix(c, 10, 6, 0, 2, 0);
        if (!Gpio.valid(pinArg))
            return false;
        int pin = (int) pinArg;
        if (!CoreState.of(c).digitalConfigured[pin])
            return false;
        Stm32f103 chip = CoreState.chip(c);
        chargePortLookup(c);
        c.charge(CycleClass.PERIPHERAL, 3); // IDR over APB2
        chip.sync();
        boolean level = chip.gpio().read(pin);
        c.charge(CycleClass.ALU, 3);
        return level;
    }

    /**
     * The way from a configured pin to its port, which digitalWrite, digitalRead and
     * digitalToggle all take once the pin passed the NC and g_digPinConfigured tests:
     * an out-of-line call to get_GPIO_Port (PortNames.c: bounds check, LDR from
     * GPIOPort[]), then STM_LL_GPIO_PIN, which on the F1 is a load from pin_map_ll[].
     * Both tables are const, in flash, and their loads pay the two wait states the
     * prefetcher cannot hide. With the lookup the three come to about 50–60 cycles, as
     * STM32duino 2.x's do.
     */
    private static void chargePortLookup(Invocation c) {
        Cost.call(c);
        c.charge(CycleClass.ALU, 4);
        c.charge(CycleClass.LOAD, 2 * Stm32f103cb.FLASH_WAIT_STATES);
    }

    // ---- the fast variants: PinName, no configuration check ---------------------

    /**
     * The pin a PinName names, or -1 for a valid port bit this package has no pad for
     * (writes are lost, reads are 0). A port beyond GPIOC (NC included) makes
     * get_GPIO_Port return NULL, and the LL access through it bus-faults.
     */
    private static int pinOfName(Invocation c, long name) {
        int pn = (int) name;
        int port = (pn >> 4) & 0xF;
        if (pn < 0 || port > 2)
            throw new HardFault(HardFault.Kind.BUS_FAULT, 0x10, "digital*Fast on a PinName with no GPIO port (" + pn + ")");
        return ArduinoPins.fromPortPin(port, pn & 0xF);
    }

    /**
     * digitalWriteFast and friends (digital_io.h): static inline, so no call of their
     * own, but still get_GPIO_Port out of line and the pin_map_ll[] load before the
     * one LL register access. No g_digPinConfigured test, so they work on any pin
     * whose mode is right, configured by pinMode or not.
     */
    private static void digitalWriteFast(Invocation c, boolean high) {
        int pin = pinOfName(c, c.arg(0));
        chargePortLookup(c);
        c.charge(CycleClass.ALU, 2);
        c.charge(CycleClass.PERIPHERAL, 2); // BSRR / BRR
        if (pin < 0)
            return;
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        if (chip.gpio().writeOdr(pin, high))
            c.noteSideEffect();
    }

    private static void digitalReadFast(Invocation c) {
        int pin = pinOfName(c, c.arg(0));
        chargePortLookup(c);
        c.charge(CycleClass.ALU, 3);
        c.charge(CycleClass.PERIPHERAL, 3); // IDR over APB2
        if (pin < 0) {
            c.returnValue(LOW);
            return;
        }
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        c.returnValue(chip.gpio().read(pin) ? HIGH : LOW);
    }

    private static void digitalToggleFast(Invocation c) {
        int pin = pinOfName(c, c.arg(0));
        chargePortLookup(c);
        c.charge(CycleClass.ALU, 2);
        c.charge(CycleClass.PERIPHERAL, 5); // ODR read, then BSRR
        if (pin < 0)
            return;
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        chip.gpio().writeOdr(pin, !chip.gpio().odr(pin));
        c.noteSideEffect();
    }

    /**
     * pinNametoDigitalPin (pinmap.c): a linear search of the variant's digitalPin[]
     * table in flash; NUM_DIGITAL_PINS when the name is not there.
     */
    private static void pinNameToDigitalPin(Invocation c) {
        Cost.call(c);
        int pn = (int) c.arg(0);
        c.charge(CycleClass.ALU, 3);
        int found = ArduinoPins.COUNT;
        if (pn >= 0 && ((pn >> 4) & 0xF) <= 2) {
            int i = 0;
            while (i < ArduinoPins.COUNT && ArduinoPins.PORT_PIN[i] != pn)
                i++;
            found = i;
            long steps = Math.min(i + 1L, ArduinoPins.COUNT);
            c.charge(CycleClass.LOAD, steps * (2 + Stm32f103cb.FLASH_WAIT_STATES));
            c.charge(CycleClass.ALU, 2 * steps);
            c.charge(CycleClass.BRANCH, 3 * steps);
        }
        c.returnValue(found);
    }

    private static void digitalToggle(Invocation c) {
        Cost.call(c);
        Cost.mix(c, 10, 6, 0, 2, 0);
        if (!Gpio.valid(c.arg(0)))
            return;
        int pin = (int) c.arg(0);
        if (!CoreState.of(c).digitalConfigured[pin])
            return;
        Stm32f103 chip = CoreState.chip(c);
        chargePortLookup(c);
        c.charge(CycleClass.PERIPHERAL, 5); // ODR read-modify-write
        chip.sync();
        chip.gpio().writeOdr(pin, !chip.gpio().odr(pin));
        c.noteSideEffect();
    }

    // ---- analog ------------------------------------------------------------------

    /** STM32duino's mapResolution, in 32-bit unsigned arithmetic. */
    static long mapResolution(long value, int from, int to) {
        value &= 0xFFFF_FFFFL;
        if (from == to)
            return value;
        if (from > to) {
            int shift = from - to;
            long one = shift >= 32 ? 0x1_0000_0000L : (1L << shift);
            return value < one ? 0 : ((((value + 1) & 0xFFFF_FFFFL) >>> Math.min(shift, 63)) - 1) & 0xFFFF_FFFFL;
        }
        if (value == 0)
            return 0;
        int shift = to - from;
        return shift >= 32 ? 0xFFFF_FFFFL : ((((value + 1) << shift) - 1) & 0xFFFF_FFFFL);
    }

    private static void analogRead(Invocation c) {
        Cost.call(c);
        Cost.mix(c, 8, 6, 0, 3, 0);
        long pinArg = c.arg(0);
        // analogInputToPinName: A0..A7 are 0..7 here, so the number is the pin; only PA0..PA7, PB0, PB1 reach ADC1
        if (!Gpio.valid(pinArg) || pinArg > 9) {
            c.returnValue(0);
            return;
        }
        int pin = (int) pinArg;
        Stm32f103 chip = CoreState.chip(c);
        CoreState core = CoreState.of(c);
        chip.sync();
        // HAL_ADC_Init, MspInit (clock on, pin to analog), the calibration's HAL code
        Cost.mix(c, 140, 70, 40, 80, 120);
        disconnectDebug(c, chip, pin);
        if (chip.gpio().setMode(pin, Gpio.Mode.ANALOG))
            c.noteSideEffect();
        // ADC_Enable's ADC_STAB_DELAY_US (1 us): a volatile countdown of 72 iterations,
        // LDR/SUBS/STR/LDR/CMP/BNE, about 7 cycles each
        Cost.mix(c, 72, 2 * 72, 72, 3 * 72, 0);
        // HAL_RCCEx_GetPeriphCLKFreq, called to size the pre-calibration wait, and that
        // wait of two ADC clocks
        Cost.call(c);
        Cost.mix(c, 40, 20, 6, 24, 10);
        // HAL_ADCEx_Calibration_Start runs on every call (ADC_CR2_RSTCAL exists on the F1):
        // RSTCAL, then CAL for tCAL = 83 ADC clocks (DS5319 Table 46) at STM32duino's
        // 12 MHz ADC clock (PCLK2 / 6), polled: ~560 cycles
        c.charge(CycleClass.WAIT, 560);
        double volts = chip.gpio().padVolts(pin);
        long raw = Math.max(0, Math.min(4095, Math.round(volts / 3.3 * 4095)));
        // ConfigChannel, Start, PollForConversion (13.5 + 12.5 ADC clocks at 12 MHz), GetValue, Stop, DeInit
        Cost.mix(c, 120, 60, 30, 70, 100);
        c.charge(CycleClass.WAIT, 160);
        chip.adcActive(ANALOG_READ_ACTIVE_CYCLES);
        c.returnValue(mapResolution(raw, core.internalReadResolution, core.readResolution));
    }

    private static void analogReadResolution(Invocation c) {
        Cost.call(c);
        Cost.mix(c, 6, 0, 2, 2, 0);
        long res = (int) c.arg(0);
        if (res <= 0 || res > 32)
            throw new HardFault(HardFault.Kind.ABORT, -1, "Error_Handler: analogReadResolution(" + res + ")");
        CoreState core = CoreState.of(c);
        core.readResolution = (int) res;
        core.internalReadResolution = 12; // the F1's ADC is 12-bit only; results are scaled
    }

    private static void analogWriteResolution(Invocation c) {
        Cost.call(c);
        Cost.mix(c, 6, 0, 2, 2, 0);
        long res = (int) c.arg(0);
        if (res <= 0 || res > 32)
            throw new HardFault(HardFault.Kind.ABORT, -1, "Error_Handler: analogWriteResolution(" + res + ")");
        CoreState core = CoreState.of(c);
        core.writeResolution = (int) res;
        core.internalWriteResolution = (int) Math.min(res, 16);
    }

    static void analogWrite(Invocation c, long pinArg, long value) {
        Cost.call(c);
        Cost.mix(c, 8, 6, 0, 3, 0);
        if (!Gpio.valid(pinArg))
            return;
        int pin = (int) pinArg;
        Stm32f103 chip = CoreState.chip(c);
        CoreState core = CoreState.of(c);
        PwmTimer timer = chip.pwmTimer(pin);
        if (timer == null) {
            pinMode(c, pin, OUTPUT);
            long v = mapResolution(value, core.writeResolution, 8);
            digitalWrite(c, pin, v >= 128);
            return;
        }
        chip.sync();
        long serial = chip.gpio().changeSerial();
        core.pwmConfigured[pin] = true;
        long v = mapResolution(value, core.writeResolution, core.internalWriteResolution);
        int channel = timer.channelOf(pin);
        if (!timer.exists()) {
            // new HardwareTimer: handle init, HAL_TIM_Base_Init, clock enable, NVIC
            Cost.mix(c, 420, 210, 160, 190, 120);
            timer.create();
        }
        boolean wasPwm = timer.isPwm(channel);
        if (!wasPwm) {
            // setMode(channel, PWM1, pin): HAL_TIM_PWM_ConfigChannel and the pin's AF init
            Cost.mix(c, 170, 80, 40, 80, 70);
            timer.setPwmMode(channel);
        }
        // setOverflow (two UDIVs) and setCaptureCompare (MUL, UDIV)
        Cost.mix(c, 90, 40, 10, 45, 12);
        c.charge(CycleClass.DIV, 24);
        c.charge(CycleClass.MUL, 2);
        timer.setOverflowHz(core.writeFrequency);
        timer.setCompare(channel, v, core.internalWriteResolution);
        if (!wasPwm) {
            Cost.mix(c, 50, 20, 10, 25, 25); // resume(): enable channel, MOE, counter
            timer.resume();
        }
        if (chip.gpio().changeSerial() != serial)
            c.noteSideEffect();
    }

    // ---- tone --------------------------------------------------------------------

    private static void tone(Invocation c, long pinArg, long frequency, long duration) {
        Cost.call(c);
        Cost.mix(c, 8, 6, 0, 3, 0);
        if (!Gpio.valid(pinArg))
            return;
        int pin = (int) pinArg;
        Stm32f103 chip = CoreState.chip(c);
        ToneTimer timer = chip.toneTimer();
        if (timer.pin() != -1 && timer.pin() != pin)
            return;
        frequency &= 0xFFFF_FFFFL;
        duration &= 0xFFFF_FFFFL;
        if (frequency > 65535)
            return;
        chip.sync();
        c.noteSideEffect();
        if (frequency == 0) {
            if (timer.exists()) {
                Cost.mix(c, 10, 4, 2, 6, 8);
                timer.pause();
            }
            return;
        }
        long timFreq = (2 * frequency) & 0xFFFF_FFFFL;
        timer.setPin(pin);
        // count = (timFreq * duration) / 1000 in uint32, stored into an int32_t
        timer.setCount(duration > 0 ? (int) (((timFreq * duration) & 0xFFFF_FFFFL) / 1000) : -1);
        c.charge(CycleClass.MUL, 1);
        c.charge(CycleClass.DIV, 8);
        pinFunction(c, chip, pin, Gpio.Mode.OUTPUT_PP);
        if (!timer.exists()) {
            Cost.mix(c, 420, 210, 160, 190, 120); // new HardwareTimer(TIM3)
            timer.create();
        }
        // setOverflow, attachInterrupt (std::function copy, DIER), resume
        Cost.mix(c, 150, 70, 50, 80, 40);
        c.charge(CycleClass.DIV, 24);
        timer.setOverflowHz(timFreq);
        timer.resume();
    }

    private static void noTone(Invocation c, long pinArg, boolean destruct) {
        Cost.call(c);
        Cost.mix(c, 8, 6, 0, 3, 0);
        if (!Gpio.valid(pinArg))
            return;
        int pin = (int) pinArg;
        Stm32f103 chip = CoreState.chip(c);
        ToneTimer timer = chip.toneTimer();
        if (timer.pin() != pin || !timer.exists())
            return;
        chip.sync();
        c.noteSideEffect();
        if (destruct) {
            Cost.mix(c, 110, 60, 40, 60, 40);
            timer.destroy();
            pinFunction(c, chip, pin, Gpio.Mode.INPUT);
            timer.setPin(-1);
        } else {
            Cost.mix(c, 10, 4, 2, 6, 8);
            timer.pause();
        }
    }

    // ---- pulseIn -----------------------------------------------------------------

    private static void pulseIn(Invocation c, long pinArg, long state, long timeout) {
        Cost.call(c);
        Cost.mix(c, 16, 10, 0, 4, 0);
        if (!Gpio.valid(pinArg)) {
            // digitalPinToBitMask of NC: STM32duino reads an unmapped port; we give up at once
            c.returnValue(0);
            return;
        }
        Stm32f103 chip = CoreState.chip(c);
        TimeBuiltins.chargeMicros(c);
        long startMicros = TimeBuiltins.micros(chip);
        c.block(new PulseIn(chip, (int) pinArg, state != 0, timeout & 0xFFFF_FFFFL, startMicros));
    }

    /**
     * STM32duino's pulseIn, three polling loops on IDR and micros(): wait for any pulse
     * already in progress to end, wait for one to start, time it until it ends; each
     * loop gives up once micros() has moved more than {@code timeout} past the start.
     *
     * The loops sample every {@link #PULSE_LOOP_CYCLES} from where each began, so an
     * edge is noticed at the first sample on or after it, as on the chip. Between
     * samples nothing can change but a waveform edge, the board's inputs at the start
     * of the next window, or an ISR writing the pin, so the CPU is told to come back
     * only then (or at the timeout) and spends the gap as WAIT.
     */
    static final class PulseIn implements Blocker {

        private final Stm32f103 chip;
        private final int pin;
        private final boolean state;
        private final long timeout;
        private final long startMicros;
        private int phase;
        private long gridOrigin;
        private long pulseStartMicros;
        private long timeoutCycle;
        private long seenSerial = Long.MIN_VALUE;

        PulseIn(Stm32f103 chip, int pin, boolean state, long timeout, long startMicros) {
            this.chip = chip;
            this.pin = pin;
            this.state = state;
            this.timeout = timeout;
            this.startMicros = startMicros;
            this.gridOrigin = chip.now();
            this.timeoutCycle = gridOrigin + (timeout + 1) * 72 + 1;
            chip.gpio().markObserved(pin);
        }

        private long align(long t) {
            if (t <= gridOrigin)
                return gridOrigin;
            long k = (t - gridOrigin + PULSE_LOOP_CYCLES - 1) / PULSE_LOOP_CYCLES;
            return gridOrigin + k * PULSE_LOOP_CYCLES;
        }

        @Override
        public long nextPollCycle() {
            long now = chip.now();
            if (chip.gpio().changeSerial() != seenSerial || chip.gpio().levelChangeDue(pin, now))
                return now;
            long t = Math.min(chip.gpio().nextLevelChange(pin, now), timeoutCycle);
            t = Math.min(t, chip.nextEventCycle());
            return Math.max(now, align(t));
        }

        @Override
        public boolean poll(Invocation call) {
            chip.sync();
            long now = chip.now();
            long aligned = align(now);
            if (aligned > now) {
                call.charge(CycleClass.WAIT, aligned - now);
                chip.sync();
                now = chip.now();
            }
            seenSerial = chip.gpio().changeSerial();
            boolean level = chip.gpio().idr(pin, now);
            while (true) {
                if (phase == 0) {
                    if (level == state)
                        return waiting(call, now);
                    phase = 1;
                    gridOrigin = now;
                }
                if (phase == 1) {
                    if (level != state)
                        return waiting(call, now);
                    phase = 2;
                    pulseStartMicros = TimeBuiltins.micros(chip);
                    gridOrigin = now;
                }
                if (level == state)
                    return waiting(call, now);
                call.charge(CycleClass.ALU, 4);
                call.returnValue((TimeBuiltins.micros(chip) - pulseStartMicros) & 0xFFFF_FFFFL);
                return true;
            }
        }

        private boolean waiting(Invocation call, long now) {
            if (((TimeBuiltins.micros(chip) - startMicros) & 0xFFFF_FFFFL) > timeout) {
                call.returnValue(0);
                return true;
            }
            if (now >= timeoutCycle)
                timeoutCycle = now + 72;
            return false;
        }
    }

    // ---- shiftOut / shiftIn ------------------------------------------------------

    private static void shiftOut(Invocation c) {
        long data = c.arg(0);
        long clock = c.arg(1);
        boolean lsb = c.arg(2) == LSBFIRST;
        long val = c.arg(3);
        Cost.call(c);
        for (int i = 0; i < 8; i++) {
            int bit = lsb ? i : 7 - i;
            c.charge(CycleClass.ALU, 4);
            digitalWrite(c, data, ((val >>> bit) & 1) != 0);
            digitalWrite(c, clock, true);
            digitalWrite(c, clock, false);
            c.charge(CycleClass.BRANCH, 3);
        }
    }

    private static void shiftIn(Invocation c) {
        long data = c.arg(0);
        long clock = c.arg(1);
        boolean lsb = c.arg(2) == LSBFIRST;
        Cost.call(c);
        int value = 0;
        for (int i = 0; i < 8; i++) {
            digitalWrite(c, clock, true);
            if (digitalRead(c, data))
                value |= 1 << (lsb ? i : 7 - i);
            c.charge(CycleClass.ALU, 4);
            digitalWrite(c, clock, false);
            c.charge(CycleClass.BRANCH, 3);
        }
        c.returnValue(value & 0xFF);
    }

    // ---- interrupts --------------------------------------------------------------

    private static void attachInterrupt(Invocation c) {
        Cost.call(c);
        Cost.mix(c, 8, 6, 0, 3, 0);
        long pinArg = c.arg(0);
        long handler = c.arg(1);
        long mode = c.arg(2);
        if (!Gpio.valid(pinArg))
            return;
        int pin = (int) pinArg;
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        int edges = mode == CHANGE ? Gpio.EDGE_BOTH
                : (mode == FALLING || mode == LOW) ? Gpio.EDGE_FALLING
                : Gpio.EDGE_RISING;
        Gpio gpio = chip.gpio();
        // A pulled input keeps its pull, which on the F1 is its ODR bit: setMode leaves ODR
        // alone, so INPUT_PULL stays pulled the same way. Anything else becomes floating.
        Gpio.Mode input = gpio.mode(pin) == Gpio.Mode.INPUT_PULL ? Gpio.Mode.INPUT_PULL : Gpio.Mode.INPUT;
        // HAL_GPIO_Init in an IT mode (pull kept), AFIO_EXTICR, EXTI_IMR/RTSR/FTSR, NVIC priority and enable,
        // and the std::function callback stored in gpio_irq_conf
        pinFunction(c, chip, pin, input);
        Cost.mix(c, 160, 80, 60, 90, 60);
        gpio.attach(pin, edges, handler);
        c.noteSideEffect();
    }

    private static void detachInterrupt(Invocation c) {
        Cost.call(c);
        Cost.mix(c, 40, 20, 12, 20, 16);
        long pinArg = c.arg(0);
        if (!Gpio.valid(pinArg))
            return;
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        if (chip.gpio().detach((int) pinArg))
            c.noteSideEffect();
    }

    /** CPSIE i. Interrupts that came due while masked are taken now, once each. */
    private static void enableIrq(Invocation c) {
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        c.charge(CycleClass.ALU, 1);
        c.setInterruptsEnabled(true);
        if (chip.interruptsReenabled())
            c.noteSideEffect();
    }

    /**
     * CPSID i. Masking is not reported as a side effect: a loop that masks, copies a
     * volatile an ISR writes and unmasks again ends every iteration as it began, and
     * must stay eligible for the idle fast-forward.
     */
    private static void disableIrq(Invocation c) {
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        c.charge(CycleClass.ALU, 1);
        c.setInterruptsEnabled(false);
    }

    /**
     * MRS r0, PRIMASK: 1 while interrupts are masked. With {@link #setPrimask} it makes
     * the nesting critical section CMSIS code writes
     * ({@code m = __get_PRIMASK(); __disable_irq(); ... __set_PRIMASK(m);}), which is
     * also safe inside an ISR.
     */
    private static void getPrimask(Invocation c) {
        c.charge(CycleClass.ALU, 1);
        c.returnValue(CoreState.chip(c).interruptsEnabled() ? 0 : 1);
    }

    /**
     * MSR PRIMASK, r0: bit 0 masks or unmasks, exactly as CPSID/CPSIE do — unmasking
     * takes what came due meanwhile, once each, through the same path as
     * {@code __enable_irq}. Setting the state it already has changes nothing.
     */
    private static void setPrimask(Invocation c) {
        if ((c.arg(0) & 1) != 0)
            disableIrq(c);
        else
            enableIrq(c);
    }

    /**
     * WFE: if the event register is set (by {@code __SEV}, or by any exception taken since
     * the last WFE), clear it and go on; otherwise sleep as {@link #waitForInterrupt}
     * does, the wake-up consuming the event the interrupt raised. One simplification:
     * a pending interrupt wakes it even behind PRIMASK, as it would with SCR.SEVONPEND
     * set (STM32duino leaves it clear, so on the chip such a WFE sleeps on).
     */
    private static void waitForEvent(Invocation c) {
        c.charge(CycleClass.ALU, 1);
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        if (chip.takeEvent())
            return;
        sleepUntilInterrupt(c, chip);
    }

    /**
     * WFI: the core sleeps until an interrupt is pending — taken or not, since a
     * pending interrupt wakes it even behind PRIMASK, and WFI does not sleep at all
     * while one already is. SysTick fires every millisecond, so it never sleeps longer
     * than that. The sleep is charged as WAIT; ISRs taken during it are their own.
     *
     * One simplification: an EXTI interrupt left pending behind PRIMASK before the
     * WFI does not stop it sleeping (the core's pending set is not visible from here);
     * the next SysTick then wakes it, within a millisecond.
     */
    private static void waitForInterrupt(Invocation c) {
        c.charge(CycleClass.ALU, 1);
        Stm32f103 chip = CoreState.chip(c);
        chip.sync();
        sleepUntilInterrupt(c, chip);
    }

    /** The sleep WFI and WFE share: parked until an interrupt is taken or pends. */
    private static void sleepUntilInterrupt(Invocation c, Stm32f103 chip) {
        if (chip.interruptPending())
            return;
        long start = chip.interruptSerial();
        c.block(new Blocker() {
            @Override
            public long nextPollCycle() {
                if (chip.interruptSerial() != start)
                    return chip.now();
                return Math.max(chip.now(), chip.peekNextInterruptCycle());
            }

            @Override
            public boolean poll(Invocation call) {
                chip.sync();
                return chip.interruptSerial() != start || chip.interruptPending();
            }
        });
    }

    /**
     * NVIC_SystemReset: DSB, write SYSRESETREQ to AIRCR, DSB, spin until the reset
     * lands. The chip then reboots from flash (see {@link Stm32f103#requestSystemReset()}).
     */
    private static void systemReset(Invocation c) {
        Cost.call(c);
        c.charge(CycleClass.ALU, 4);
        c.charge(CycleClass.PERIPHERAL, 2);
        throw CoreState.chip(c).requestSystemReset();
    }

    private IoBuiltins() {}
}
