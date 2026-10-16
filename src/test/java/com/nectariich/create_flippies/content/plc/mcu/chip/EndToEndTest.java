package com.nectariich.create_flippies.content.plc.mcu.chip;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.arduino.ArduinoCore;
import com.nectariich.create_flippies.content.plc.mcu.lang.SketchCompiler;

/**
 * Real sketches through the real toolchain and CPU on the chip: plain main, no Gradle.
 */
public final class EndToEndTest {

    static final long WINDOW = 50_000_000L;
    static final long BUDGET = 2_000_000L;

    static int failures;

    public static void main(String[] args) {
        if (runAll() > 0)
            System.exit(1);
    }

    /** Runs every check from a clean count; returns how many failed. Also run under JUnit by {@code ChipHarnessTest}. */
    static int runAll() {
        failures = 0;
        blink();
        pwm();
        interrupt();
        tone();
        pulseIn();
        millisPolling();
        System.out.println(failures == 0 ? "end-to-end: all passed" : "end-to-end: " + failures + " failures");
        return failures;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    static Stm32f103 boot(String name, String source) {
        System.out.println(name + ":");
        CompileResult r = SketchCompiler.compile(source, ArduinoCore.INSTANCE);
        r.diagnostics().forEach(d -> System.out.println("    " + d));
        if (!r.ok()) {
            check(false, "compiles");
            return null;
        }
        System.out.println("    flash " + r.program().flashBytes() + " B, RAM " + r.program().staticRamBytes() + " B");
        Stm32f103 chip = new Stm32f103();
        chip.setPowered(true);
        chip.flash(r.program());
        while (chip.state() == McuState.PROGRAMMING)
            chip.runFor(WINDOW, BUDGET);
        return chip;
    }

    /** Runs windows, returning the average host microseconds per window. */
    static double run(Stm32f103 chip, int windows) {
        long t0 = System.nanoTime();
        for (int i = 0; i < windows; i++)
            chip.runFor(WINDOW, BUDGET);
        return (System.nanoTime() - t0) / 1000.0 / windows;
    }

    static void report(Stm32f103 chip, double hostMicros) {
        System.out.printf("    state %s%s, speed %.3f, load %.4f, %.1f mW, %.0f us host/window%n",
                chip.state(), chip.fault() != null ? " " + chip.fault().getMessage() + " @" + chip.faultLine() : "",
                chip.speed(), chip.cpuLoad(), chip.mcuPowerWatts() * 1000, hostMicros);
    }

    static void blink() {
        Stm32f103 chip = boot("blink", """
                void setup() { pinMode(DO0, OUTPUT); }
                void loop() {
                  digitalWrite(DO0, HIGH);
                  delay(10);
                  digitalWrite(DO0, LOW);
                  delay(15);
                }
                """);
        if (chip == null)
            return;
        double host = run(chip, 20);
        report(chip, host);
        check(Math.abs(chip.pin(8).highFraction() - 0.4) < 0.01, "DO0 duty 0.4: " + chip.pin(8).highFraction());
        check(Math.abs(chip.uptimeMillis() - 1000) <= 2, "uptime " + chip.uptimeMillis());
    }

    static void pwm() {
        Stm32f103 chip = boot("analogWrite", """
                void setup() { analogWrite(DO6, 64); analogWrite(DO0, 192); }
                void loop() { }
                """);
        if (chip == null)
            return;
        double host = run(chip, 4);
        report(chip, host);
        check(Math.abs(chip.pin(14).highFraction() - 64 / 255.0) < 0.003, "DO6 duty 64/255: " + chip.pin(14).highFraction());
        check(Math.abs(chip.pin(8).highFraction() - 192 / 255.0) < 0.003, "DO0 duty 192/255: " + chip.pin(8).highFraction());
        check(Math.abs(chip.pin(14).pwmHz() - 1000) < 1, "1 kHz: " + chip.pin(14).pwmHz());
    }

    static void interrupt() {
        Stm32f103 chip = boot("attachInterrupt", """
                volatile int edges = 0;
                void onEdge() {
                  edges++;
                  digitalWrite(DO1, edges & 1);
                }
                void setup() {
                  pinMode(DI0, INPUT);
                  pinMode(DO1, OUTPUT);
                  pinMode(DO2, OUTPUT);
                  attachInterrupt(digitalPinToInterrupt(DI0), onEdge, RISING);
                }
                void loop() {
                  digitalWrite(DO2, edges >= 3 ? HIGH : LOW);
                }
                """);
        if (chip == null)
            return;
        chip.runFor(WINDOW, BUDGET);
        boolean level = false;
        long t0 = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            level = !level;
            chip.setInput(0, level);
            chip.runFor(WINDOW, BUDGET);
        }
        report(chip, (System.nanoTime() - t0) / 6000.0);
        check(chip.pin(9).levelAtEnd(), "DO1 high after 3 rising edges (odd)");
        check(chip.pin(10).levelAtEnd(), "DO2 high once edges >= 3");
    }

    static void tone() {
        Stm32f103 chip = boot("tone", """
                void setup() { tone(DO3, 1000); }
                void loop() { }
                """);
        if (chip == null)
            return;
        double host = run(chip, 4);
        report(chip, host);
        check(Math.abs(chip.pin(11).highFraction() - 0.5) < 0.01, "DO3 duty 0.5: " + chip.pin(11).highFraction());
        check(Math.abs(chip.pin(11).pwmHz() - 1000) < 1, "tone 1 kHz: " + chip.pin(11).pwmHz());
    }

    static void pulseIn() {
        Stm32f103 chip = boot("pulseIn", """
                unsigned long width = 0;
                void setup() { pinMode(DO4, OUTPUT); pinMode(DO5, OUTPUT); }
                void loop() {
                  width = pulseIn(DI1, HIGH, 3000000UL);
                  digitalWrite(DO4, width > 140000 && width < 160000 ? HIGH : LOW);
                  digitalWrite(DO5, width == 0 ? HIGH : LOW);
                }
                """);
        if (chip == null)
            return;
        chip.runFor(WINDOW, BUDGET);
        chip.setInput(1, true);
        long t0 = System.nanoTime();
        for (int i = 0; i < 3; i++)
            chip.runFor(WINDOW, BUDGET);
        chip.setInput(1, false);
        chip.runFor(WINDOW, BUDGET);
        report(chip, (System.nanoTime() - t0) / 4000.0);
        check(chip.pin(12).levelAtEnd(), "pulseIn measured ~150 ms (DO4 high)");
        check(!chip.pin(13).levelAtEnd(), "no timeout (DO5 low)");
    }

    static void millisPolling() {
        Stm32f103 chip = boot("millis polling", """
                unsigned long last = 0;
                bool on = false;
                void setup() { pinMode(DO7, OUTPUT); }
                void loop() {
                  if (millis() - last >= 100) {
                    last += 100;
                    on = !on;
                    digitalWrite(DO7, on);
                  }
                }
                """);
        if (chip == null)
            return;
        run(chip, 2);
        double host = run(chip, 40);
        report(chip, host);
        // 2 s: toggled every 100 ms, so 50 % over a full period; one window is all-high or all-low
        double f = chip.pin(15).highFraction();
        check(f == 0.0 || f == 1.0, "a 50 ms window inside a 100 ms half-period is all one level: " + f);
        check(Math.abs(chip.uptimeMillis() - 2100) <= 3, "uptime " + chip.uptimeMillis());
        check(chip.speed() > 0.999, "keeps real time (idle fast-forward): speed " + chip.speed());
    }
}
