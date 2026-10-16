package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoPins;
import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CompiledProgram;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.arduino.ArduinoCore;
import com.nectariich.create_flippies.content.plc.mcu.chip.Stm32f103;
import com.nectariich.create_flippies.content.plc.mcu.lang.SketchCompiler;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Real sketches, end to end: the real toolchain ({@link SketchCompiler} against
 * {@link ArduinoCore}), flashed into the real chip model ({@link Stm32f103}), run in
 * 50 ms windows exactly as {@code PlcSmallControl} runs it once per server tick, and
 * judged only by what the board can see — {@link PinDrive}s, states, faults, power.
 *
 * Each of the three halves has its own tests against stand-ins for the other two;
 * these are the ones that would catch the seams between them: a builtin the CPU
 * calls with the wrong argument layout, a fast-forward that skips an edge the chip
 * scheduled, a fault that reaches the board as an exception instead of a state.
 *
 * Several sketches report a number they computed by holding it on the outputs: see
 * {@link #SHOW_WORD}. That keeps the tests honest about the interface — nothing here
 * reads the emulated SRAM — at the price of a few hundred emulated milliseconds.
 */
class PlcSketchTest {

    /** One server tick. */
    private static final long WINDOW = 50_000_000L;
    /** The host budget PlcSmallControl gives a PLC per tick. */
    private static final long BUDGET = 2_000_000L;
    /** For the tests about what a sketch computes rather than how fast: never drop time. */
    private static final long UNLIMITED = 10_000_000_000L;

    private static final int DO0 = ArduinoPins.FIRST_DO;
    private static final int DI0 = ArduinoPins.FIRST_DI;
    private static final int STLED = ArduinoPins.STATUS;

    /**
     * Holds a 16-bit value on the outputs for as long as the sketch runs: the low byte
     * on DO0..DO7 with STLED low for 100 ms, then the high byte with STLED high for
     * 100 ms, and again. Appended after each sketch that uses it, so it never moves a
     * sketch's own line numbers; the .ino auto-prototypes let setup() call it first.
     */
    private static final String SHOW_WORD = """

            void showWord(unsigned int v) {
              for (int i = 0; i < 8; i++) pinMode(DO0 + i, OUTPUT);
              pinMode(STLED, OUTPUT);
              for (;;) {
                for (int i = 0; i < 8; i++) digitalWrite(DO0 + i, (v >> i) & 1);
                digitalWrite(STLED, LOW);
                delay(100);
                for (int i = 0; i < 8; i++) digitalWrite(DO0 + i, (v >> (8 + i)) & 1);
                digitalWrite(STLED, HIGH);
                delay(100);
              }
            }
            """;

    // ------------------------------------------------------------------ harness ---

    private static CompileResult compile(String source) {
        return SketchCompiler.compile(source, ArduinoCore.INSTANCE);
    }

    private static String diagnostics(CompileResult r) {
        return r.diagnostics().stream().map(Diagnostic::toString).collect(Collectors.joining("\n"));
    }

    private static CompiledProgram build(String source) {
        CompileResult r = compile(source);
        if (!r.ok())
            fail("does not compile:\n" + diagnostics(r));
        for (Diagnostic d : r.diagnostics())
            assertFalse(d.severity() == Diagnostic.Severity.WARNING, "unexpected warning: " + d);
        return r.program();
    }

    /** A powered chip with the sketch flashed and programming finished: the next window is its first. */
    private static Stm32f103 boot(String source) {
        Stm32f103 chip = new Stm32f103();
        chip.setPowered(true);
        flashAndWait(chip, build(source));
        return chip;
    }

    private static void flashAndWait(Stm32f103 chip, CompiledProgram program) {
        chip.flash(program);
        int windows = 0;
        while (chip.state() == McuState.PROGRAMMING) {
            chip.runFor(WINDOW, BUDGET);
            assertTrue(++windows < 200, "programming never finishes");
        }
        assertEquals(McuState.RUNNING, chip.state());
    }

    private static void run(Stm32f103 chip, int windows) {
        run(chip, windows, UNLIMITED);
    }

    private static void run(Stm32f103 chip, int windows, long budget) {
        for (int i = 0; i < windows; i++)
            chip.runFor(WINDOW, budget);
    }

    private static PinDrive dout(Stm32f103 chip, int n) {
        return chip.pin(DO0 + n);
    }

    private static int outputByte(Stm32f103 chip) {
        int b = 0;
        for (int i = 0; i < 8; i++)
            if (dout(chip, i).levelAtEnd())
                b |= 1 << i;
        return b;
    }

    /** Reads what {@link #SHOW_WORD} is showing. */
    private static int readWord(Stm32f103 chip) {
        int lo = -1;
        for (int i = 0; i < 40; i++) {
            chip.runFor(WINDOW, UNLIMITED);
            assertEquals(McuState.RUNNING, chip.state(), "faulted while showing a value: " + chip.fault());
            boolean high = chip.pin(STLED).levelAtEnd();
            if (!high)
                lo = outputByte(chip);
            else if (lo >= 0)
                return lo | outputByte(chip) << 8;
        }
        return fail("the sketch never showed a word");
    }

    private static void assertNear(double expected, double actual, double tolerance, String what) {
        assertTrue(Math.abs(expected - actual) <= tolerance, what + ": expected " + expected + " ± " + tolerance + ", got " + actual);
    }

    /** The 1-based line of the first line containing {@code marker}. */
    private static int lineOf(String source, String marker) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++)
            if (lines[i].contains(marker))
                return i + 1;
        return fail("no line marked " + marker);
    }

    // --------------------------------------------------------------- digital I/O ---

    @Test
    void blinkWithDelayTogglesOnTheRightWindows() {
        Stm32f103 chip = boot("""
                void setup() { pinMode(DO0, OUTPUT); }
                void loop() {
                  digitalWrite(DO0, HIGH);
                  delay(500);
                  digitalWrite(DO0, LOW);
                  delay(500);
                }
                """);
        // window k covers [50k, 50k + 50) ms; the toggles fall a few microseconds after 0, 500, 1000 ms
        for (int k = 0; k < 40; k++) {
            run(chip, 1);
            boolean high = (k / 10) % 2 == 0;
            PinDrive d = dout(chip, 0);
            assertTrue(d.output(), "DO0 is an output");
            assertEquals(high, d.levelAtEnd(), "DO0 at the end of window " + k);
            assertNear(high ? 1 : 0, d.highFraction(), 0.001, "DO0 duty in window " + k);
            assertEquals(0, d.pwmHz());
        }
        assertEquals(2000, chip.uptimeMillis());
        assertEquals(1.0, chip.speed());
        assertTrue(chip.cpuLoad() < 0.01, "delay() is WAIT: load " + chip.cpuLoad());
    }

    @Test
    void millisPollingBlinkKeepsTimeAndCostsLittle() {
        Stm32f103 chip = boot("""
                unsigned long last;
                bool on;
                void setup() { pinMode(DO1, OUTPUT); }
                void loop() {
                  if (millis() - last >= 250) {
                    last += 250;
                    on = !on;
                    digitalWrite(DO1, on);
                  }
                }
                """);
        for (int k = 0; k < 40; k++) {
            run(chip, 1, BUDGET);
            boolean high = (k / 5) % 2 == 1;
            assertEquals(high, dout(chip, 1).levelAtEnd(), "DO1 at the end of window " + k);
            assertNear(high ? 1 : 0, dout(chip, 1).highFraction(), 0.001, "DO1 duty in window " + k);
            assertEquals(1.0, chip.speed(), "keeps real time in window " + k);
        }
        // warm, the whole 50 ms of polling must cost the server a sliver of a tick
        long t0 = System.nanoTime();
        run(chip, 100, BUDGET);
        double hostMillis = (System.nanoTime() - t0) / 1e6 / 100;
        assertTrue(hostMillis < 1.0, "host time per window " + hostMillis + " ms");
        assertEquals(1.0, chip.speed());
    }

    @Test
    void buttonOnDi0DrivesDo0() {
        Stm32f103 chip = boot("""
                void setup() {
                  pinMode(DI0, INPUT);
                  pinMode(DO0, OUTPUT);
                }
                void loop() {
                  digitalWrite(DO0, digitalRead(DI0));
                }
                """);
        run(chip, 2, BUDGET);
        assertFalse(dout(chip, 0).levelAtEnd());
        for (int round = 0; round < 3; round++) {
            chip.setInput(DI0, true);
            run(chip, 1, BUDGET);
            assertTrue(dout(chip, 0).levelAtEnd(), "pressed");
            assertNear(1, dout(chip, 0).highFraction(), 0.001, "an input edge is seen at the window's start");
            chip.setInput(DI0, false);
            run(chip, 1, BUDGET);
            assertFalse(dout(chip, 0).levelAtEnd(), "released");
            assertNear(0, dout(chip, 0).highFraction(), 0.001, "released for the whole window");
        }
        assertEquals(1.0, chip.speed());
    }

    @Test
    void tightPollingLoopIsFastForwarded() {
        Stm32f103 chip = boot("""
                unsigned int presses;
                void setup() { pinMode(DI3, INPUT); pinMode(DO3, OUTPUT); }
                void loop() {
                  while (digitalRead(DI3) == LOW) {}
                  presses++;
                  digitalWrite(DO3, presses & 1);
                  while (digitalRead(DI3) == HIGH) {}
                }
                """);
        run(chip, 20, BUDGET); // JIT warm-up
        long t0 = System.nanoTime();
        run(chip, 100, BUDGET);
        double hostMillis = (System.nanoTime() - t0) / 1e6 / 100;
        assertTrue(hostMillis < 1.0, "host time per window " + hostMillis + " ms");
        assertEquals(1.0, chip.speed(), "an idle poll keeps real time on the 2 ms budget");
        assertFalse(dout(chip, 3).levelAtEnd());
        chip.setInput(3, true);
        run(chip, 1, BUDGET);
        assertTrue(dout(chip, 3).levelAtEnd(), "the edge woke the fast-forwarded loop");
        chip.setInput(3, false);
        run(chip, 1, BUDGET);
        chip.setInput(3, true);
        run(chip, 1, BUDGET);
        assertFalse(dout(chip, 3).levelAtEnd(), "second press");
    }

    @Test
    void attachInterruptCountsEdges() {
        Stm32f103 chip = boot("""
                volatile unsigned int rising;
                volatile unsigned int changes;
                void onRise() { rising++; }
                void onChange() { changes++; }
                void setup() {
                  for (int i = 0; i < 8; i++) pinMode(DO0 + i, OUTPUT);
                  attachInterrupt(digitalPinToInterrupt(DI1), onRise, RISING);
                  attachInterrupt(digitalPinToInterrupt(DI2), onChange, CHANGE);
                }
                void loop() {
                  for (int i = 0; i < 4; i++) digitalWrite(DO0 + i, (rising >> i) & 1);
                  for (int i = 0; i < 4; i++) digitalWrite(DO4 + i, (changes >> i) & 1);
                  delay(1);
                }
                """);
        run(chip, 2);
        for (int edge = 0; edge < 5; edge++) {
            chip.setInput(1, true);
            chip.setInput(2, true);
            run(chip, 1);
            chip.setInput(1, false);
            chip.setInput(2, false);
            run(chip, 1);
        }
        assertEquals(5, outputByte(chip) & 0xF, "five rising edges on DI1");
        assertEquals(10, outputByte(chip) >> 4, "ten changes on DI2");
        assertEquals(McuState.RUNNING, chip.state());
    }

    // ---------------------------------------------------------------- analog out ---

    @Test
    void analogWriteOnTimerPinsIsHardwarePwm() {
        Stm32f103 chip = boot("""
                void setup() {
                  analogWrite(DO0, 64);   // PB0, TIM1_CH2N
                  analogWrite(DO6, 191);  // PB6, TIM4_CH1
                  analogWrite(DO7, 255);  // PB7: full scale is a static level
                }
                void loop() {}
                """);
        run(chip, 3);
        PinDrive pb0 = dout(chip, 0);
        PinDrive pb6 = dout(chip, 6);
        assertTrue(pb0.output() && pb6.output());
        assertNear(64 / 255.0, pb0.highFraction(), 0.005, "PB0 duty");
        assertNear(191 / 255.0, pb6.highFraction(), 0.005, "PB6 duty");
        assertEquals(1000, pb0.pwmHz(), 1, "STM32duino's default PWM frequency");
        assertEquals(1000, pb6.pwmHz(), 1);
        assertNear(1, dout(chip, 7).highFraction(), 0.001, "255 of 255");
    }

    @Test
    void analogWriteOnAPinWithoutATimerIsAThreshold() {
        // wiring_analog.c: a pin with no PWM is driven LOW below 128 (of 8-bit full scale), HIGH from 128
        Stm32f103 chip = boot("""
                void setup() {
                  analogWrite(DO2, 127);
                  analogWrite(DO3, 128);
                }
                void loop() {}
                """);
        run(chip, 2);
        assertTrue(dout(chip, 2).output(), "analogWrite makes the pin an output");
        assertFalse(dout(chip, 2).levelAtEnd(), "127 is LOW");
        assertNear(0, dout(chip, 2).highFraction(), 0.001, "127 is LOW");
        assertTrue(dout(chip, 3).levelAtEnd(), "128 is HIGH");
        assertNear(1, dout(chip, 3).highFraction(), 0.001, "128 is HIGH");
        assertEquals(0, dout(chip, 3).pwmHz());
    }

    @Test
    void toneIsASquareWaveThatStopsItself() {
        Stm32f103 chip = boot("""
                void setup() { tone(DO4, 500, 200); }
                void loop() {}
                """);
        run(chip, 1);
        assertNear(0.5, dout(chip, 4).highFraction(), 0.01, "tone is 50 % duty");
        assertEquals(500, dout(chip, 4).pwmHz(), 1);
        run(chip, 2);
        assertEquals(500, dout(chip, 4).pwmHz(), 1, "still sounding at 150 ms");
        run(chip, 2);
        assertEquals(0, dout(chip, 4).pwmHz(), "stopped after its 200 ms");
        assertNear(0, dout(chip, 4).highFraction(), 0.001, "and left low");
    }

    // ----------------------------------------------------------------- timing in ---

    @Test
    void pulseInMeasuresAPulseToTheWindow() {
        Stm32f103 chip = boot("""
                void setup() { pinMode(DI2, INPUT); }
                void loop() {
                  unsigned long w = pulseIn(DI2, HIGH, 3000000UL);
                  if (w > 0) showWord(w / 1000);
                }
                """ + SHOW_WORD);
        run(chip, 2);
        chip.setInput(2, true);
        run(chip, 3);
        chip.setInput(2, false);
        int ms = readWord(chip);
        // edges are sampled at window boundaries, so the pulse is three windows long to the
        // microsecond; pulseIn's own polling may shave a fraction of a millisecond off it
        assertTrue(ms == 149 || ms == 150, "pulse width " + ms + " ms");
    }

    @Test
    void shiftOutHasTheBitBangedCost() {
        Stm32f103 chip = boot("""
                void setup() {
                  pinMode(DO0, OUTPUT);
                  pinMode(DO1, OUTPUT);
                  unsigned long t0 = micros();
                  for (int i = 0; i < 100; i++) shiftOut(DO0, DO1, MSBFIRST, 0xA5);
                  unsigned long dt = micros() - t0;
                  showWord(dt);
                }
                void loop() {}
                """ + SHOW_WORD);
        int micros = readWord(chip);
        // 24 digitalWrite()s per byte through STM32duino's HAL path at 40-70 cycles each plus
        // the loop: 13-25 us a byte at 72 MHz
        double perByte = micros / 100.0;
        assertTrue(perByte >= 10 && perByte <= 40, "shiftOut takes " + perByte + " us per byte");
    }

    @Test
    void shiftOutDrivesTheDataPattern() {
        Stm32f103 chip = boot("""
                void setup() {
                  pinMode(DO0, OUTPUT);
                  pinMode(DO1, OUTPUT);
                  shiftOut(DO0, DO1, LSBFIRST, 0x80);
                }
                void loop() {}
                """);
        run(chip, 1);
        assertTrue(dout(chip, 0).levelAtEnd(), "LSB first: the last bit out is bit 7");
        assertFalse(dout(chip, 1).levelAtEnd(), "the clock idles low");
        assertTrue(dout(chip, 1).highFraction() > 0, "but it did pulse");
        assertTrue(dout(chip, 1).highFraction() < 0.001, "for eight short pulses");
    }

    // ------------------------------------------------------------- computation ---

    /** newlib's random(): a 64-bit LCG seeded with 1, returning bits 32..62. */
    private static long[] newlibRandom(long seed, int n) {
        long next = seed;
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            next = next * 6364136223846793005L + 1;
            out[i] = (next >>> 32) & 0x7FFFFFFFL;
        }
        return out;
    }

    @Test
    void randomMatchesNewlibAndIsReproducible() {
        String source = """
                void setup() {
                  long a = random(65536);
                  showWord(a);
                }
                void loop() {}
                """ + SHOW_WORD;
        Stm32f103 chip = boot(source);
        int first = readWord(chip);
        assertEquals(newlibRandom(1, 1)[0] % 65536, first, "unseeded random() is newlib's sequence from seed 1");

        chip.reset();
        assertEquals(first, readWord(chip), "a reset starts the same sequence again");

        Stm32f103 seeded = boot("""
                void setup() {
                  randomSeed(42);
                  random(10);
                  showWord(random(1000, 61000));
                }
                void loop() {}
                """ + SHOW_WORD);
        long[] r = newlibRandom(42, 2);
        assertEquals(r[1] % 60000 + 1000, readWord(seeded), "randomSeed(42), second draw, random(min, max)");
    }

    @Test
    void stringsBuildAndCompare() {
        Stm32f103 chip = boot("""
                void setup() {
                  String s = "PLC";
                  s += 42;
                  s += '-';
                  s += String(3.14159, 2);
                  s = s + " " + String(255, HEX);
                  String t = s.substring(3, 5);
                  int n = t.toInt();
                  bool same = s == "PLC42-3.14 ff";
                  showWord((same ? 0x8000 : 0) | (s.length() << 8) | n);
                }
                void loop() {}
                """ + SHOW_WORD);
        int w = readWord(chip);
        assertEquals(0x8000, w & 0x8000, "built \"PLC42-3.14 ff\"");
        assertEquals(13, (w >> 8) & 0x7F, "length");
        assertEquals(42, w & 0xFF, "substring(3, 5).toInt()");
    }

    @Test
    void structsAndPointersBehaveLikeArmGcc() {
        Stm32f103 chip = boot("""
                struct Sample {
                  uint8_t channel;
                  int16_t value;
                  uint32_t stamp;
                };
                Sample samples[4] = {{0, -5, 10}, {1, 300, 20}, {2, 7, 30}, {3, -2, 40}};
                long total(const Sample *p, int n) {
                  long sum = 0;
                  for (const Sample *end = p + n; p != end; ++p) sum += p->value;
                  return sum;
                }
                void bump(Sample &s) { s.value += 100; }
                void setup() {
                  bump(samples[2]);
                  Sample *q = &samples[1];
                  q[1].stamp += sizeof(Sample);    // 8 on ARM: 1 + pad + 2 + 4
                  long t = total(samples, 4);      // -5 + 300 + 107 - 2 = 400
                  char c = -1;                     // unsigned on ARM: 255
                  showWord(t | (samples[2].stamp == 38 ? 0x1000 : 0) | (c > 0 ? 0x2000 : 0));
                }
                void loop() {}
                """ + SHOW_WORD);
        int w = readWord(chip);
        assertEquals(400, w & 0xFFF, "pointer walk over a struct array");
        assertTrue((w & 0x1000) != 0, "sizeof(struct) is 8 under AAPCS");
        assertTrue((w & 0x2000) != 0, "plain char is unsigned on ARM");
    }

    // -------------------------------------------------------------------- faults ---

    @Test
    void stackOverflowIsAHardFaultOnItsLineAndFreezesTheOutputs() {
        String source = """
                int depth(int n) {
                  volatile char buf[64];
                  buf[0] = n;
                  return depth(n + 1) + buf[0]; // RECURSE
                }
                void setup() {
                  pinMode(DO5, OUTPUT);
                  digitalWrite(DO5, HIGH);
                  delay(100);
                  depth(0);
                }
                void loop() {}
                """;
        Stm32f103 chip = boot(source);
        run(chip, 1);
        assertEquals(McuState.RUNNING, chip.state());
        run(chip, 2);
        assertEquals(McuState.HARD_FAULT, chip.state());
        HardFault f = chip.fault();
        assertNotNull(f);
        assertEquals(HardFault.Kind.STACK_OVERFLOW, f.kind(), f.getMessage());
        int line = chip.faultLine();
        assertTrue(line >= 1 && line <= lineOf(source, "RECURSE"), "fault line " + line + " is inside depth()");
        run(chip, 2);
        assertEquals(McuState.HARD_FAULT, chip.state(), "a HardFault is forever");
        assertTrue(dout(chip, 5).levelAtEnd(), "STM32duino's while(1) handler leaves the outputs as they were");
        assertNear(1, dout(chip, 5).highFraction(), 0.001, "frozen high");
        assertEquals(1.0, chip.cpuLoad(), "the b . loop is all BRANCH");
    }

    @Test
    void nullPointerIsABusFaultOnItsLine() {
        String source = """
                int *target;
                void setup() { pinMode(DO0, OUTPUT); }
                void loop() {
                  digitalWrite(DO0, HIGH);
                  int v = *target; // NULL
                  digitalWrite(DO0, v);
                }
                """;
        Stm32f103 chip = boot(source);
        run(chip, 1);
        assertEquals(McuState.HARD_FAULT, chip.state());
        assertEquals(HardFault.Kind.BUS_FAULT, chip.fault().kind(), chip.fault().getMessage());
        assertEquals(lineOf(source, "NULL"), chip.faultLine());
        assertTrue(dout(chip, 0).levelAtEnd(), "frozen with the HIGH written before the fault");

        chip.reset();
        assertEquals(McuState.RUNNING, chip.state(), "a reset gets out of HardFault");
        assertNull(chip.fault());
        assertEquals(0, chip.faultLine());
    }

    // ---------------------------------------------------------------- link errors ---

    @Test
    void flashOverflowIsALinkError() {
        CompileResult r = compile("""
                const uint8_t table[140000] PROGMEM = {1, 2, 3};
                void setup() {}
                void loop() { analogWrite(DO0, pgm_read_byte(&table[millis() % 140000])); }
                """);
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                && d.message().contains("region `FLASH' overflowed by")), diagnostics(r));
    }

    @Test
    void ramOverflowIsALinkError() {
        CompileResult r = compile("""
                uint8_t buffer[20000];
                void setup() {}
                void loop() { buffer[millis() % 20000]++; }
                """);
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                && d.message().contains("region `RAM' overflowed by")), diagnostics(r));
    }

    @Test
    void serialIsNotDeclared() {
        String source = """
                void setup() {
                  Serial.begin(9600);
                }
                void loop() {}
                """;
        CompileResult r = compile(source);
        assertFalse(r.ok());
        Diagnostic error = r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                .findFirst().orElseThrow();
        assertEquals(2, error.line());
        assertTrue(error.message().contains("'Serial' was not declared in this scope"), error.message());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.NOTE
                && d.message().toLowerCase().contains("serial port")), diagnostics(r));
    }

    @Test
    void communicationLibrariesAreRefused() {
        CompileResult r = compile("""
                #include <Wire.h>
                void setup() {}
                void loop() {}
                """);
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("not available on this PLC")), diagnostics(r));
    }

    // ------------------------------------------------------- reset, flash, power ---

    @Test
    void resetRebootsAndReflashReplacesTheProgram() {
        Stm32f103 chip = boot("""
                void setup() { pinMode(DO0, OUTPUT); digitalWrite(DO0, HIGH); }
                void loop() { if (millis() >= 300) digitalWrite(DO0, LOW); }
                """);
        run(chip, 8);
        assertFalse(dout(chip, 0).levelAtEnd(), "off after 300 ms");
        assertEquals(400, chip.uptimeMillis());

        chip.reset();
        assertEquals(0, chip.uptimeMillis());
        run(chip, 1);
        assertTrue(dout(chip, 0).levelAtEnd(), "setup() ran again");

        chip.setResetHeld(true);
        assertEquals(McuState.IN_RESET, chip.state());
        run(chip, 2);
        assertFalse(dout(chip, 0).output(), "NRST held: every pin back to its reset state");
        chip.setResetHeld(false);
        run(chip, 1);
        assertTrue(dout(chip, 0).levelAtEnd());

        CompiledProgram second = build("""
                void setup() { pinMode(DO1, OUTPUT); digitalWrite(DO1, HIGH); }
                void loop() {}
                """);
        chip.flash(second);
        assertEquals(McuState.PROGRAMMING, chip.state());
        run(chip, 1);
        assertFalse(dout(chip, 0).output(), "held in reset while programming");
        assertTrue(chip.programmingProgress() > 0 && chip.programmingProgress() < 1);
        while (chip.state() == McuState.PROGRAMMING)
            run(chip, 1);
        run(chip, 1);
        assertFalse(dout(chip, 0).output(), "the old program is gone");
        assertTrue(dout(chip, 1).levelAtEnd(), "the new one runs");

        chip.flash(null);
        assertEquals(McuState.NO_PROGRAM, chip.state());
        run(chip, 1);
        assertFalse(dout(chip, 1).output());
    }

    @Test
    void powerLossLosesSram() {
        Stm32f103 chip = boot("""
                unsigned int count;
                bool was;
                void setup() { pinMode(DI0, INPUT); for (int i = 0; i < 8; i++) pinMode(DO0 + i, OUTPUT); }
                void loop() {
                  bool now = digitalRead(DI0);
                  if (now && !was) count++;
                  was = now;
                  for (int i = 0; i < 8; i++) digitalWrite(DO0 + i, (count >> i) & 1);
                }
                """);
        for (int i = 0; i < 3; i++) {
            chip.setInput(DI0, true);
            run(chip, 1);
            chip.setInput(DI0, false);
            run(chip, 1);
        }
        assertEquals(3, outputByte(chip));

        chip.setPowered(false);
        assertEquals(McuState.UNPOWERED, chip.state());
        run(chip, 2);
        assertEquals(0, chip.mcuPowerWatts());
        assertEquals(PinDrive.OFF, dout(chip, 0), "an unpowered chip drives nothing");

        chip.setPowered(true);
        assertEquals(McuState.RUNNING, chip.state(), "boots from flash when power returns");
        run(chip, 1);
        assertEquals(0, outputByte(chip), "the count was in SRAM");
        chip.setInput(DI0, true);
        run(chip, 1);
        assertEquals(1, outputByte(chip));
    }

    @Test
    void busyCodeDrawsDatasheetPower() {
        Stm32f103 chip = boot("""
                volatile uint32_t sink;
                void loop() {
                  uint32_t crc = 0xFFFFFFFF;
                  for (uint32_t i = 0; i < 1000; i++) {
                    crc ^= i;
                    for (int k = 0; k < 8; k++) crc = (crc >> 1) ^ (0xEDB88320UL & (0 - (crc & 1)));
                  }
                  sink = crc;
                }
                void setup() {}
                """);
        run(chip, 3);
        assertEquals(1.0, chip.speed(), "unlimited budget: the whole window ran");
        assertTrue(chip.cpuLoad() > 0.99, "CPU-bound: load " + chip.cpuLoad());
        double watts = chip.mcuPowerWatts();
        // DS5319: 27 mA (peripherals off) to 36 mA (all on) at 72 MHz from flash, 3.3 V
        assertTrue(watts >= 0.08 && watts <= 0.15, "MCU power " + watts + " W");

        // and on the server's 2 ms budget it falls behind instead of stalling the tick
        long t0 = System.nanoTime();
        run(chip, 5, BUDGET);
        double hostMillis = (System.nanoTime() - t0) / 1e6 / 5;
        assertTrue(chip.speed() < 1.0, "a CPU-bound sketch cannot run at 72 MHz in 2 ms");
        assertTrue(hostMillis < 10, "budget respected: " + hostMillis + " ms per window");
        assertEquals(McuState.RUNNING, chip.state());
    }

    @Test
    void idleCodeDrawsLessThanBusyCode() {
        Stm32f103 idle = boot("""
                void setup() {}
                void loop() { delay(1000); }
                """);
        run(idle, 3);
        double watts = idle.mcuPowerWatts();
        assertTrue(watts > 0.05 && watts < 0.1, "delay() spins at full clock: " + watts + " W");
        assertTrue(idle.cpuLoad() < 0.01);
    }

    // ------------------------------------------------------ chip through the board ---

    @Test
    void boardAppliesTheChipsOutputsByItsRules() {
        Stm32f103 chip = boot("""
                void setup() {
                  pinMode(DO0, OUTPUT);
                  digitalWrite(DO0, HIGH);
                  pinMode(DO1, INPUT_PULLUP);   // ODR set, but an input: the driver stays off
                  analogWrite(DO6, 128);
                  pinMode(STLED, OUTPUT);
                  digitalWrite(STLED, HIGH);
                }
                void loop() {}
                """);
        PlcBoard board = new PlcBoard();
        board.updateSupply(24);
        run(chip, 2);
        assertEquals(1.0, board.outputDuty(PlcBoard.CH_DO0, dout(chip, 0), chip.state()));
        assertEquals(0.0, board.outputDuty(PlcBoard.CH_DO0 + 1, dout(chip, 1), chip.state()), "INPUT_PULLUP drives nothing");
        assertNear(128 / 255.0, board.outputDuty(PlcBoard.CH_DO0 + 6, dout(chip, 6), chip.state()), 0.005, "PWM as its average");
        assertEquals(1.0, board.outputDuty(PlcBoard.CH_STLED, chip.pin(STLED), chip.state()));

        double[] amps = new double[PlcBoard.CHANNELS];
        amps[PlcBoard.CH_DO0] = 1.5;
        assertTrue(board.checkOvercurrent(amps));
        assertEquals(0.0, board.outputDuty(PlcBoard.CH_DO0, dout(chip, 0), chip.state()), "latched off at 1.5 A");
        assertEquals(1.0, board.outputDuty(PlcBoard.CH_STLED, chip.pin(STLED), chip.state()), "other channels unaffected");

        chip.setResetHeld(true);
        assertTrue(board.updateReset(24));
        run(chip, 1);
        assertEquals(0.0, board.outputDuty(PlcBoard.CH_STLED, chip.pin(STLED), chip.state()), "reset turns everything off");
        chip.setResetHeld(false);
        board.updateReset(0);
        run(chip, 1);
        // the reboot spends the first few microseconds of the window in setup() before DO0 goes high
        assertNear(1.0, board.outputDuty(PlcBoard.CH_DO0, dout(chip, 0), chip.state()), 0.001, "reset cleared the latch");

        PlcBoard.Power p = board.power(24, chip.mcuPowerWatts(), 0, 3, 0, 0, 0);
        assertTrue(p.logicDraw() > chip.mcuPowerWatts() / PlcBoard.BUCK_EFFICIENCY, "logic draw includes the buck and the drivers");
        double ohms = PlcBoard.logicLoadResistance(24, p.logicDraw());
        assertNear(p.logicDraw(), 24 * 24 / ohms, 1e-9, "the logic load wire draws exactly the logic power");
    }

    @Test
    void reportedUsageMatchesArduinoCli() {
        CompileResult r = compile("""
                void setup() { pinMode(DO0, OUTPUT); }
                void loop() { digitalWrite(DO0, HIGH); delay(500); digitalWrite(DO0, LOW); delay(500); }
                """);
        assertTrue(r.ok(), diagnostics(r));
        String flash = SketchCompiler.flashUsage(r.program().flashBytes());
        String ram = SketchCompiler.ramUsage(r.program().staticRamBytes());
        assertTrue(flash.matches("Sketch uses \\d+ bytes \\(\\d+%\\) of program storage space\\. Maximum is 131072 bytes\\."), flash);
        assertTrue(ram.matches("Global variables use \\d+ bytes \\(\\d+%\\) of dynamic memory, leaving \\d+ bytes for local variables\\. Maximum is 20480 bytes\\."), ram);
        int flashBytes = r.program().flashBytes();
        assertTrue(flashBytes > 4000 && flashBytes < 20000, "a blink is ~10 KB on STM32duino: " + flashBytes);
    }
}
