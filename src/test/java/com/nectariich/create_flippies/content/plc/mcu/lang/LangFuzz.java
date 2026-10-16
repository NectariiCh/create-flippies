package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;

/**
 * Feeds the compiler mutilated sketches (the test suite's, with characters, tokens
 * and lines deleted, duplicated or swapped) and checks it never fails internally: a
 * player's half-typed sketch must get diagnostics, not a stack trace. Whatever
 * compiles is also run briefly, since a sketch that compiles must not crash the VM
 * either (it may fault, as the chip would).
 */
public final class LangFuzz {

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1234;
        if (run(rounds, seed) > 0)
            System.exit(1);
    }

    /** Compiles and runs {@code rounds} mutants; returns how many broke the toolchain or the VM. */
    static int run(int rounds, long seed) {
        Random rnd = new Random(seed);
        List<LangTests.Case> cases = LangTests.cases();
        TestLibrary lib = new TestLibrary();
        int internal = 0, compiled = 0, vmCrash = 0;
        Set<String> seen = new HashSet<>();
        for (int r = 0; r < rounds; r++) {
            String src = cases.get(rnd.nextInt(cases.size())).src;
            String m = mutate(src, rnd, 1 + rnd.nextInt(4));
            CompileResult res;
            try {
                res = SketchCompiler.compile(m, lib);
            } catch (Throwable t) {
                internal++;
                report(seen, "threw " + t, m);
                continue;
            }
            for (Diagnostic d : res.diagnostics()) {
                if (d.message().startsWith("internal compiler error")) {
                    internal++;
                    report(seen, d.message(), m);
                }
            }
            if (res.ok()) {
                compiled++;
                try {
                    TestEnv e = TestEnv.boot(res);
                    for (int w = 0; w < 4 && !e.cpu.halted() && !e.done; w++) {
                        e.windowEnd = e.ledger.now() + 3_600_000;
                        e.cpu.run(e.windowEnd, System.nanoTime() + 200_000_000L);
                    }
                    if (e.cpu.halted() && e.cpu.fault() == null)
                        throw new IllegalStateException("halted without a fault");
                } catch (Throwable t) {
                    vmCrash++;
                    report(seen, "VM threw " + t + " at " + (t.getStackTrace().length > 0 ? t.getStackTrace()[0] : ""), m);
                }
            }
        }
        System.out.println(rounds + " mutants: " + compiled + " compiled, " + internal + " internal compiler errors, " + vmCrash + " VM crashes");
        return internal + vmCrash;
    }

    private static void report(Set<String> seen, String what, String src) {
        String key = what.length() > 120 ? what.substring(0, 120) : what;
        if (seen.add(key)) {
            System.out.println("==== " + what);
            System.out.println(src);
        }
    }

    static String mutate(String s, Random rnd, int n) {
        StringBuilder b = new StringBuilder(s);
        for (int i = 0; i < n && b.length() > 2; i++) {
            int at = rnd.nextInt(b.length());
            switch (rnd.nextInt(7)) {
                case 0 -> b.deleteCharAt(at);
                case 1 -> b.insert(at, "(){}[];,*&<>=+-\"'#\\".charAt(rnd.nextInt(19)));
                case 2 -> {
                    int len = Math.min(b.length() - at, 1 + rnd.nextInt(12));
                    b.delete(at, at + len);
                }
                case 3 -> {
                    int len = Math.min(b.length() - at, 1 + rnd.nextInt(20));
                    String piece = b.substring(at, at + len);
                    b.insert(rnd.nextInt(b.length()), piece);
                }
                case 4 -> b.setLength(at);
                case 5 -> {
                    String[] words = { "String", "int", "struct", "return", "static", "const", "&", "*", "0", "nullptr",
                            "new", "delete", "goto", "case 1:", "sizeof", "auto", "[]", "->", "::", "...", "~", "operator" };
                    b.insert(at, " " + words[rnd.nextInt(words.length)] + " ");
                }
                default -> {
                    int nl = b.indexOf("\n", at);
                    if (nl > 0) {
                        int end = b.indexOf("\n", nl + 1);
                        if (end > 0)
                            b.delete(nl, end);
                    }
                }
            }
        }
        return b.toString();
    }
}
