package com.nectariich.create_flippies.content.plc.mcu.chip;

import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.R;
import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.WINDOW;
import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.boot;
import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.compile;
import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.cyclesPerCall;
import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.results;
import static com.nectariich.create_flippies.content.plc.mcu.chip.ChipFixTest.runFor;

import java.util.Arrays;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;

/**
 * Regression checks for the second round of review findings on the chip and the
 * Arduino core, through the real toolchain (plus one through {@link FakeCpu}, where
 * the CPU's side of a contract is needed in isolation). Plain main, no Gradle; also
 * run by {@code ChipHarnessTest}.
 */
public final class ChipFix2Test {

    static final long UNLIMITED = ChipFixTest.UNLIMITED;

    static int failures;

    public static void main(String[] args) {
        if (runAll() > 0)
            System.exit(1);
    }

    static int runAll() {
        failures = 0;
        int before = ChipFixTest.failures;
        pullIsOdr();
        flashStringHelper();
        primask();
        floatBinaryMath();
        extiPreemptsLongBuiltins();
        toneHeldByExtiHandler();
        hostBudgetWithShortSegments();
        // the shared helpers count their failures (a sketch that does not compile) there
        failures += ChipFixTest.failures - before;
        System.out.println(failures == 0 ? "chip fixes 2: all passed" : "chip fixes 2: " + failures + " failures");
        return failures;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    // ---- F1/F5: the F1's input pull is ODR -------------------------------------------

    static void pullIsOdr() {
        System.out.println("input pull-up/down through ODR:");
        Stm32f103 chip = boot("pull then output", R + """
                void setup() { pinMode(DO0, INPUT_PULLUP); pinMode(DO0, OUTPUT); }
                void loop() {}
                """, true);
        if (chip != null) {
            chip.runFor(WINDOW, UNLIMITED);
            chip.runFor(WINDOW, UNLIMITED);
            PinDrive d = chip.pin(8);
            check(d.output() && d.levelAtEnd() && d.highFraction() > 0.999,
                    "INPUT_PULLUP then OUTPUT drives DO0 high, ODR still 1: " + d);
        }
        long[] r = runFor("pull follows ODR", R + """
                volatile unsigned long hits;
                void onRise() { hits++; }
                void setup() {
                    pinMode(PA12, INPUT_PULLUP);
                    R[1] = digitalRead(PA12);
                    digitalWrite(PA12, LOW);          // the pull-up becomes a pull-down
                    R[2] = digitalRead(PA12);
                    pinMode(PA8, INPUT_PULLDOWN);
                    digitalWrite(PA8, HIGH);          // and the other way round
                    R[3] = digitalRead(PA8);
                    pinMode(PA9, INPUT_PULLUP);
                    pinMode(PA9, OUTPUT);
                    R[4] = digitalRead(PA9);
                    pinMode(PA10, INPUT_PULLDOWN);
                    attachInterrupt(digitalPinToInterrupt(PA10), onRise, RISING);   // keeps the pull-down
                    R[5] = digitalRead(PA10);
                    digitalWrite(PA10, HIGH);         // pull flips up: a rising edge
                    R[6] = digitalRead(PA10) * 10 + hits;
                    pinMode(PA15, OUTPUT);            // JTDI's pull-up was the debug port's, not ODR
                    R[7] = digitalRead(PA15);
                }
                void loop() {}
                """, 2);
        if (r == null)
            return;
        check(r[1] == 1 && r[2] == 0, "INPUT_PULLUP reads 1, then 0 after digitalWrite(LOW): " + r[1] + ", " + r[2]);
        check(r[3] == 1, "INPUT_PULLDOWN then digitalWrite(HIGH) reads 1: " + r[3]);
        check(r[4] == 1, "INPUT_PULLUP then OUTPUT reads back 1: " + r[4]);
        check(r[5] == 0 && r[6] == 11, "attachInterrupt keeps the pull, which a write then flips, firing RISING: " + r[5] + ", " + r[6]);
        check(r[7] == 0, "PA15 taken over as an output starts LOW: " + r[7]);

        // the pull set by pinMode itself is a change of level an attached line sees
        long[] e = runFor("pinMode pull edge", R + """
                volatile unsigned long hits;
                void onRise() { hits++; }
                void setup() {
                    pinMode(PA12, INPUT_PULLDOWN);
                    attachInterrupt(digitalPinToInterrupt(PA12), onRise, RISING);
                    pinMode(PA12, INPUT_PULLUP);
                    R[1] = hits;
                }
                void loop() {}
                """, 1);
        if (e != null)
            check(e[1] == 1, "INPUT_PULLDOWN to INPUT_PULLUP on a watched pin is one rising edge: " + e[1]);
    }

    // ---- F2: F() is const __FlashStringHelper * ---------------------------------------

    static void flashStringHelper() {
        System.out.println("F() and __FlashStringHelper:");
        long[] r = runFor("F() overloads", R + """
                unsigned long show(const __FlashStringHelper* s) { return 1000 + strlen((const char*)s); }
                unsigned long show(const char* s) { return 2000 + strlen(s); }
                void setup() {
                    R[1] = show(F("abc"));
                    R[2] = show("abcd");
                    const __FlashStringHelper* p = F("hello");
                    R[3] = pgm_read_byte((const char*)p + 1);
                }
                void loop() {}
                """, 1);
        if (r != null) {
            check(r[1] == 1003, "show(F(\"abc\")) takes the __FlashStringHelper overload: " + r[1]);
            check(r[2] == 2004, "show(\"abcd\") takes the const char* one: " + r[2]);
            check(r[3] == 'e', "the pointer is the literal's address: " + r[3]);
        }
        CompileResult bad = compile("void take(const char* s) {} void setup() { take(F(\"x\")); } void loop() {}");
        check(!bad.ok(), "passing F() to a const char* parameter needs a cast, as with g++: " + bad.diagnostics());

        // String's F() overloads are the language runtime's; reported, not failed, if missing
        CompileResult str = compile(R + """
                void setup() { String s = F("abc"); s += F("de"); s = F("xyz") ; s.concat(F("1")); String t = s + F("2"); R[1] = t.length(); }
                void loop() {}
                """);
        if (!str.ok())
            System.out.println("  NOTE String has no const __FlashStringHelper* overloads yet (LANG): " + str.diagnostics());
        else {
            long[] s = runFor("String from F()", R + """
                    void setup() { String s = F("abc"); s += F("de"); s = F("xyz") ; s.concat(F("1")); String t = s + F("2"); R[1] = t.length(); }
                    void loop() {}
                    """, 1);
            if (s != null)
                check(s[1] == 5, "String takes F() strings: \"xyz12\" is 5 long: " + s[1]);
        }
    }

    // ---- F3: CMSIS PRIMASK, WFE, SEV --------------------------------------------------

    static void primask() {
        System.out.println("__get_PRIMASK/__set_PRIMASK, __WFE/__SEV:");
        long[] r = runFor("primask", R + """
                void setup() {
                    uint32_t outer = __get_PRIMASK();
                    __disable_irq();
                    uint32_t inner = __get_PRIMASK();
                    __disable_irq();                  // a nested section
                    __set_PRIMASK(inner);
                    R[1] = outer * 100 + inner * 10 + __get_PRIMASK();
                    unsigned long m0 = millis();
                    delayMicroseconds(5500);           // five ticks come due, masked
                    R[2] = millis() - m0;
                    __set_PRIMASK(outer);              // unmasked: one is taken
                    R[3] = millis() - m0;
                    R[4] = __get_PRIMASK();
                    __SEV();
                    unsigned long t = micros();
                    __WFE();                           // the event register is set: no sleep
                    R[5] = micros() - t;
                    // with no event set it sleeps until the next SysTick; a WFE right after an
                    // exception returns at once (exception entry sets the event), so try twice
                    for (int k = 0; k < 2 && R[6] <= 2; k++) {
                        t = micros();
                        __WFE();
                        R[6] = micros() - t;
                    }
                    R[7] = sizeof(__get_PRIMASK());
                }
                void loop() {}
                """, 1);
        if (r == null)
            return;
        check(r[1] == 11, "outer 0, inner 1, still 1 after the nested section: " + r[1]);
        check(r[2] == 0 && r[3] == 1, "millis frozen while masked, one tick taken on __set_PRIMASK(0): " + r[2] + ", " + r[3]);
        check(r[4] == 0, "__get_PRIMASK() is 0 again");
        check(r[5] <= 2, "__WFE after __SEV returns at once: " + r[5] + " us");
        check(r[6] > 2 && r[6] <= 1001, "__WFE with no event sleeps until the next SysTick: " + r[6] + " us");
        check(r[7] == 4, "__get_PRIMASK returns a 32-bit value");
    }

    // ---- F4: float pow/atan2/fmod/hypot/fmin/fmax --------------------------------------

    static void floatBinaryMath() {
        System.out.println("float overloads of the two-argument math functions:");
        long[] r = runFor("float binary math", R + """
                volatile float f = 1.5f, g = 2.0f;
                volatile unsigned char b = 2;
                enum E { TWO = 2 };
                void setup() {
                    R[1] = sizeof(pow(f, g)) * 1000 + sizeof(atan2(f, g)) * 100 + sizeof(fmod(f, g)) * 10 + sizeof(hypot(f, g));
                    R[2] = sizeof(fmin(f, g)) * 10 + sizeof(fmax(f, g));
                    R[3] = sizeof(pow(f, 2)) * 1000 + sizeof(pow(2, f)) * 100 + sizeof(pow(f, 2.0)) * 10 + sizeof(pow(2, 8));
                    R[4] = (unsigned long)(pow(2, 8) + pow(b, 3) + pow(2LL, 4) + pow(TWO, 2u) + pow(f, g) * 100 + 0.5);
                    R[5] = (unsigned long)(fmod(7.5f, 2.0f) * 10) * 1000 + (unsigned long)hypot(3.0f, 4.0f) * 100
                            + (unsigned long)fmax(f, 9) * 10 + (unsigned long)fmin(g, 1u);
                    float a = atan2(1.0f, 1.0f);
                    R[6] = (unsigned long)(a * 1e6f + 0.5f);
                }
                void loop() {}
                """, 1);
        if (r != null) {
            check(r[1] == 4444, "pow/atan2/fmod/hypot of floats are float: " + r[1]);
            check(r[2] == 44, "fmin/fmax of floats are float: " + r[2]);
            check(r[3] == 8888, "mixed float/int and int/int stay double, as C++11 <cmath> has them: " + r[3]);
            check(r[4] == 256 + 8 + 16 + 4 + 225, "int, promoted-char, long long and enum arguments resolve: " + r[4]);
            check(r[5] == 15_591, "fmod 1.5, hypot 5, fmax 9, fmin 1: " + r[5]);
            check(r[6] == 785_398, "atan2f(1, 1) = pi/4: " + r[6]);
        }
        double powf = cyclesPerCall("powf", "volatile float x = 1.7f, y = 2.3f; volatile float z;", "z = powf(x, y);", 2000);
        double powFloat = cyclesPerCall("pow(float,float)", "volatile float x = 1.7f, y = 2.3f; volatile float z;", "z = pow(x, y);", 2000);
        double powDouble = cyclesPerCall("pow(double,double)", "volatile double x = 1.7, y = 2.3; volatile double z;", "z = pow(x, y);", 2000);
        check(Math.abs(powFloat - powf) < 0.02 * powf, "pow(float, float) costs what powf does: " + powFloat + " vs " + powf);
        check(powDouble > 1.8 * powFloat, "and about half of pow(double, double): " + powDouble);
    }

    // ---- F6: interrupts preempt long byte loops ---------------------------------------

    static void extiPreemptsLongBuiltins() {
        System.out.println("EXTI latency inside long builtins:");
        String[][] cases = {
                { "memset 12000", "unsigned char buf[12000]; void loop() { memset(buf, R[2]++, sizeof buf); }" },
                { "analogRead", "void loop() { R[2] += analogRead(A1) + 1; }" },
                { "sprintf %9000d", "char out[9100]; void loop() { R[2] += sprintf(out, \"%9000d\", 7); }" },
        };
        for (String[] c : cases) {
            Stm32f103 chip = boot(c[0], R + """
                    void onEdge() {
                        unsigned long late = micros() % 50000;   // windows start on multiples of 50 ms
                        if (late > 25000) late = 0;              // (an edge is never applied late by half a window)
                        if (late > R[1]) R[1] = late;
                        R[3]++;
                    }
                    void setup() { pinMode(DI0, INPUT); attachInterrupt(digitalPinToInterrupt(DI0), onEdge, CHANGE); }
                    """ + c[1], true);
            if (chip == null)
                continue;
            int toggles = 0;
            for (int w = 0; w < 44; w++) {
                if (w >= 2 && w % 2 == 0) {
                    chip.setInput(0, (toggles & 1) == 0);
                    toggles++;
                }
                chip.runFor(WINDOW, UNLIMITED);
            }
            long[] r = results(chip);
            check(chip.state() == McuState.RUNNING && r[3] == toggles, c[0] + ": every edge is taken: " + r[3] + " of " + toggles);
            check(r[1] <= 5, c[0] + ": worst latency from the window start " + r[1] + " us (12-cycle entry, not the builtin's length)");
            check(r[2] > 0, c[0] + ": the loop runs: " + r[2]);
        }

        // long calls still return what they return, in setup and inside an ISR
        Stm32f103 values = boot("long calls' values", R + """
                unsigned char a[6000], b[6000];
                void onEdge() { R[5] = memset(b, 7, sizeof b) == b; R[6] = memcmp(a, b, sizeof a) < 0; }
                void setup() {
                    pinMode(DI0, INPUT);
                    attachInterrupt(digitalPinToInterrupt(DI0), onEdge, RISING);
                    R[1] = memset(a, 3, sizeof a) == a;
                    R[2] = memcmp(a, b, sizeof a);
                    memcpy(b, a, sizeof a);
                    R[3] = memcmp(a, b, sizeof a) == 0;
                }
                void loop() { R[4] = b[5999]; }
                """, true);
        if (values != null) {
            values.runFor(WINDOW, UNLIMITED);
            values.setInput(0, true);
            values.runFor(WINDOW, UNLIMITED);
            long[] v = results(values);
            check(v[1] == 1 && v[2] == 3 && v[3] == 1, "memset returns its pointer, memcmp its difference: " + v[1] + ", " + v[2] + ", " + v[3]);
            check(v[5] == 1 && v[6] == 1 && v[4] == 7, "and so inside an ISR, whose writes the loop then sees: " + v[5] + ", " + v[6] + ", " + v[4]);
        }

        // the pieces add up to what the call cost in one go
        double memset = cyclesPerCall("memset cost", "static unsigned char buf[4000];", "memset(buf, i, sizeof buf);", 200);
        double expect = 12 + 4000 * 6;
        check(Math.abs(memset - expect) < 0.01 * expect, "memset(4000) still costs its byte loop: " + memset + " cycles, expected about " + expect);
        double analog = cyclesPerCall("analogRead cost 2", "", "sink = analogRead(A0);", 2000);
        check(analog > 2000 && analog < 2600, "analogRead still about 2200 cycles: " + analog);

        // and keep their classes: a memset loop is busy, not waiting
        Stm32f103 busy = boot("memset load", R + "unsigned char buf[12000]; void setup() {} void loop() { memset(buf, R[2]++, sizeof buf); }", true);
        if (busy != null) {
            busy.runFor(WINDOW, UNLIMITED);
            busy.runFor(WINDOW, UNLIMITED);
            long[] cls = busy.lastWindowCycles();
            check(busy.cpuLoad() > 0.99 && cls[CycleClass.STORE.ordinal()] > 0.15 * busy.lastWindowLength(),
                    "a memset loop is CPU load, charged as stores and branches: load " + busy.cpuLoad() + ", " + Arrays.toString(cls));
        }
    }

    // ---- F7: TIM3 below EXTI ----------------------------------------------------------

    static void toneHeldByExtiHandler() {
        System.out.println("tone() frozen inside an EXTI handler:");
        // the chip's side, with a stand-in CPU that says when it is in a handler
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(FakeCpu.step("void tone(unsigned char pin, unsigned int frequency)", 10, 1000));
        p.setup.add(FakeCpu.step("void delay(", 10));
        p.setup.add(cpu -> cpu.inHandler = true);
        p.setup.add(FakeCpu.step("void delay(", 20));
        p.setup.add(cpu -> cpu.inHandler = false);
        long[] ms = new long[1];
        p.setup.add(cpu -> ms[0] = cpu.call("unsigned long millis("));
        Stm32f103 chip = new Stm32f103();
        chip.setPowered(true);
        chip.flash(p);
        while (chip.state() == McuState.PROGRAMMING)
            chip.runFor(WINDOW, UNLIMITED);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, UNLIMITED);
        double held = chip.pin(10).highFraction();
        check(Math.abs(held - 0.5) > 0.15, "the 1 kHz tone stands still for the 20 ms in the handler: duty " + held);
        check(ms[0] >= 29 && ms[0] <= 31, "SysTick (priority 0) still preempts the handler: millis() " + ms[0] + " after 10 + 20 ms");
        chip.runFor(WINDOW, UNLIMITED);
        double after = chip.pin(10).highFraction();
        check(Math.abs(after - 0.5) < 0.01, "and toggles again once it returns: duty " + after);

        // through the real CPU, when it says whether it is in a handler
        Stm32f103 real = boot("tone + slow ISR", R + """
                void onEdge() { unsigned long t = micros(); while (micros() - t < 20000) {} R[1]++; }
                void setup() { tone(DO2, 1000); pinMode(DI0, INPUT); attachInterrupt(digitalPinToInterrupt(DI0), onEdge, RISING); }
                void loop() {}
                """, true);
        if (real == null)
            return;
        boolean probed = Stm32f103.handlerProbe(real.cpu()).getAsBoolean() || hasProbe(real);
        if (!probed) {
            System.out.println("  NOTE the CPU has no public boolean inInterrupt() yet (LANG): end-to-end check skipped");
            return;
        }
        real.runFor(WINDOW, UNLIMITED);
        real.setInput(0, true);
        real.runFor(WINDOW, UNLIMITED);
        double duty = real.pin(10).highFraction();
        check(results(real)[1] == 1 && Math.abs(duty - 0.5) > 0.15, "DO2 freezes for the ISR's 20 ms: duty " + duty);
        real.runFor(WINDOW, UNLIMITED);
        check(Math.abs(real.pin(10).highFraction() - 0.5) < 0.01, "and runs at 50 % after it: " + real.pin(10).highFraction());
    }

