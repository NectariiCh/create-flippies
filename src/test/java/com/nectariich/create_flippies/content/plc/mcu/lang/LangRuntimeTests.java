package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;

/**
 * The CPU's time behaviour: interrupts, the idle-loop fast-forward (that it skips
 * host work and that it changes nothing the sketch can observe), throughput, and
 * compile speed. Run with
 * {@code java -cp <out> com.nectariich.create_flippies.content.plc.mcu.lang.LangRuntimeTests}.
 */
public final class LangRuntimeTests {

    static final long WINDOW = 3_600_000; // 50 ms at 72 MHz
    static int failed;

    public static void main(String[] args) {
        runAll();
        if (failed > 0)
            System.exit(1);
    }

    /** Every runtime test; returns how many checks failed. */
    static int runAll() {
        failed = 0;
        interrupts();
        interruptsWhileParked();
        interruptsMasked();
        blinkFastForward();
        pollingInput();
        busyLoopNotSkipped();
        gotoLoopSkipped();
        loopInInterruptSkipped();
        benchmark();
        compileSpeed();
        System.out.println(failed == 0 ? "all runtime tests passed" : failed + " runtime test(s) failed");
        return failed;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok)
            failed++;
    }

    static TestEnv boot(String src) {
        CompileResult r = SketchCompiler.compile(src, new TestLibrary());
        if (!r.ok())
            throw new IllegalStateException("compile failed: " + r.diagnostics());
        return TestEnv.boot(r);
    }

    /** One 50 ms window, as the chip runs them. */
    static CpuCore.Exit window(TestEnv e) {
        e.windowEnd = e.ledger.now() + WINDOW;
        return e.cpu.run(e.windowEnd, System.nanoTime() + 2_000_000_000L);
    }

    static void interrupts() {
        TestEnv e = boot("""
                volatile int hits = 0;
                volatile int lambdaHits = 0;
                void isr() { hits++; }
                void setup() {
                  attachInterrupt(digitalPinToInterrupt(DI0), isr, RISING);
                  attachInterrupt(1, []() { lambdaHits += 100; }, RISING);
                }
                void loop() {
                  if (hits >= 3 && lambdaHits >= 200) { out(hits); out(lambdaHits); done(); }
                }
                """);
        window(e);
        for (int i = 0; i < 3; i++) {
            e.cpu.pendInterrupt(6, e.handlers[0]);
            if (i < 2)
                e.cpu.pendInterrupt(7, e.handlers[1]);
            window(e);
        }
        window(e);
        check(e.done && e.out.equals(List.of("3", "200")), "interrupts: handlers run, lambda handler too " + e.out);
        check(e.ledger.cycles(CycleClass.INTERRUPT) >= 5 * 24, "interrupts: entry and exit charged (" + e.ledger.cycles(CycleClass.INTERRUPT) + ")");
    }

    static void interruptsWhileParked() {
        TestEnv e = boot("""
                volatile int hits = 0;
                void isr() { hits++; }
                void setup() {
                  attachInterrupt(0, isr, RISING);
                  delay(1000);
                  out(hits);
                  done();
                }
                void loop() {}
                """);
        window(e);
        e.cpu.pendInterrupt(6, e.handlers[0]);
        window(e);
        e.cpu.pendInterrupt(6, e.handlers[0]);
        e.cpu.pendInterrupt(6, e.handlers[0]); // pending twice before it runs: one
        long host = System.nanoTime();
        while (!e.done && e.ledger.now() < 72_000_000L * 2)
            window(e);
        host = System.nanoTime() - host;
        check(e.done && e.out.equals(List.of("2")), "delay(): interrupts taken while parked, NVIC coalesces " + e.out);
        check(e.ledger.now() >= 72_000_000L && e.ledger.now() < 72_000_000L + 2 * WINDOW, "delay(1000) lasted ~1 s of emulated time");
        check(e.ledger.cycles(CycleClass.WAIT) > 70_000_000L, "delay() charged as WAIT (" + e.ledger.cycles(CycleClass.WAIT) + ")");
    }

    static void interruptsMasked() {
        TestEnv e = boot("""
                volatile int hits = 0;
                void isr() { hits++; }
                int phase = 0;
                void setup() { attachInterrupt(0, isr, RISING); noInterrupts(); }
                void loop() {
                  if (phase == 0 && millis() > 120) { out(hits); interrupts(); phase = 1; }
                  if (phase == 1) { out(hits); done(); }
                }
                """);
        window(e);
        e.cpu.pendInterrupt(6, e.handlers[0]);
        for (int i = 0; i < 6 && !e.done; i++)
            window(e);
        check(e.done && e.out.equals(List.of("0", "1")), "noInterrupts() holds a pending interrupt until interrupts() " + e.out);
    }

    static final String BLINK = """
            unsigned long last = 0;
            int count = 0;
            void setup() { pinMode(DO0, OUTPUT); }
            void loop() {
              unsigned long now = millis();
              if (now - last >= 250) {
                last = now;
                count++;
                digitalWrite(DO0, count & 1);
              }
            }
            """;

    /** Runs {@code seconds} of emulated time in 50 ms windows; returns host nanoseconds. */
    static long runSeconds(TestEnv e, int seconds) {
        long t0 = System.nanoTime();
        long end = 72_000_000L * seconds;
        while (e.ledger.now() < end && !e.cpu.halted())
            window(e);
        return System.nanoTime() - t0;
    }

    static void blinkFastForward() {
        TestEnv fast = boot(BLINK);
        TestEnv slow = boot(BLINK);
        ((Vm) slow.cpu).fastForward = false;
        runSeconds(fast, 2);
        runSeconds(slow, 2);
        long hostFast = runSeconds(fast, 12);
        long hostSlow = runSeconds(slow, 12);
        Vm vf = (Vm) fast.cpu, vs = (Vm) slow.cpu;
        check(fast.writeLog.equals(slow.writeLog), "blink: fast-forward changes no write time (" + fast.writeLog.size() + " writes, first "
                + fast.writeLog.subList(0, 3) + ")");
        check(fast.writeLog.size() == 48, "blink: 4 toggles a second for 12 s (" + fast.writeLog.size() + ")");
        boolean exact = true;
        for (int i = 1; i < fast.writeLog.size(); i++) {
            long dt = fast.writeLog.get(i) - fast.writeLog.get(i - 1);
            if (Math.abs(dt - 18_000_000) > 72_000)
                exact = false;
        }
        check(exact, "blink: toggles 250 ms apart to within a millisecond");
        long all = fast.ledger.now();
        check(vf.skippedIterations > 0 && vf.instructions < vs.instructions / 20,
                "blink: fast-forward skipped " + vf.skippedIterations + " iterations; interpreted " + vf.instructions + " vs " + vs.instructions + " instructions");
        System.out.printf("     blink, 12 s emulated: %.1f ms host with fast-forward, %.1f ms without (%.0fx)%n",
                hostFast / 1e6, hostSlow / 1e6, (double) hostSlow / hostFast);
        System.out.printf("     cpu load reported: %.1f%% non-WAIT%n", 100.0 * (all - fast.ledger.cycles(CycleClass.WAIT)) / all);
    }

    static void pollingInput() {
        String src = """
                void setup() {
                  while (digitalRead(DI0) == LOW) { }
                  digitalWrite(DO0, HIGH);
                  while (digitalRead(DI0) == HIGH) { }
                  digitalWrite(DO0, LOW);
                }
                void loop() {}
                """;
        TestEnv fast = boot(src), slow = boot(src);
        ((Vm) slow.cpu).fastForward = false;
        long hf = 0, hs = 0;
        for (int w = 0; w < 40; w++) {
            boolean in = w >= 10 && w < 25;
            fast.inputs[0] = in;
            slow.inputs[0] = in;
            long t = System.nanoTime();
            window(fast);
            hf += System.nanoTime() - t;
            t = System.nanoTime();
            window(slow);
            hs += System.nanoTime() - t;
        }
        check(fast.writeLog.equals(slow.writeLog) && fast.writeLog.size() == 2,
                "polling digitalRead: same reaction times with and without fast-forward " + fast.writeLog);
        check(fast.writeLog.size() == 2 && fast.writeLog.get(0) >= 10 * WINDOW && fast.writeLog.get(0) < 10 * WINDOW + 1000,
                "polling digitalRead: reacts at the start of the window the input changed in");
        System.out.printf("     polling 2 s: %.2f ms host with fast-forward, %.1f ms without%n", hf / 1e6, hs / 1e6);
    }

    static void busyLoopNotSkipped() {
        TestEnv e = boot("""
                volatile unsigned long n = 0;
                void setup() {}
                void loop() { n++; if (n == 200000) { out(n); done(); } }
                """);
        while (!e.done && e.ledger.now() < 72_000_000L * 5)
            window(e);
        check(e.done && ((Vm) e.cpu).skippedIterations == 0, "a loop that changes memory is never skipped");
    }

    /** A backward goto is a loop: an idle one is fast-forwarded like while(1){}. */
    static void gotoLoopSkipped() {
        TestEnv e = boot("""
                void setup() { a: goto a; }
                void loop() {}
                """);
        TestEnv f = boot("""
                volatile int x = 1;
                void setup() { a: if (x) goto a; }
                void loop() {}
                """);
        runSeconds(e, 2);
        runSeconds(f, 2);
        Vm ve = (Vm) e.cpu, vf = (Vm) f.cpu;
        check(ve.skippedIterations > 0 && ve.instructions < 100_000 && e.ledger.now() >= 2 * 72_000_000L,
                "goto loop fast-forwarded (" + ve.instructions + " instructions for 2 s)");
        check(vf.skippedIterations > 0 && vf.instructions < 100_000, "conditional goto loop fast-forwarded (" + vf.instructions + " instructions)");
    }

    /** A handler that spins forever (handlers do not nest) is fast-forwarded too. */
    static void loopInInterruptSkipped() {
        TestEnv e = boot("""
                volatile int entered = 0;
                void isr() { entered = 1; for (;;) { } }
                void setup() { attachInterrupt(0, isr, RISING); }
                void loop() {}
                """);
        window(e);
        e.cpu.pendInterrupt(6, e.handlers[0]);
        e.cpu.pendInterrupt(7, e.handlers[0]); // pending behind the spinning handler: never taken, never in the way
        long host = runSeconds(e, 2);
        Vm v = (Vm) e.cpu;
        check(v.skippedIterations > 0 && v.instructions < 100_000 && e.ledger.now() >= 2 * 72_000_000L,
                "loop inside an interrupt handler fast-forwarded (" + v.instructions + " instructions, " + host / 1_000_000 + " ms host)");
    }

    static void benchmark() {
        TestEnv e = boot("""
                uint32_t x = 1, acc = 0;
                int arr[64];
                void setup() {}
                void loop() {
                  for (int i = 0; i < 64; i++) {
                    x = x * 1103515245u + 12345u;
                    arr[i] = x >> 16;
                    acc += arr[(i * 7) & 63] ^ i;
                  }
                }
                """);
        Vm vm = (Vm) e.cpu;
        // Warm up the JIT, then measure.
        for (int i = 0; i < 40; i++)
            e.cpu.run(e.ledger.now() + WINDOW, Long.MAX_VALUE);
        long i0 = vm.instructions, c0 = e.ledger.now();
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++)
            e.cpu.run(e.ledger.now() + WINDOW, Long.MAX_VALUE);
        long dt = System.nanoTime() - t0;
        double ops = (vm.instructions - i0) / (dt / 1e9);
        double mhz = (e.ledger.now() - c0) / (dt / 1e9) / 1e6;
        System.out.printf("     throughput: %.1f M VM ops/s, %.1f emulated MHz (%.2fx real time at 72 MHz)%n", ops / 1e6, mhz, mhz / 72);
        check(ops >= 30e6, "throughput >= 30 M simple ops/s");

        TestEnv f = boot("""
                float y = 1.0f;
                double z = 0.5;
                void setup() {}
                void loop() {
                  for (int i = 0; i < 100; i++) { y = y * 1.0001f + 0.5f; z = z / 1.01 + y; }
                }
                """);
        for (int i = 0; i < 20; i++)
            f.cpu.run(f.ledger.now() + WINDOW, Long.MAX_VALUE);
        Vm fv = (Vm) f.cpu;
        long fi = fv.instructions;
        t0 = System.nanoTime();
        for (int i = 0; i < 50; i++)
            f.cpu.run(f.ledger.now() + WINDOW, Long.MAX_VALUE);
        dt = System.nanoTime() - t0;
        System.out.printf("     soft-float loop: %.1f M VM ops/s, %.1f ms host per 50 ms window%n", (fv.instructions - fi) / (dt / 1e9) / 1e6, dt / 1e6 / 50);
    }

    static void compileSpeed() {
        StringBuilder sb = new StringBuilder();
        sb.append("int state[16];\nString label = \"x\";\n");
        int fns = 0;
        while (countLines(sb) < 2000) {
            sb.append("int f").append(fns).append("(int a, int b) {\n");
            sb.append("  int r = a * ").append(fns).append(" + b;\n");
            sb.append("  for (int i = 0; i < 4; i++) {\n    r ^= state[i & 15] << (i + 1);\n    if (r > 1000) r -= 17; else r += 3;\n  }\n");
            sb.append("  switch (r & 3) { case 0: r++; break; case 1: r--; break; default: r *= 2; }\n");
            sb.append("  label += r;\n  return r;\n}\n");
            fns++;
        }
        sb.append("void setup() {\n");
        for (int i = 0; i < fns; i++)
            sb.append("  state[").append(i & 15).append("] = f").append(i).append("(").append(i).append(", 2);\n");
        sb.append("}\nvoid loop() {}\n");
        String src = sb.toString();
        TestLibrary lib = new TestLibrary();
        for (int i = 0; i < 5; i++)
            SketchCompiler.compile(src, lib);
        long t0 = System.nanoTime();
        CompileResult r = SketchCompiler.compile(src, lib);
        long dt = System.nanoTime() - t0;
        long tc = System.nanoTime();
        SketchCompiler.compile(src, new TestLibrary());
        System.out.printf("     compile %d lines: %.1f ms warm%n", countLines(sb), dt / 1e6);
        check(r.ok(), "2000-line sketch compiles: " + (r.ok() ? String.join(" | ", SketchCompiler.usage(r.program())) : r.diagnostics()));
        check(dt < 200_000_000L, "2000-line sketch compiles in under 200 ms warm");
    }

    static int countLines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) == '\n')
                n++;
        return n;
    }
}
