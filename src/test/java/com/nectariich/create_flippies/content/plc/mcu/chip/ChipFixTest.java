package com.nectariich.create_flippies.content.plc.mcu.chip;

import java.lang.reflect.Field;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.Memory;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.arduino.ArduinoCore;
import com.nectariich.create_flippies.content.plc.mcu.lang.SketchCompiler;

/**
 * Regression checks for the review findings fixed in the chip and the Arduino core,
 * each through the real toolchain: plain main, no Gradle, also run by
 * {@code ChipHarnessTest}.
 *
 * Sketches report through a global {@code volatile unsigned long R[8]} whose first
 * word is a marker; the harness finds it in SRAM and reads the rest.
 */
public final class ChipFixTest {

    static final long WINDOW = 50_000_000L;
    static final long UNLIMITED = 10_000_000_000L;
    static final int MARK = 0x5EEDC0DE;
    static final String R = "volatile unsigned long R[8] = { 0x5EEDC0DEUL };\n";

    static int failures;

    /** The chip {@link #runFor(String, String, int)} last booted, to run on further. */
    static Stm32f103 lastChip;

    public static void main(String[] args) {
        if (runAll() > 0)
            System.exit(1);
    }

    static int runAll() {
        failures = 0;
        isrTimingIndependentOfFastForward();
        delayWithFastTone();
        extiLatency();
        fastToneDutyAndUptime();
        roundAndAbsAreLibmAndStdAbs();
        newMathFunctions();
        stm32duinoExtras();
        systemReset();
        waitForInterruptAndExit();
        tabIsNotPrintable();
        qsortAndBsearch();
        digitalIoCosts();
        analogReadCost();
        stledHasHardwarePwm();
        printfIsBounded();
        strstrCostIsCountedNotPaid();
        System.out.println(failures == 0 ? "chip fixes: all passed" : "chip fixes: " + failures + " failures");
        return failures;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    // ---- SysTick / tone ISRs charged at their own time ------------------------------

    /**
     * Runs a sketch with fast-forward on and off, raising DI0 at the start of window
     * {@code edgeWindow} (or never, for -1), and returns R[] from both.
     */
    static long[][] bothWays(String name, String source, int windows, int edgeWindow) {
        long[][] out = new long[2][];
        for (int k = 0; k < 2; k++) {
            Stm32f103 chip = boot(name, source, k == 0);
            if (chip == null)
                return null;
            for (int w = 0; w < windows; w++) {
                if (w == edgeWindow)
                    chip.setInput(0, true);
                chip.runFor(WINDOW, UNLIMITED);
            }
            out[k] = results(chip);
        }
        return out;
    }

    static void isrTimingIndependentOfFastForward() {
        System.out.println("ISR timing, fast-forward on and off:");
        String[][] cases = {
                { "poll DI0", "void setup(){ pinMode(DI0, INPUT); while (digitalRead(DI0) == LOW) {} R[1] = micros(); } void loop(){}", "4", "200000" },
                { "poll DI0 with a 60 kHz tone", "void setup(){ pinMode(DI0, INPUT); tone(DO3, 60000); while (digitalRead(DI0) == LOW) {} R[1] = micros(); } void loop(){}", "4", "200000" },
                { "millis() poll with a 7 kHz tone", "void setup(){ tone(DO3, 7000); while (millis() < 20) {} R[1] = micros(); while (millis() < 77) {} R[2] = micros(); } void loop(){}", "-1", "20000" },
        };
        for (String[] c : cases) {
            long[][] r = bothWays(c[0], R + c[1], 6, Integer.parseInt(c[2]));
            if (r == null)
                continue;
            long expect = Long.parseLong(c[3]);
            check(r[0][1] == r[1][1] && r[0][2] == r[1][2], c[0] + ": same result either way " + str(r[0], 1, 3) + " vs " + str(r[1], 1, 3));
            check(r[0][1] >= expect && r[0][1] <= expect + 10, c[0] + ": reacts within 10 us of " + expect + ": " + r[0][1]);
        }
    }

    static void delayWithFastTone() {
        System.out.println("delay(30) under a 20 kHz tone:");
        long[][] r = bothWays("delay", R + "void setup(){ tone(DO3, 20000); unsigned long t = micros(); delay(30); R[1] = micros() - t; } void loop(){}", 2, -1);
        if (r == null)
            return;
        check(r[0][1] == r[1][1], "same either way: " + r[0][1] + " vs " + r[1][1]);
        check(r[0][1] >= 29_000 && r[0][1] <= 30_050, "delay(30) lasts 29..30 ms, as uwTick counts on through the tone ISRs: " + r[0][1]);
    }

    static void extiLatency() {
        System.out.println("EXTI latency at a window start:");
        long[][] r = bothWays("exti", R + """
                void onEdge() { if (R[1] == 0) R[1] = micros(); }
                void setup() { pinMode(DI0, INPUT); attachInterrupt(digitalPinToInterrupt(DI0), onEdge, RISING); }
                void loop() {}
                """, 5, 3);
        if (r == null)
            return;
        check(r[0][1] == r[1][1], "same either way: " + r[0][1] + " vs " + r[1][1]);
        check(r[0][1] >= 150_000 && r[0][1] <= 150_005, "ISR runs within 5 us of the window start (150000): " + r[0][1]);
    }

    static void fastToneDutyAndUptime() {
        System.out.println("tone() duty and uptime with fast tones:");
        for (int hz : new int[] { 1000, 10_000, 20_000, 50_000 }) {
            Stm32f103 chip = boot("tone " + hz, "void setup(){ tone(DO0, " + hz + "); } void loop(){}", true);
            if (chip == null)
                return;
            long upBefore = chip.uptimeMillis();
            double worst = 0;
            for (int w = 0; w < 10; w++) {
                chip.runFor(WINDOW, UNLIMITED);
                worst = Math.max(worst, Math.abs(chip.pin(8).highFraction() - 0.5));
            }
            long up = chip.uptimeMillis() - upBefore;
            check(up == 500, hz + " Hz: 10 windows are 500 ms of uptime: " + up);
            check(worst < 0.005, hz + " Hz: duty within 0.5 % of 0.5 every window (worst off by " + worst + ")");
        }
    }

    // ---- round / abs / libm ---------------------------------------------------------

    static void roundAndAbsAreLibmAndStdAbs() {
        System.out.println("round() and abs() as STM32duino has them:");
        long[] r = runFor("round/abs", R + """
                void setup() {
                    float r = round(12.345 * 10) / 10;
                    R[1] = (unsigned long)(r * 1000 + 0.5);
                    R[2] = sizeof(round(1.5));
                    R[3] = round(1e10) == 1e10;
                    int i = -3;
                    int a = abs(i++);
                    R[4] = a;
                    R[5] = (unsigned long)i;
                    R[6] = (unsigned long)(abs(-2.5) * 10) + (unsigned long)(abs(-1.25f) * 100) * 1000;
                    R[7] = sizeof(abs(-1.5f)) * 100 + sizeof(abs(-1.5)) * 10 + sizeof(abs(-3LL));
                }
                void loop() {}
                """, 1);
        if (r == null)
            return;
        check(r[1] == 12_300, "round(12.345 * 10) / 10 == 12.3, not AVR's 12: " + r[1]);
        check(r[2] == 8, "round(double) returns double: sizeof " + r[2]);
        check(r[3] == 1, "round(1e10) == 1e10, not a saturated long");
        check(r[4] == 3 && r[5] == ((-2) & 0xFFFF_FFFFL), "abs(i++) evaluates once: abs " + r[4] + ", i " + (int) r[5]);
        check(r[6] == 125_025, "abs(double) and abs(float) keep the fraction: " + r[6]);
        check(r[7] == 488, "abs(float), abs(double), abs(long long) keep the argument's type: " + r[7]);

        CompileResult unsigned = compile("void setup() { unsigned int u = 5; int x = abs(u); } void loop() {}");
        check(!unsigned.ok() && unsigned.diagnostics().toString().contains("ambiguous"),
                "abs(unsigned) is ambiguous, as g++ says: " + unsigned.diagnostics());
    }

    static void newMathFunctions() {
        System.out.println("math.h functions sketches use:");
        long[] r = runFor("math", R + """
                void setup() {
                    double s = fmax(1.0, 2.0) + fmin(3.0f, 4.0f) + hypot(3.0, 4.0) + cbrt(27.0) + log2(8.0)
                            + ldexp(1.0, 3) + tanh(0.0) + sinh(0.0) + cosh(0.0) + roundf(2.5f) + lroundf(1.5f);
                    R[1] = (unsigned long)(s + 0.5);
                    R[2] = (unsigned long)lround(-2.5);
                    R[3] = (unsigned long)lround(2.4999);
                    R[4] = (unsigned long)(fmin(NAN, 7.0));
                    R[5] = (unsigned long)(long)(round(-0.5) * 10);
                    R[6] = (unsigned long)(ldexp(3, 4));
                    R[7] = (unsigned long)(sinh(1.0) * 1e6 + cosh(1.0) * 1e3 + 0.5);
                }
                void loop() {}
                """, 1);
        if (r == null)
            return;
        check(r[1] == 30, "fmax+fmin+hypot+cbrt+log2+ldexp+tanh+sinh+cosh+roundf+lroundf == 30: " + r[1]);
        check(r[2] == ((-3) & 0xFFFF_FFFFL) && r[3] == 2, "lround: half away from zero: " + (int) r[2] + ", " + r[3]);
        check(r[4] == 7, "fmin(NaN, 7) == 7");
        check(r[5] == ((-10) & 0xFFFF_FFFFL), "round(-0.5) == -1: " + (int) r[5]);
        check(r[6] == 48, "ldexp(int, int): " + r[6]);
        check(r[7] == Math.round(Math.sinh(1) * 1e6 + Math.cosh(1) * 1e3), "sinh/cosh values: " + r[7]);
    }

    // ---- STM32duino extras ----------------------------------------------------------

    static void stm32duinoExtras() {
        System.out.println("STM32duino core APIs:");
        Stm32f103 chip = boot("extras", R + """
                void setup() {
                    pinMode(DI0, INPUT);
                    pinMode(DO0, OUTPUT);
                    digitalWriteFast(PB_0, HIGH);
                    digitalWriteFast(PB_1, HIGH); // no pinMode: the fast functions do not check
                    R[1] = digitalReadFast(PA_0);
                    R[2] = SystemCoreClock;
                    R[3] = (digitalPinToPinName(DO0) == PB_0) + 2 * (pinNametoDigitalPin(PB_0) == DO0)
                            + 4 * (analogInputToDigitalPin(A3) == 3) + 8 * (digitalPinToBitMask(DO3) == 8)
                            + 16 * (PWM_FREQUENCY == 1000 && PWM_RESOLUTION == 8 && ADC_RESOLUTION == 10)
                            + 32 * (digitalPinToPinName(99) == NC) + 64 * (pinNametoDigitalPin(NC) == NUM_DIGITAL_PINS);
                    unsigned long t0 = HAL_GetTick();
                    HAL_Delay(0);
                    R[4] = HAL_GetTick() - t0;
                    unsigned long m0 = getCurrentMillis();
                    HAL_Delay(5);
                    R[5] = getCurrentMillis() - m0;
                    unsigned long u0 = getCurrentMicros();
                    __NOP(); __DSB(); __ISB(); __DMB();
                    R[6] = getCurrentMicros() - u0;
                    digitalToggleFast(PB_0);
                    R[7] = digitalRead(DO0);
                }
                void loop() {}
                """, true);
        if (chip == null)
            return;
        chip.setInput(0, true);
        chip.runFor(WINDOW, UNLIMITED);
        long[] r = results(chip);
        check(r[1] == 1, "digitalReadFast(PA_0) reads the input");
        check(r[2] == 72_000_000L, "SystemCoreClock == 72 MHz: " + r[2]);
        check(r[3] == 127, "PinName helpers and PWM/ADC macros: " + Long.toBinaryString(r[3]));
        check(r[4] == 1, "HAL_Delay(0) waits for the next tick: " + r[4]);
        check(r[5] == 6, "HAL_Delay(5) waits 5 ms plus one tick: " + r[5]);
        check(r[6] <= 2, "__NOP and the barriers cost a few cycles: " + r[6] + " us");
        check(r[7] == 0, "digitalToggleFast(PB_0) toggled DO0 back low");
        check(chip.pin(9).levelAtEnd() == false && chip.pin(9).highFraction() == 0,
                "digitalWriteFast on an input pin sets ODR only; the pad drives nothing");

        CompileResult port = compile("void setup() { volatile unsigned long* p = portOutputRegister(digitalPinToPort(DO0)); } void loop() {}");
        check(!port.ok() && port.diagnostics().toString().contains("not_available_on_this_PLC"),
                "port registers: a compile error that says why: " + port.diagnostics());
        CompileResult wrongType = compile("void setup() { digitalWriteFast(DO0, HIGH); } void loop() {}");
        check(!wrongType.ok(), "digitalWriteFast takes a PinName, not an Arduino pin number");

        Stm32f103 bad = boot("NC", "void setup() { digitalWriteFast(NC, HIGH); } void loop() {}", true);
        if (bad != null) {
            bad.runFor(WINDOW, UNLIMITED);
            check(bad.state() == McuState.HARD_FAULT && bad.fault().kind() == HardFault.Kind.BUS_FAULT,
                    "digitalWriteFast(NC) bus-faults through get_GPIO_Port's NULL: " + bad.state());
        }
    }

    static void systemReset() {
        System.out.println("NVIC_SystemReset:");
        Stm32f103 chip = boot("reset", """
                void setup() { pinMode(DO0, OUTPUT); }
                void loop() {
                    digitalWrite(DO0, millis() >= 10);
                    if (millis() >= 20)
                        NVIC_SystemReset();
                }
                """, true);
        if (chip == null)
            return;
        long maxUptime = 0;
        double duty = 0;
        for (int w = 0; w < 10; w++) {
            chip.runFor(WINDOW, UNLIMITED);
            maxUptime = Math.max(maxUptime, chip.uptimeMillis());
            if (w >= 2)
                duty += chip.pin(8).highFraction() / 8;
        }
        check(chip.state() == McuState.RUNNING, "still running after rebooting: " + chip.state());
        check(maxUptime <= 21, "every reboot restarts uptime and millis(): max uptime " + maxUptime);
        // each boot: 10 ms low, ~10 ms high, then ~1 ms in reset with the pin floating
        check(duty > 0.40 && duty < 0.52, "DO0 high about half of each 21 ms boot cycle: " + duty);
        check(chip.lastWindowLength() >= 3_590_000, "the clock runs on through the resets: " + chip.lastWindowLength());

        Stm32f103 ctor = boot("reset in setup", R + "void setup() { R[1]++; NVIC_SystemReset(); } void loop() {}", true);
        if (ctor != null) {
            ctor.runFor(WINDOW, UNLIMITED);
            check(ctor.state() == McuState.RUNNING && results(ctor)[1] <= 1 && ctor.uptimeMillis() <= 1,
                    "a reset loop in setup() reboots every millisecond with fresh SRAM: R[1] = " + results(ctor)[1]);
        }
    }

    static void waitForInterruptAndExit() {
        System.out.println("__WFI and exit():");
        long[] r = runFor("wfi", R + """
                void setup() {
                    unsigned long t = micros();
                    __WFI();
                    R[1] = micros() - t;
                    t = micros();
                    noInterrupts();
                    delayMicroseconds(1500); // a tick comes due behind PRIMASK
                    __WFI();                 // and the pending tick wakes it at once
                    interrupts();            // where it is taken, so millis() has not lost it
                    R[2] = micros() - t;
                }
                void loop() { __WFI(); R[3]++; }
                """, 2);
        if (r == null)
            return;
        check(r[1] > 0 && r[1] <= 1001, "__WFI sleeps until the next SysTick: " + r[1] + " us");
        check(r[2] >= 1500 && r[2] <= 1510, "__WFI with a tick pending behind PRIMASK does not sleep: " + r[2] + " us");
        check(r[3] >= 95 && r[3] <= 101, "a __WFI loop wakes once per SysTick: " + r[3] + " wakes in 100 ms");

        Stm32f103 chip = boot("exit", R + """
                void setup() { pinMode(DO0, OUTPUT); digitalWrite(DO0, HIGH); exit(0); digitalWrite(DO0, LOW); }
                void loop() { R[1]++; }
                """, true);
        if (chip == null)
            return;
        for (int w = 0; w < 3; w++)
            chip.runFor(WINDOW, UNLIMITED);
        check(chip.state() == McuState.RUNNING, "exit() is not a fault: " + chip.state());
        check(chip.pin(8).highFraction() == 1.0 && results(chip)[1] == 0, "after exit() nothing more runs and outputs hold");
        check(chip.uptimeMillis() == 150, "the clock (SysTick) runs on: " + chip.uptimeMillis());
    }

    // ---- ctype -------------------------------------------------------------------

    static void tabIsNotPrintable() {
        System.out.println("TAB in <ctype.h>:");
        long[] r = runFor("ctype", R + """
                void setup() {
                    R[1] = isprint('\\t') != 0;
                    R[2] = isPrintable('\\t');
                    R[3] = (isblank('\\t') != 0) + 2 * isWhitespace('\\t') + 4 * (isspace('\\t') != 0) + 8 * (iscntrl('\\t') != 0);
                    R[4] = (isprint(' ') != 0) + 2 * (isblank(' ') != 0) + 4 * (isgraph('\\t') != 0);
                }
                void loop() {}
                """, 1);
        if (r == null)
            return;
        check(r[1] == 0 && r[2] == 0, "isprint('\\t') and isPrintable('\\t') are false");
        check(r[3] == 15, "TAB stays blank, whitespace, space and control: " + r[3]);
        check(r[4] == 3, "space is printable and blank, TAB not graphic: " + r[4]);
    }

    // ---- qsort / bsearch ------------------------------------------------------------

    static void qsortAndBsearch() {
        System.out.println("qsort and bsearch:");
        long[] r = runFor("qsort", R + """
                struct Pair { int key; int tag; };
                int calls;
                int cmpInt(const void* a, const void* b) { calls++; int x = *(const int*)a, y = *(const int*)b; return (x > y) - (x < y); }
                int cmpPair(const void* a, const void* b) { return ((const Pair*)a)->key - ((const Pair*)b)->key; }
                int cmpByte(const void* a, const void* b) { return *(const unsigned char*)a - *(const unsigned char*)b; }
                int v[200];
                Pair p[60];
                unsigned char s[9] = { 9, 3, 7, 1, 8, 2, 6, 4, 5 };
                void setup() {
                    randomSeed(42);
                    unsigned long sum = 0;
                    for (int i = 0; i < 200; i++) { v[i] = random(-1000, 1000); sum += v[i]; }
                    qsort(v, 200, sizeof(int), cmpInt);
                    bool sorted = true;
                    unsigned long after = 0;
                    for (int i = 0; i < 200; i++) { after += v[i]; if (i && v[i - 1] > v[i]) sorted = false; }
                    R[1] = sorted && after == sum;
                    R[2] = calls;
                    for (int i = 0; i < 60; i++) { p[i].key = (i * 37) % 7; p[i].tag = i; }
                    qsort(p, 60, sizeof(Pair), cmpPair);
                    bool pairs = true;
                    for (int i = 1; i < 60; i++) if (p[i - 1].key > p[i].key) pairs = false;
                    R[3] = pairs;
                    qsort(s, 9, 1, cmpByte);
                    R[4] = s[0] * 100000000UL + s[4] * 10000 + s[8];
                    int key = v[123];
                    int* hit = (int*)bsearch(&key, v, 200, sizeof(int), cmpInt);
                    int missing = 5000;
                    R[5] = (hit != 0 && *hit == key) + 2 * (bsearch(&missing, v, 200, sizeof(int), cmpInt) == 0)
                            + 4 * (bsearch(&key, v, 0, sizeof(int), cmpInt) == 0);
                    qsort(v, 0, sizeof(int), cmpInt);
                    qsort(v, 1, sizeof(int), cmpInt);
                }
                void loop() {}
                """, 2);
        if (r == null)
            return;
        check(r[1] == 1, "200 ints sorted, none lost");
        check(r[2] > 1000 && r[2] < 3000, "about n log n comparator calls: " + r[2]);
        check(r[3] == 1, "60 structs with repeated keys sorted (median-of-nine path)");
        check(r[4] == 100_050_009L, "bytes sorted with byte swaps: " + r[4]);
        check(r[5] == 7, "bsearch finds, misses and handles an empty array: " + r[5]);

        CompileResult unused = compile("void setup() {} void loop() {}");
        CompileResult used = compile("int c(const void* a, const void* b) { return 0; } int v[4]; void setup() { qsort(v, 4, 4, c); } void loop() {}");
        if (unused.ok() && used.ok())
            check(used.program().flashBytes() - unused.program().flashBytes() > 200 && unused.program().flashBytes() < 9_800,
                    "qsort costs flash only when used: " + unused.program().flashBytes() + " / " + used.program().flashBytes());
    }

    // ---- costs ------------------------------------------------------------------

    /** Cycles per call of {@code body} (a statement using i), over 20000 calls, less the empty loop. */
    static double cyclesPerCall(String name, String setupLines, String body) {
        return cyclesPerCall(name, setupLines, body, 20000);
    }

    static double cyclesPerCall(String name, String setupLines, String body, int count) {
        long[] r = runFor(name, R + """
                volatile int sink;
                void setup() {
                    %s
                    unsigned long t = micros();
                    for (volatile int i = 0; i < %d; i++) { }
                    R[1] = micros() - t;
                    t = micros();
                    for (volatile int i = 0; i < %d; i++) { %s }
                    R[2] = micros() - t;
                }
                void loop() {}
                """.formatted(setupLines, count, count, body), 3);
        if (r == null)
            return -1;
        return (r[2] - r[1]) * 72.0 / count;
    }

    static void digitalIoCosts() {
        System.out.println("digitalWrite/digitalRead costs:");
        double write = cyclesPerCall("digitalWrite", "pinMode(DO0, OUTPUT);", "digitalWrite(DO0, HIGH);");
        double read = cyclesPerCall("digitalRead", "pinMode(DI0, INPUT);", "sink = digitalRead(DI0);");
        check(write >= 45 && write <= 65, "digitalWrite about 50 cycles: " + write);
        check(read >= 45 && read <= 70, "digitalRead about 55 cycles: " + read);
    }

    static void analogReadCost() {
        System.out.println("analogRead cost:");
        double c = cyclesPerCall("analogRead", "", "sink = analogRead(A0);", 1000);
        check(c >= 1800 && c <= 2300, "analogRead about 25-30 us with calibration and the 1 us stabilisation loop: " + c);
    }

    static void stledHasHardwarePwm() {
        System.out.println("analogWrite(STLED):");
        Stm32f103 chip = boot("stled pwm", """
                void setup() { analogWrite(STLED, 100); analogWrite(DO0, 128); }
                void loop() { if (millis() > 120) pinMode(STLED, OUTPUT); }
                """, true);
        if (chip == null)
            return;
        chip.runFor(WINDOW, UNLIMITED);
        chip.runFor(WINDOW, UNLIMITED);
        PinDrive led = chip.pin(16);
        check(Math.abs(led.highFraction() - 100 / 255.0) < 0.01 && Math.abs(led.pwmHz() - 1000) < 1,
                "STLED is TIM1_CH4: 1 kHz at 39 %: " + led);
        check(Math.abs(chip.pin(8).highFraction() - 128 / 255.0) < 0.01, "DO0 shares TIM1: " + chip.pin(8));
        chip.runFor(WINDOW, UNLIMITED);
        chip.runFor(WINDOW, UNLIMITED);
        check(chip.pin(8).highFraction() == 0, "pinMode(STLED) deletes TIM1 and stops DO0's PWM too: " + chip.pin(8));
    }

    // ---- printf -----------------------------------------------------------------

    static void printfIsBounded() {
        System.out.println("printf with huge widths and precisions:");
        long t0 = System.nanoTime();
        long[] r = runFor("printf", R + """
                char buf[32];
                int n;
                void setup() {
                    R[1] = snprintf(buf, sizeof buf, "%999999999d", 1);
                    R[2] = buf[0] == ' ' && buf[30] == ' ' && buf[31] == 0;
                    R[3] = snprintf(buf, sizeof buf, "%*d", 0x7FFFFFF0, 1);
                    R[4] = snprintf(buf, sizeof buf, "%.300000d", 7);
                    R[5] = buf[0] == '0' && buf[30] == '0';
                    snprintf(buf, 4, "%10d%n|%-5s|", 5, &n, "ab");
                    R[6] = n;
                    R[7] = snprintf(buf, sizeof buf, "%05d|%-4x|%+.3d", -42, 255, 7) * 1000 + (buf[0] == '-') + 10 * (buf[5] == '|');
                }
                void loop() {}
                """, 1);
        if (r == null)
            return;
        // the two huge paddings are charged what the chip's putc loop takes, about 260 s
        // of emulated time between them: run on until setup() has got through them
        Stm32f103 slow = lastChip;
        int windows = 1;
        while (results(slow)[7] == 0 && windows < 8000) {
            slow.runFor(WINDOW, UNLIMITED);
            windows++;
        }
        r = results(slow);
        long host = System.nanoTime() - t0;
        check(windows > 4000, "the paddings took emulated time: " + windows + " windows");
        check(r[1] == 999_999_999L && r[2] == 1, "%999999999d: returns the full length, stores 31 spaces: " + r[1]);
        check(r[3] == 0x7FFF_FFF0L, "%*d with a huge width: " + r[3]);
        check(r[4] == 300_000 && r[5] == 1, "%.300000d: " + r[4]);
        check(r[6] == 10, "%n counts what was not stored: " + r[6]);
        check(r[7] == 15_011, "ordinary formatting unchanged (\"-0042|ff  |+007\"): " + r[7]);
        check(host < 2_000_000_000L, "all of it in reasonable host time: " + host / 1_000_000 + " ms");

        Stm32f103 chip = boot("printf loop", "char buf[32]; void setup() {} void loop() { snprintf(buf, sizeof buf, \"%.65000d\", 1); }", true);
        if (chip != null) {
            chip.runFor(WINDOW, UNLIMITED);
            long start = System.nanoTime();
            for (int w = 0; w < 10; w++)
                chip.runFor(WINDOW, 2_000_000L);
            long perWindow = (System.nanoTime() - start) / 10;
            check(perWindow < 4_000_000L, "%.65000d in loop(): host time per window " + perWindow / 1000 + " us");
            check(chip.speed() > 0.99, "and the chip keeps up: speed " + chip.speed());
        }

        Stm32f103 overrun = boot("sprintf overrun", "char buf[32]; void setup() { sprintf(buf, \"%999999999d\", 1); } void loop() {}", true);
        if (overrun != null) {
            overrun.runFor(WINDOW, UNLIMITED);
            check(overrun.state() == McuState.HARD_FAULT, "sprintf past the end of SRAM bus-faults, it does not run out of host memory: " + overrun.state());
        }
    }

    // ---- strstr -------------------------------------------------------------------

    static void strstrCostIsCountedNotPaid() {
        System.out.println("strstr:");
        long[] r = runFor("strstr", R + """
                char h[] = "abaabaaabaaaab";
                void setup() {
                    R[1] = strstr(h, "aaab") - h;
                    R[2] = strstr(h, "") == h;
                    R[3] = strstr(h, "aaaaab") == 0;
                    R[4] = strstr("", "") != 0 && strstr("", "a") == 0;
                    R[5] = strstr(h, "b") - h;
                    R[6] = strstr(h, "abaabaaabaaaab") == h;
                    R[7] = strstr(h, "abaabaaabaaaabX") == 0;
                }
                void loop() {}
                """, 1);
        if (r != null) {
            check(r[1] == 5, "strstr finds the first match: " + r[1]);
            check(r[2] == 1 && r[3] == 1 && r[4] == 1, "empty needle, no match, empty haystack");
            check(r[5] == 1 && r[6] == 1 && r[7] == 1, "one character, the whole string, one past it");
        }

        Stm32f103 chip = boot("strstr worst case", R + """
                char h[10001];
                char n[5002];
                volatile char* found;
                void setup() {
                    memset(h, 'a', 10000);
                    memset(n, 'a', 5000);
                    n[5000] = 'b';
                    unsigned long t = micros();
                    found = strstr(h, n);
                    R[1] = micros() - t;
                    R[2] = found == 0;
                }
                void loop() { found = strstr(h, n); R[3]++; }
                """, true);
        if (chip == null)
            return;
        long start = System.nanoTime();
        for (int w = 0; w < 400; w++)
            chip.runFor(WINDOW, 2_000_000L);
        long host = System.nanoTime() - start;
        long[] out = results(chip);
        // the naive loop: at each of the 10000 positions, the common prefix plus the comparison that ends it
        long compares = 0;
        for (int p = 0; p < 10_000; p++)
            compares += Math.min(5000, 10_000 - p) + 1;
        double expectUs = (compares * 9 + 10_001 * 7) / 72.0;
        check(out[2] == 1, "no match");
        check(Math.abs(out[1] - expectUs) / expectUs < 0.01,
                "charged as newlib-nano's naive search: " + out[1] + " us, expected about " + (long) expectUs);
        check(host < 400 * 2_000_000L, "and paid for by emulated time, not host time: " + host / 1_000_000 + " ms for 400 windows");
        check(out[3] >= 2 && out[3] <= 6, "the loop's calls advance with emulated time: " + out[3] + " in 20 s");
    }

    /** Boots a sketch, runs it for {@code windows} windows (fast-forward on), returns R[]; null if it does not compile. */
    static long[] runFor(String name, String source, int windows) {
        Stm32f103 chip = boot(name, source, true);
        lastChip = chip;
        if (chip == null)
            return null;
        for (int w = 0; w < windows; w++)
            chip.runFor(WINDOW, UNLIMITED);
        if (chip.state() != McuState.RUNNING)
            check(false, name + ": still running (" + chip.state() + (chip.fault() != null ? ", " + chip.fault().getMessage() : "") + ")");
        return results(chip);
    }

    // ---- harness -----------------------------------------------------------------

    static CompileResult compile(String source) {
        return SketchCompiler.compile(source, ArduinoCore.INSTANCE);
    }

    /** Boots a sketch, or returns null (and fails) when it does not compile. */
    static Stm32f103 boot(String name, String source, boolean fastForward) {
        CompileResult r = compile(source);
        if (!r.ok()) {
            r.diagnostics().forEach(d -> System.out.println("    " + d));
            check(false, name + ": compiles");
            return null;
        }
        Stm32f103 chip = new Stm32f103();
        chip.setPowered(true);
        chip.flash(r.program());
        while (chip.state() == McuState.PROGRAMMING)
            chip.runFor(WINDOW, UNLIMITED);
        setFastForward(chip, fastForward);
        return chip;
    }

    /** The VM's idle fast-forward switch, a package-private field of the LANG agent's Vm. */
    static void setFastForward(Stm32f103 chip, boolean on) {
        CpuCore cpu = chip.cpu();
        if (cpu == null)
            return;
        try {
            Field f = cpu.getClass().getDeclaredField("fastForward");
            f.setAccessible(true);
            f.setBoolean(cpu, on);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no fastForward switch on " + cpu.getClass(), e);
        }
    }

    /** R[0..7] as the sketch left them; R[0] is the marker. Empty when no CPU or no marker. */
    static long[] results(Stm32f103 chip) {
        long[] out = new long[8];
        CpuCore cpu = chip.cpu();
        if (cpu == null)
            return out;
        Memory m = cpu.memory();
        for (long a = Stm32f103cb.SRAM_BASE; a < Stm32f103cb.SRAM_BASE + Stm32f103cb.SRAM_SIZE - 32; a += 4) {
            if (m.read32(a) == MARK) {
                for (int i = 0; i < 8; i++)
                    out[i] = m.read32(a + 4L * i) & 0xFFFF_FFFFL;
                return out;
            }
        }
        return out;
    }

    static String str(long[] r, int from, int to) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = from; i < to; i++)
            sb.append(i > from ? ", " : "").append(r[i]);
        return sb.append(']').toString();
    }
}
