package com.nectariich.create_flippies.content.plc.mcu.chip;

import java.util.HashSet;
import java.util.Set;

import com.nectariich.create_flippies.content.plc.mcu.Builtin;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.PinDrive;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.content.plc.mcu.arduino.ArduinoCore;

/**
 * Plain-main tests for the chip and the Arduino core, run without Gradle:
 * {@code javac -d out $(find .../mcu -name '*.java') FakeCpu.java ChipTest.java && java -cp out ...ChipTest}.
 */
public final class ChipTest {

    static final long WINDOW = 50_000_000L;
    static final long BUDGET = 1_000_000_000L;
    static final long SRAM = Stm32f103cb.SRAM_BASE;

    static int failures;
    static int checks;

    public static void main(String[] args) {
        if (runAll() > 0)
            System.exit(1);
    }

    /** Runs every check from a clean count; returns how many failed. Also run under JUnit by {@code ChipHarnessTest}. */
    static int runAll() {
        failures = 0;
        checks = 0;
        prelude();
        powerStates();
        blink();
        unconfiguredWrite();
        pwm();
        pwmStopKillsTimer();
        analogWriteDigitalPin();
        exti();
        toneTest();
        pulseIn();
        analogRead();
        maskedMillis();
        delayMicros();
        hardFault();
        maths();
        chars();
        libc();
        shift();
        powerModel();
        System.out.println(checks + " checks, " + failures + " failures");
        return failures;
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void near(double actual, double expected, double tolerance, String what) {
        check(Math.abs(actual - expected) <= tolerance, what + " = " + actual + ", expected " + expected + " +- " + tolerance);
    }

    /** A powered chip running the program, past programming and booted. */
    static Stm32f103 running(FakeCpu.Program p) {
        Stm32f103 chip = new Stm32f103();
        chip.setPowered(true);
        chip.flash(p);
        int guard = 0;
        while (chip.state() == McuState.PROGRAMMING && guard++ < 100)
            chip.runFor(WINDOW, BUDGET);
        check(chip.state() == McuState.RUNNING, "boots to RUNNING, got " + chip.state());
        return chip;
    }

    static FakeCpu.Step s(String proto, long... args) {
        return FakeCpu.step(proto, args);
    }

    // ---------------------------------------------------------------------------

    static void prelude() {
        String p = ArduinoCore.INSTANCE.prelude();
        check(p.contains("#define LED_BUILTIN 16"), "LED_BUILTIN");
        check(p.contains("#define PA11 16"), "PA11 = 16");
        check(p.contains("#define PC15 34"), "PC15 = 34");
        check(p.contains("#define DO7 15"), "DO7");
        check(p.contains("#define B11111111 255"), "B11111111");
        check(p.contains("#define B0 0\n"), "B0");
        check(!p.contains("Serial"), "no Serial");
        int defines = p.split("#define ", -1).length - 1;
        check(defines > 700, "prelude has " + defines + " defines");
        // every macro defined once
        Set<String> names = new HashSet<>();
        for (String line : p.split("\n")) {
            if (!line.startsWith("#define "))
                continue;
            String name = line.substring(8).split("[ (]")[0];
            check(names.add(name) || name.equals("Arduino_h"), "duplicate macro " + name);
        }
        // balanced parentheses per line
        for (String line : p.split("\n")) {
            int depth = 0;
            for (char ch : line.toCharArray()) {
                if (ch == '(') depth++;
                if (ch == ')') depth--;
            }
            check(depth == 0, "unbalanced: " + line);
        }
        Set<String> protos = new HashSet<>();
        for (Builtin b : ArduinoCore.INSTANCE.builtins())
            check(protos.add(b.prototype()), "duplicate prototype " + b.prototype());
        check(ArduinoCore.INSTANCE.flashBytesFor("analogWrite") > 0, "flash for analogWrite");
        check(ArduinoCore.INSTANCE.flashBytesFor("nonexistent") == 0, "flash for unknown");
        System.out.println("prelude: " + p.length() + " chars, " + defines + " defines; "
                + ArduinoCore.INSTANCE.builtins().size() + " builtins");
    }

    static void powerStates() {
        Stm32f103 chip = new Stm32f103();
        check(chip.state() == McuState.UNPOWERED, "new chip unpowered");
        chip.runFor(WINDOW, BUDGET);
        check(chip.mcuPowerWatts() == 0, "unpowered draws nothing");
        chip.setPowered(true);
        check(chip.state() == McuState.NO_PROGRAM, "blank flash");
        chip.runFor(WINDOW, BUDGET);
        check(chip.mcuPowerWatts() > 0.005 && chip.mcuPowerWatts() < 0.03, "blank lockup power " + chip.mcuPowerWatts());

        FakeCpu.Program p = new FakeCpu.Program();
        p.flashBytes = 25 * 1024; // 1 s of programming
        chip.flash(p);
        check(chip.state() == McuState.PROGRAMMING, "programming");
        int windows = 0;
        while (chip.state() == McuState.PROGRAMMING && windows < 100) {
            chip.runFor(WINDOW, BUDGET);
            windows++;
        }
        check(windows == 20, "25 KB programs in 20 windows, took " + windows);
        check(chip.state() == McuState.RUNNING, "running after programming");
        chip.setResetHeld(true);
        check(chip.state() == McuState.IN_RESET, "reset held");
        chip.runFor(WINDOW, BUDGET);
        check(chip.pin(8) == PinDrive.OFF, "outputs off in reset");
        chip.setResetHeld(false);
        check(chip.state() == McuState.RUNNING, "released");
        chip.setPowered(false);
        check(chip.state() == McuState.UNPOWERED && chip.uptimeMillis() == 0, "power cut");
        chip.flash(null);
        chip.setPowered(true);
        check(chip.state() == McuState.NO_PROGRAM, "erased");
    }

    static void blink() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void pinMode(", 8, 1));
        p.loop.add(s("void digitalWrite(", 8, 1));
        p.loop.add(s("void delay(", 10));
        p.loop.add(s("void digitalWrite(", 8, 0));
        p.loop.add(s("void delay(", 15));
        Stm32f103 chip = running(p);
        for (int i = 0; i < 20; i++)
            chip.runFor(WINDOW, BUDGET);
        PinDrive d = chip.pin(8);
        check(d.output(), "DO0 is an output");
        near(d.highFraction(), 0.4, 0.01, "blink duty");
        near(chip.uptimeMillis(), 1000, 2, "uptime");
        near(chip.cpuLoad(), 0.0, 0.01, "delay loop is all WAIT");
        long ms = chip.uwTick();
        near(ms, 1000, 2, "millis after 1 s");
        check(chip.pin(9).highFraction() == 0 && !chip.pin(9).output(), "DO1 untouched");
        long[] cycles = chip.lastWindowCycles();
        check(cycles[CycleClass.INTERRUPT.ordinal()] >= 50 * Stm32f103.SYSTICK_ISR_CYCLES, "SysTick ISRs charged");
        near(chip.speed(), 1.0, 1e-9, "speed");
        System.out.printf("blink: duty %.4f, power %.1f mW, load %.4f%n", d.highFraction(), chip.mcuPowerWatts() * 1000, chip.cpuLoad());
    }

    static void unconfiguredWrite() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void digitalWrite(", 9, 1));
        p.setup.add(cpu -> cpu.call("int digitalRead(", 0));
        Stm32f103 chip = running(p);
        chip.setInput(0, true);
        chip.runFor(WINDOW, BUDGET);
        check(!chip.pin(9).output() && chip.pin(9).highFraction() == 0, "digitalWrite without pinMode does nothing");
        check(p.last.lastReturn == 0, "digitalRead without pinMode reads LOW");
    }

    static void pwm() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void analogWrite(", 14, 64));
        p.setup.add(s("void analogWriteFrequency(", 20_000));
        p.setup.add(s("void analogWrite(", 8, 255));
        p.setup.add(s("void analogWrite(", 9, 0));
        Stm32f103 chip = running(p);
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        PinDrive d = chip.pin(14);
        check(d.output(), "PB6 is AF output");
        near(d.highFraction(), 64.0 / 255, 0.002, "PWM duty 64/255");
        near(d.pwmHz(), 1000, 0.5, "PWM 1 kHz");
        near(chip.pin(8).highFraction(), 1.0, 1e-9, "duty 255 is 100 %");
        check(chip.pin(8).pwmHz() == 0, "100 % reports no frequency");
        near(chip.pin(9).highFraction(), 0.0, 1e-9, "duty 0");
        check(chip.pin(9).output(), "duty 0 still an output");
    }

    static void pwmStopKillsTimer() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void analogWrite(", 8, 128));
        p.setup.add(s("void analogWrite(", 9, 128));
        p.setup.add(s("void pinMode(", 8, 1)); // pwm_stop deletes TIM1: PB1 stops too
        p.setup.add(s("void digitalWrite(", 8, 1));
        Stm32f103 chip = running(p);
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        near(chip.pin(8).highFraction(), 1.0, 1e-9, "PB0 now a GPIO output, high");
        near(chip.pin(9).highFraction(), 0.0, 1e-9, "PB1 stopped with its timer");
    }

    static void analogWriteDigitalPin() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void analogWrite(", 10, 200));
        p.setup.add(s("void analogWrite(", 11, 100));
        Stm32f103 chip = running(p);
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        near(chip.pin(10).highFraction(), 1.0, 1e-9, "non-PWM pin >= 128 is HIGH");
        near(chip.pin(11).highFraction(), 0.0, 1e-9, "non-PWM pin < 128 is LOW");
        check(chip.pin(11).output(), "analogWrite made it an output");
    }

    static void exti() {
        FakeCpu.Program p = new FakeCpu.Program();
        int[] count = new int[1];
        p.handlers.put(0x0800_1235L, cpu -> {
            count[0]++;
            cpu.call("void digitalToggle(", 12);
        });
        p.setup.add(s("void pinMode(", 0, 0));
        p.setup.add(s("void pinMode(", 12, 1));
        p.setup.add(s("void attachInterrupt(", 0, 0x0800_1235L, 4)); // RISING
        Stm32f103 chip = running(p);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET); // setup runs in the first window after boot
        boolean level = false;
        for (int i = 0; i < 10; i++) {
            level = !level;
            chip.setInput(0, level);
            chip.runFor(WINDOW, BUDGET);
        }
        check(count[0] == 5, "5 rising edges, got " + count[0]);
        check(p.last.interruptsTaken == 5, "5 interrupts taken");
        // the ISR toggled DO4 five times, on rising edges at the starts of windows 1, 3, 5, 7, 9
        check(chip.pin(12).levelAtEnd(), "odd number of toggles leaves DO4 high");
    }