    private static boolean hasProbe(Stm32f103 chip) {
        try {
            chip.cpu().getClass().getMethod("inInterrupt");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ---- F8: the host budget holds with short segments --------------------------------

    static void hostBudgetWithShortSegments() {
        System.out.println("host budget with a window cut into short stretches:");
        String[][] cases = {
                { "65 kHz tone", "volatile unsigned long m; void setup(){ tone(DO0, 65535); } void loop(){ m = m * 3 + 1; }" },
                { "EXTI on a 36 MHz PWM pin", """
                        volatile unsigned long m; void isr(){}
                        void setup(){ attachInterrupt(digitalPinToInterrupt(PB6), isr, CHANGE); analogWriteFrequency(36000000);
                                      analogWrite(PB6, 128); noInterrupts(); }
                        void loop(){ m = m * 3 + 1; }""" },
                { "two of them", """
                        volatile unsigned long m; void isr(){}
                        void setup(){ attachInterrupt(digitalPinToInterrupt(PB6), isr, CHANGE); attachInterrupt(digitalPinToInterrupt(PB1), isr, CHANGE);
                                      analogWriteFrequency(36000000); analogWrite(PB6, 128); analogWrite(PB1, 100); noInterrupts(); }
                        void loop(){ m = m * 3 + 1; }""" },
        };
        long budget = 500_000L;
        for (String[] c : cases) {
            Stm32f103 chip = boot(c[0], c[1], true);
            if (chip == null)
                continue;
            int windows = 60;
            long[] host = new long[windows];
            double speed = 1;
            for (int w = 0; w < windows; w++) {
                long t = System.nanoTime();
                chip.runFor(WINDOW, budget);
                host[w] = System.nanoTime() - t;
                speed = Math.min(speed, chip.speed());
            }
            long[] tail = Arrays.copyOfRange(host, windows / 2, windows);
            Arrays.sort(tail);
            long median = tail[tail.length / 2];
            check(chip.state() == McuState.RUNNING, c[0] + ": still running");
            check(median < 4 * budget, c[0] + ": median host time per window " + median / 1000 + " us against a " + budget / 1000 + " us budget");
            check(speed < 1, c[0] + ": and the dropped time shows as speed < 1: " + speed);
        }
    }
}