    static void toneTest() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void tone(unsigned char pin, unsigned int frequency)", 10, 1000));
        Stm32f103 chip = running(p);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        near(chip.pin(10).highFraction(), 0.5, 0.01, "tone duty");
        near(chip.pin(10).pwmHz(), 1000, 0.01, "tone frequency");
        long isr = chip.lastWindowCycles()[CycleClass.INTERRUPT.ordinal()];
        long expected = 100L * 112 + 50L * Stm32f103.SYSTICK_ISR_CYCLES;
        near(isr, expected, 400, "tone ISR cycles");

        FakeCpu.Program q = new FakeCpu.Program();
        q.setup.add(s("void tone(unsigned char pin, unsigned int frequency, unsigned long duration)", 10, 1000, 75));
        chip = running(q);
        q.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        near(chip.pin(10).highFraction(), 0.5, 0.02, "tone first window");
        chip.runFor(WINDOW, BUDGET);
        near(chip.pin(10).highFraction(), 0.25, 0.02, "tone stops after 75 ms");
        chip.runFor(WINDOW, BUDGET);
        check(chip.pin(10).highFraction() == 0 && !chip.pin(10).levelAtEnd(), "tone ended low");

        // second pin ignored while the first holds the timer, until noTone(pin, true)
        FakeCpu.Program r = new FakeCpu.Program();
        r.setup.add(s("void tone(unsigned char pin, unsigned int frequency)", 10, 500));
        r.setup.add(s("void noTone(unsigned char pin)", 10));
        r.setup.add(s("void tone(unsigned char pin, unsigned int frequency)", 11, 500));
        chip = running(r);
        r.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        check(chip.pin(11).highFraction() == 0, "tone on a second pin ignored after plain noTone");
    }

    static void pulseIn() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("unsigned long pulseIn(unsigned long pin, unsigned long state, unsigned long timeout)", 1, 1, 3_000_000));
        Stm32f103 chip = running(p);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);          // low
        chip.setInput(1, true);
        chip.runFor(WINDOW, BUDGET);          // high x3
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        chip.setInput(1, false);
        chip.runFor(WINDOW, BUDGET);
        near(p.last.lastReturn, 150_000, 2, "pulseIn of 3 windows");

        FakeCpu.Program q = new FakeCpu.Program();
        q.setup.add(s("unsigned long pulseIn(unsigned long pin, unsigned long state)", 2, 1));
        q.setup.add(cpu -> cpu.lastReturn += 1_000_000_000L); // marks that pulseIn returned
        chip = running(q);
        q.last.idleWhenDone = true;
        for (int i = 0; i < 21; i++)
            chip.runFor(WINDOW, BUDGET);
        check(q.last.lastReturn == 1_000_000_000L, "pulseIn times out after 1 s with 0, got " + q.last.lastReturn);

        // pulseIn on a PWM pin measures the timer's high time
        FakeCpu.Program r = new FakeCpu.Program();
        r.setup.add(s("void analogWrite(", 14, 64));
        r.setup.add(s("unsigned long pulseIn(unsigned long pin, unsigned long state)", 14, 1));
        chip = running(r);
        r.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        near(r.last.lastReturn, 1000.0 * 64 / 255, 2, "pulseIn of PWM high time (us)");
    }

    static void analogRead() {
        FakeCpu.Program p = new FakeCpu.Program();
        long[] r = new long[3];
        p.setup.add(s("void pinMode(", 3, 0));
        p.setup.add(cpu -> r[0] = cpu.call("int digitalRead(", 3));
        // analogRead is long enough to be charged in pieces (Preemptible): its value is
        // there once the call has finished, which is when the next step runs
        p.setup.add(s("unsigned long analogRead(", 3));
        p.setup.add(cpu -> r[1] = cpu.lastReturn);
        p.setup.add(cpu -> r[2] = cpu.call("int digitalRead(", 3));
        Stm32f103 chip = new Stm32f103();
        chip.setInput(3, true);
        chip.setPowered(true);
        chip.flash(p);
        while (chip.state() == McuState.PROGRAMMING)
            chip.runFor(WINDOW, BUDGET);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        check(r[0] == 1, "DI3 reads HIGH");
        check(r[1] == 1023, "analogRead 10-bit full scale, got " + r[1]);
        check(r[2] == 0, "after analogRead the pin is analog: digitalRead 0");
    }

    static void maskedMillis() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void __disable_irq("));
        p.setup.add(s("void delayMicroseconds(", 5500));
        p.setup.add(cpu -> cpu.call("unsigned long millis("));
        p.setup.add(s("void __enable_irq("));
        long[] after = new long[1];
        p.setup.add(cpu -> after[0] = cpu.call("unsigned long millis("));
        Stm32f103 chip = running(p);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        check(after[0] == 1, "5.5 ms masked: one tick caught up, millis = " + after[0]);
    }

    static void delayMicros() {
        FakeCpu.Program p = new FakeCpu.Program();
        long[] t = new long[2];
        p.setup.add(cpu -> t[0] = cpu.ledger.now());
        p.setup.add(s("void delayMicroseconds(", 10));
        p.setup.add(cpu -> t[1] = cpu.ledger.now());
        Stm32f103 chip = running(p);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        near(t[1] - t[0], 720, 12, "delayMicroseconds(10) cycles");
    }

    static void hardFault() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void pinMode(", 8, 1));
        p.setup.add(s("void digitalWrite(", 8, 1));
        p.setup.add(s("void delay(", 20));
        p.setup.add(s("void abort("));
        Stm32f103 chip = running(p);
        chip.runFor(WINDOW, BUDGET);
        check(chip.state() == McuState.HARD_FAULT, "abort faults");
        check(chip.fault().kind() == HardFault.Kind.ABORT, "kind ABORT");
        check(chip.faultLine() == 42, "fault line");
        chip.runFor(WINDOW, BUDGET);
        check(chip.pin(8).levelAtEnd() && chip.pin(8).highFraction() == 1.0, "output frozen high");
        long[] c = chip.lastWindowCycles();
        check(c[CycleClass.BRANCH.ordinal()] == 3_600_000, "fault loop all BRANCH");
        near(chip.cpuLoad(), 1.0, 1e-9, "fault loop is load 1");
        chip.reset();
        check(chip.state() == McuState.RUNNING, "reset clears the fault");

        FakeCpu.Program q = new FakeCpu.Program();
        q.setup.add(s("void pinMode(", 8, 9));
        chip = running(q);
        chip.runFor(WINDOW, BUDGET);
        check(chip.state() == McuState.HARD_FAULT, "bad pinMode mode goes to Error_Handler");
    }

    static void maths() {
        FakeCpu.Program p = new FakeCpu.Program();
        Stm32f103 chip = running(p);
        FakeCpu cpu = p.last;
        cpu.call("double sqrt(double", Double.doubleToRawLongBits(2.0));
        near(cpu.lastDouble, Math.sqrt(2), 0, "sqrt(2.0)");
        cpu.call("float sqrtf(", Float.floatToRawIntBits(2f));
        check(cpu.lastFloat == (float) Math.sqrt(2), "sqrtf");
        long before = chip.now();
        cpu.call("double sqrt(int", 16);
        near(cpu.lastDouble, 4, 0, "sqrt(int)");
        check(chip.now() - before > 500, "double sqrt costs");
        cpu.call("double pow(", Double.doubleToRawLongBits(2.0), Double.doubleToRawLongBits(10.0));
        near(cpu.lastDouble, 1024, 0, "pow");
        check(cpu.call("long map(", 5, 0, 10, 0, 100) == 50, "map");
        check(cpu.call("long map(", 5, 0, 0, 7, 100) == 7, "map with empty range: SDIV by 0 gives out_min");
        check(cpu.call("long map(", -1, 0, 10, 0, 100) == -10, "map negative");
        // newlib rand() from seed 1
        check(cpu.call("long random(void") == 1481765933L, "first random() from seed 1: " + cpu.lastReturn);
        cpu.call("void randomSeed(", 1);
        check(cpu.call("long random(void") == 1481765933L, "randomSeed(1) restarts the sequence");
        cpu.call("void randomSeed(", 0);
        long r = cpu.call("long random(long howbig)", 10);
        check(r >= 0 && r < 10, "random(10)");
        r = cpu.call("long random(long howsmall", 5, 8);
        check(r >= 5 && r < 8, "random(5, 8)");
        check(cpu.call("long random(long howbig)", 0) == 0, "random(0)");
        check(cpu.call("long random(long howsmall", 9, 3) == 9, "random(9, 3)");
        check(cpu.call("unsigned short makeWord(unsigned char", 0x12, 0x34) == 0x1234, "makeWord");
    }

    static void chars() {
        FakeCpu.Program p = new FakeCpu.Program();
        running(p);
        FakeCpu cpu = p.last;
        check(cpu.call("int isdigit(", '7') == 4, "newlib isdigit returns the mask");
        check(cpu.call("bool isDigit(", '7') == 1, "isDigit true");
        check(cpu.call("bool isWhitespace(", '\t') == 1, "tab is whitespace");
        check(cpu.call("bool isWhitespace(", '\n') == 0, "newline is not blank");
        check(cpu.call("bool isSpace(", '\n') == 1, "newline is space");
        check(cpu.call("bool isAlpha(", 0xE9) == 0, "Latin-1 letters are not alpha in the C locale");
        check(cpu.call("int toupper(", 'q') == 'Q', "toupper");
        check(cpu.call("bool isPunct(", '!') == 1, "isPunct");
        check(cpu.call("bool isHexadecimalDigit(", 'F') == 1, "isxdigit F");
        check(cpu.call("int isdigit(", -1) == 0, "EOF");
    }

    static void libc() {
        FakeCpu.Program p = new FakeCpu.Program();
        running(p);
        FakeCpu cpu = p.last;
        FakeCpu.FakeMemory m = cpu.memory;
        long buf = SRAM + 0x100;
        long fmt = m.putString(SRAM + 0x400, "%d|%5s|%-4d|%04x|%f|%c|%u|%+d|%%");
        long str = m.putString(SRAM + 0x500, "ab");
        long n = cpu.call("int sprintf(", buf, fmt, -42, str, 7, 0xBE, Double.doubleToRawLongBits(1.5), 'Z', 0xFFFF_FFFFL, 5);
        String out = m.getString(buf);
        check(out.equals("-42|   ab|7   |00be||Z|4294967295|+5|%"), "sprintf: " + out);
        check(n == out.length(), "sprintf length");
        cpu.call("int snprintf(", buf, 4, m.putString(SRAM + 0x400, "hello"));
        check(m.getString(buf).equals("hel") && cpu.lastReturn == 5, "snprintf truncates: " + m.getString(buf));

        cpu.call("char* dtostrf(", Double.doubleToRawLongBits(3.14159), 6, 2, buf);
        check(m.getString(buf).equals("  3.14"), "dtostrf: '" + m.getString(buf) + "'");
        cpu.call("char* dtostrf(", Double.doubleToRawLongBits(-0.05), -7, 3, buf);
        check(m.getString(buf).equals("-0.050 "), "dtostrf neg/left: '" + m.getString(buf) + "'");
        cpu.call("char* dtostrf(", Double.doubleToRawLongBits(1.999), 0, 2, buf);
        check(m.getString(buf).equals("2.00"), "dtostrf rounding: '" + m.getString(buf) + "'");

        cpu.call("char* itoa(", -42 & 0xFFFF_FFFFL | 0xFFFF_FFFF_0000_0000L, buf, 10);
        check(m.getString(buf).equals("-42"), "itoa -42");
        cpu.call("char* itoa(", 255, buf, 16);
        check(m.getString(buf).equals("ff"), "itoa ff");
        cpu.call("char* itoa(", -1L, buf, 16);
        check(m.getString(buf).equals("ffffffff"), "itoa -1 base 16");

        long end = SRAM + 0x600;
        check(cpu.call("long strtol(", m.putString(SRAM + 0x400, "  -0x1fZ"), end, 0) == -31, "strtol hex");
        check(m.read32(end) == (int) (SRAM + 0x400 + 7), "strtol endptr");
        check((int) cpu.call("long strtol(", m.putString(SRAM + 0x400, "99999999999"), 0, 10) == Integer.MAX_VALUE, "strtol saturates");
        check(cpu.call("int atoi(", m.putString(SRAM + 0x400, "12abc")) == 12, "atoi");
        check(cpu.call("unsigned long strtoul(", m.putString(SRAM + 0x400, "-1"), 0, 10) == 0xFFFF_FFFFL, "strtoul -1");
        cpu.call("double atof(", m.putString(SRAM + 0x400, " 1.5e3xyz"));
        near(cpu.lastDouble, 1500, 0, "atof");
        cpu.call("double strtod(", m.putString(SRAM + 0x400, "-.25"), end);
        near(cpu.lastDouble, -0.25, 0, "strtod .25");
        check(m.read32(end) == (int) (SRAM + 0x400 + 4), "strtod endptr");

        m.putString(buf, "abc");
        cpu.call("char* strcat(", buf, m.putString(SRAM + 0x400, "def"));
        check(m.getString(buf).equals("abcdef"), "strcat");
        check(cpu.call("unsigned int strlen(", buf) == 6, "strlen");
        check(cpu.call("int strcmp(", buf, m.putString(SRAM + 0x400, "abd")) < 0, "strcmp");
        check(cpu.call("char* strstr(", buf, m.putString(SRAM + 0x400, "cd")) == buf + 2, "strstr");
        check(cpu.call("char* strchr(", buf, 'e') == buf + 4, "strchr");
        check(cpu.call("char* strchr(", buf, 0) == buf + 6, "strchr NUL");

        long tok = m.putString(buf, ",a,,bc,");
        long delim = m.putString(SRAM + 0x400, ",");
        long t1 = cpu.call("char* strtok(", tok, delim);
        long t2 = cpu.call("char* strtok(", 0, delim);
        long t3 = cpu.call("char* strtok(", 0, delim);
        check(m.getString(t1).equals("a") && m.getString(t2).equals("bc") && t3 == 0, "strtok");

        boolean faulted = false;
        try {
            cpu.call("void* memset(", Stm32f103cb.FLASH_BASE, 0, 4);
        } catch (HardFault f) {
            faulted = f.kind() == HardFault.Kind.MEM_MANAGE;
        }
        check(faulted, "memset into flash faults");
        m.putString(buf, "0123456789");
        cpu.call("void* memmove(", buf + 2, buf, 5);
        check(m.getString(buf).equals("0101234789"), "memmove overlap: " + m.getString(buf));
    }

    static void shift() {
        FakeCpu.Program p = new FakeCpu.Program();
        p.setup.add(s("void pinMode(", 12, 1));
        p.setup.add(s("void pinMode(", 13, 1));
        p.setup.add(s("void pinMode(", 5, 0));
        long[] got = new long[1];
        Stm32f103 chip = new Stm32f103();
        chip.setInput(5, true);
        chip.setPowered(true);
        chip.flash(p);
        while (chip.state() == McuState.PROGRAMMING)
            chip.runFor(WINDOW, BUDGET);
        p.setup.add(s("void shiftOut(", 12, 13, 1, 0xA5));
        p.setup.add(cpu -> got[0] = cpu.call("unsigned long shiftIn(", 5, 13, 1));
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        check(got[0] == 0xFF, "shiftIn of a high DI reads 0xFF");
        check(chip.pin(13).highFraction() > 0 && chip.pin(13).highFraction() < 0.001, "clock pulses averaged");
        check(chip.pin(12).levelAtEnd(), "data pin ends on bit 0 of 0xA5, a 1");
    }

    static void powerModel() {
        // a pure busy-wait window at 72 MHz with GPIOA/B and AFIO clocked
        FakeCpu.Program p = new FakeCpu.Program();
        Stm32f103 chip = running(p);
        p.last.idleWhenDone = true;
        chip.runFor(WINDOW, BUDGET);
        double watts = chip.mcuPowerWatts();
        double amps = watts / 3.3;
        System.out.printf("power: idle busy-wait %.2f mA (%.1f mW)%n", amps * 1000, watts * 1000);
        check(amps > 0.022 && amps < 0.030, "busy-wait current in the 27 mA ballpark: " + amps);

        FakeCpu.Program q = new FakeCpu.Program();
        q.setup.add(s("void abort("));
        chip = running(q);
        chip.runFor(WINDOW, BUDGET);
        chip.runFor(WINDOW, BUDGET);
        double fault = chip.mcuPowerWatts() / 3.3;
        System.out.printf("power: HardFault loop %.2f mA%n", fault * 1000);
        check(fault > amps, "branch loop draws more than WAIT");
        check(fault < 0.036, "under the all-peripherals figure");
    }
}
