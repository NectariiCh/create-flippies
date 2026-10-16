package com.nectariich.create_flippies.content.plc.mcu.lang;

import static com.nectariich.create_flippies.content.plc.mcu.lang.LangTests.check;
import static com.nectariich.create_flippies.content.plc.mcu.lang.LangTests.s;
import static com.nectariich.create_flippies.content.plc.mcu.lang.LangTests.t;

import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * Regression cases for the second round of review findings against the language:
 * what arm-none-eabi-g++ -std=gnu++17 accepts and how the code it emits for
 * STM32duino behaves (order of evaluation, tail recursion, LDRD alignment,
 * variable-length arrays, .noinit), and what the emulator charges for it (EXTI
 * dispatch, flash wait states, fast-forwarded loops). They run as part of
 * {@link LangTests}, and so under JUnit through {@link SketchCompilerTest}.
 */
final class LangRegressions2 {

    static void add(List<LangTests.Case> l) {
        declarations(l);
        variableLengthArrays(l);
        orderOfEvaluation(l);
        tailRecursion(l);
        builtinsAndHeaders(l);
        memoryAndFaults(l);
        timing(l);
        compilerLimits(l);
    }

    private static boolean hasWarning(CompileResult r, String text) {
        for (Diagnostic d : r.diagnostics())
            if (d.severity() == Diagnostic.Severity.WARNING && d.toString().contains(text))
                return true;
        return false;
    }

    // ---------------- parsing and declarations ----------------

    private static void declarations(List<LangTests.Case> l) {
        // [dcl.ambig.res]: parentheses that cannot hold parameters hold an initializer.
        l.add(s("direct-initialisation from a functional cast", "String g(String(\"glob\"));", """
                String b(String("y")); outs(b.c_str());
                outs(g.c_str());
                int x(int(3.5)); out(x);
                String a = "x"; String c(a); outs(c.c_str());
                String d(c + "z"); outs(d.c_str());
                """).out("y", "glob", "3", "x", "xz"));
        // ... and where they can, it is still the most vexing parse: a function declaration.
        l.add(t("the most vexing parse is still a declaration", "int y = 7;\nvoid setup() { int x(int(y)); out(x); }\nvoid loop() {}\n")
                .error("no matching function for call to 'out(int (*)(int))'"));
        l.add(t("a function declared at block scope", "void setup() { int four(); out(four()); done(); }\nvoid loop() {}\nint four() { return 4; }\n")
                .out("4"));
        l.add(s("declarations in while, switch and for conditions", "", """
                int n = 0; while (int z = 3 - n) { n++; } out(n);
                int s = 0, m = 0; while (int z = 4 - m) { m++; if (z == 2) continue; s += z; } out(s);
                switch (int v = 2) { case 2: out(v); break; default: out(-1); }
                int k = 3; switch (k++; int w = k + 3) { case 7: out(w); break; default: out(0); }
                switch (k = 10; k) { case 10: out(k); break; }
                int f = 0; for (; int z = 3 - f; ) f++; out(f);
                """).out("3", "8", "2", "7", "10", "3"));
        l.add(s("__builtin_offsetof", "struct In { char b; short c; }; struct Out { char a; In s; };",
                "out(__builtin_offsetof(Out, s)); out(__builtin_offsetof(In, c));").out("2", "2"));
        l.add(s("a string literal in braces initialises a char array", """
                struct N { char name[8]; int v; };
                char g[] = {"abc"};
                """, """
                char s[] = {"abcd"}; out(sizeof(s)); outs(s);
                outs(g); out(sizeof(g));
                N n = {{"hi"}, 1}; outs(n.name);
                """).out("5", "abcd", "abc", "4", "hi"));
        l.add(t("conditional directives inside macro arguments", """
                #define ID(x) x
                #define ADD(a, b) ((a) + (b))
                void setup() {
                  out(ID(
                #ifdef __arm__
                  3
                #else
                  4
                #endif
                  ));
                  out(ADD(5,
                #if 0
                  100
                #elif 1
                  3
                #endif
                  ));
                  done();
                }
                void loop() {}
                """).out("3", "8"));
        l.add(t("a for-init name redeclared in the body", "void setup() { for (int i = 0; i < 2; i++) { int i = 5; out(i); } }\nvoid loop() {}\n")
                .error("redeclaration of 'int i'"));
        l.add(t("an if-condition name redeclared in the body", "void setup() { if (int x = 1) { int x = 2; out(x); } }\nvoid loop() {}\n")
                .error("redeclaration of 'int x'"));
        l.add(s("a nested block may reuse a for-init name", "", "for (int i = 0; i < 2; i++) { { int i = 5; out(i); } }").out("5", "5"));
        l.add(s("u8 character literal", "", "out(u8'a'); out(sizeof(u8'a'));").out("97", "1"));
        l.add(s("range-for over a braced list", "", """
                int s = 0; for (int x : {1, 2, 3}) s += x; out(s);
                for (char c : {'a', 'b'}) out(c);
                for (auto v : {10L, 20L}) out(v);
                """).out("6", "97", "98", "10", "20"));
        l.add(t("range-for over a list of mixed types", "void setup() { for (auto x : {1, 2.0}) out(x); }\nvoid loop() {}\n")
                .error("deduced conflicting types 'int' and 'double'"));
        l.add(s("nullptr compared with 0 and NULL", "", "out(nullptr == 0); out(nullptr == NULL); out(0 != nullptr);").out("1", "1", "0"));
        l.add(t("an ordered comparison with nullptr is still an error", "void setup() { out(nullptr < 0); }\nvoid loop() {}\n")
                .error("comparison between pointer and integer"));
        // A sketch's own variadic function cannot reach its extra arguments here: refuse rather than miscompile.
        l.add(s("variadic function called with its named parameters only", "int f(int n, ...) { return n; }", "out(f(4)); out(f(5));")
                .out("4", "5"));
        l.add(t("variadic function called with extra arguments", "int f(int n, ...) { return n; }\nvoid setup() { out(f(7, 8)); }\nvoid loop() {}\n")
                .error("calling variadic function 'f' with arguments beyond its named parameters is not supported on this PLC"));
        l.add(t("address of a variadic function", "int f(int n, ...) { return n; }\nvoid setup() { int (*p)(int, ...) = f; out(p(4)); }\nvoid loop() {}\n")
                .error("taking the address of variadic function 'f' is not supported on this PLC"));
        l.add(s("the core's variadic functions still take extra arguments", "", "out(sumv(3, 1, 2, 3));").out("6"));
    }

    // ---------------- variable-length arrays ----------------

    private static void variableLengthArrays(List<LangTests.Case> l) {
        l.add(s("variable-length array", """
                int f(int n) {
                  int a[n];
                  for (int i = 0; i < n; i++) a[i] = i * 2;
                  out(sizeof(a)); out(sizeof(a) / sizeof(a[0]));
                  return a[n - 1];
                }
                """, """
                out(f(5)); out(f(1));
                int m = 2; int b[m][3]; b[1][2] = 3; out(b[1][2]); out(sizeof(b)); out(sizeof(b[0]));
                """).out("20", "5", "8", "4", "1", "0", "3", "24", "12"));
        // Its storage is given back when the scope ends, however the scope is left.
        l.add(s("variable-length arrays release their stack", """
                int f(int n) { byte buf[n]; buf[0] = 1; buf[n - 1] = 2; return buf[0]; }
                int g(int n) { for (int i = 0; i < 4; i++) { char t[n]; t[0] = i; if (i == 2) break; if (i == 1) continue; } return n; }
                """, """
                int s = 0;
                for (int i = 0; i < 1000; i++) s += f(100 + i % 50);
                out(s);
                for (int i = 0; i < 500; i++) { int a[i + 1]; a[i] = i; s += a[i] - i; }
                out(s);
                for (int i = 0; i < 1000; i++) g(200);
                out(1);
                """).out("1000", "1000", "1"));
        l.add(t("variable-length array beyond SRAM", "void setup() {\n  volatile int n = 30000;\n  char big[n];\n  big[0] = 1;\n}\nvoid loop() {}\n")
                .fault(HardFault.Kind.STACK_OVERFLOW, 3));
        l.add(t("variable-length array at file scope", "int n = 3;\nint a[n];\nvoid setup() {}\nvoid loop() {}\n")
                .error("array bound is not an integer constant"));
        l.add(t("static variable-length array", "void setup() { volatile int n = 3; static int a[n]; }\nvoid loop() {}\n")
                .error("array bound is not an integer constant"));
        l.add(t("initialised variable-length array", "void setup() { int n = 3; int a[n] = {1, 2, 3}; }\nvoid loop() {}\n")
                .error("variable-sized object 'a' may not be initialized"));
        l.add(t("jump into a variable-length array's scope", "void setup() { int n = 2; goto x; { int a[n];\n x: a[0] = 1; } }\nvoid loop() {}\n")
                .error("which has variably modified type"));
    }

    // ---------------- C++17 order of evaluation ----------------

    private static void orderOfEvaluation(List<LangTests.Case> l) {
        // -std=gnu++17: the right operand of = and op= is sequenced before the left (P0145).
        l.add(s("assignment evaluates its right operand first", """
                int seq = 0; int arr[4]; int g;
                int L() { seq = seq * 10 + 1; return 0; }
                int R() { seq = seq * 10 + 2; return 5; }
                int* LP() { seq = seq * 10 + 1; return &g; }
                String sarr[2]; String RS() { seq = seq * 10 + 2; return String("x"); }
                struct P { int x, y; }; P parr[2]; P RP() { seq = seq * 10 + 2; P p = {3, 4}; return p; }
                """, """
                arr[L()] = R(); out(seq);
                seq = 0; arr[L()] += R(); out(seq);
                seq = 0; *LP() = R(); out(seq);
                seq = 0; sarr[L()] = RS(); out(seq);
                seq = 0; sarr[L()] += RS(); out(seq);
                seq = 0; parr[L()] = RP(); out(seq); out(parr[0].y);
                int a[3] = {9, 9, 9}; int i = 0; a[i] = i++; out(a[0]); out(a[1]);
                """).out("21", "21", "21", "21", "21", "21", "4", "9", "0"));
        l.add(s("compound assignment reads its target after the right operand", """
                int g = 1; int f() { g = 10; return 1; }
                struct B { unsigned a : 4; unsigned b : 4; } bf;
                int h() { bf.a = 5; return 1; }
                """, """
                g += f(); out(g);
                int x = 1; x += (x = 5); out(x);
                bf.a = 1; bf.a += h(); out(bf.a);
                int y = 10; y -= (y = 7); out(y);
                int q[2] = {10, 3}; int k = 0; q[k] -= q[1]; out(q[0]);
                int d = 9; d /= (d = 3) - 0; out(d);
                long long w = 5; w <<= 2; out((int)w);
                int arr[4] = {10, 20, 30, 40}; int* p = arr; int m = 2; p += m; out(*p); p -= (m = 1); out(*p);
                """).out("11", "10", "6", "0", "7", "1", "20", "30", "20"));
        l.add(s("the value and address of an assignment", "", """
                int a[2]; int b = (a[1] = 5); out(b);
                int c = 0; int e = (c += 7); out(e);
                int z = 0; (z = 3) = 4; out(z);
                int u = 1; (u += 2) += 3; out(u);
                String s1, s2; s2 = s1 = "ab"; outs(s1.c_str()); outs(s2.c_str());
                """).out("5", "7", "4", "6", "ab", "ab"));
    }

    // ---------------- tail recursion ----------------

    private static void tailRecursion(List<LangTests.Case> l) {
        // GCC -Os makes these loops; on the board they never run out of stack.
        l.add(s("tail recursion with an addend", "int d(int n) { return n == 0 ? 0 : 1 + d(n - 1); }", "out(d(3000)); out(d(20000));")
                .out("3000", "20000"));
        l.add(s("tail recursion through a returned local", "int d(int n, int acc) { if (n == 0) return acc; int r = d(n - 1, acc + 1); return r; }",
                "out(d(5000, 0));").out("5000"));
        l.add(s("tail recursion with factors and mixed forms", """
                long long fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                int g(int n) { return n == 0 ? 1 : 3 + 2 * g(n - 1); }
                int h(int n) { if (n == 0) return 1; return h(n - 1) + 1; }
                unsigned p2(int n) { return n == 0 ? 1 : 2 * p2(n - 1); }
                int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }
                int calls; int bump() { return ++calls; }
                int e(int n) { return n == 0 ? 0 : bump() + e(n - 1); }
                """, """
                outll(fact(10)); out(g(3)); out(h(4000)); outu(p2(31)); out(gcd(1071, 462));
                out(e(3000)); out(calls);
                """).out("3628800", "29", "4001", "2147483648", "21", "4501500", "3000"));
        // Only where it is the same program.
        l.add(t("no tail recursion when a global is read after the call", "int k;\nint d(int n) { return n == 0 ? 0 : d(n - 1) + k; }\nvoid setup() { out(d(3000)); }\nvoid loop() {}\n")
                .fault(HardFault.Kind.STACK_OVERFLOW, 0));
        l.add(t("no tail recursion when a local's address is taken", "int d(int n) { int x = n; int* p = &x; return n == 0 ? *p : d(n - 1); }\nvoid setup() { out(d(3000)); }\nvoid loop() {}\n")
                .fault(HardFault.Kind.STACK_OVERFLOW, 0));
        l.add(t("no tail recursion in floating point", "float d(int n) { return n == 0 ? 0 : 1.0f + d(n - 1); }\nvoid setup() { out((int)d(3000)); }\nvoid loop() {}\n")
                .fault(HardFault.Kind.STACK_OVERFLOW, 0));
    }

    // ---------------- builtins and headers ----------------

    private static void builtinsAndHeaders(List<LangTests.Case> l) {
        l.add(s("GCC bit builtins", "", """
                out(__builtin_clz(1)); out(__builtin_clz(0x80000000u)); out(__builtin_ctz(1)); out(__builtin_ctz(48));
                out(__builtin_popcount(7)); out(__builtin_popcountl(0xFFFFFFFFul));
                volatile unsigned z = 0; out(__builtin_clz(z)); out(__builtin_ctz(z));
                out(__builtin_clzll(0ULL)); out(__builtin_clzll(1ULL)); out(__builtin_ctzll(1ULL << 40));
                out(__builtin_popcountll(0xF000000000000001ULL)); out(__builtin_clzl(255ul));
                """).out("31", "0", "0", "4", "3", "32", "32", "32", "64", "63", "40", "5", "24"));
        l.add(t("assert that holds", "#include <assert.h>\nvoid setup() { assert(1 == 1); out(1); done(); }\nvoid loop() {}\n").out("1"));
        l.add(t("assert that fails aborts", "#include <assert.h>\nvoid setup() {\n  int x = 2;\n  assert(x == 1);\n}\nvoid loop() {}\n")
                .fault(HardFault.Kind.ABORT, 4));
        l.add(t("NDEBUG turns assert off", "#define NDEBUG\n#include <cassert>\nvoid setup() { assert(0); out(5); done(); }\nvoid loop() {}\n").out("5"));
        l.add(t("IWatchdog.h is refused for what it is", "#include <IWatchdog.h>\nvoid setup() {}\nvoid loop() {}\n")
                .error("IWatchdog.h: the independent watchdog (IWDG) library is not supported on this PLC"));
        // The core's F() is the literal cast to const __FlashStringHelper*, which WString takes.
        l.add(s("F() strings work with String", """
                struct __FlashStringHelper;
                #define FS(s) ((const __FlashStringHelper *)(s))
                int which(const char*) { return 1; }
                int which(const __FlashStringHelper*) { return 2; }
                """, """
                String a = FS("ab"); outs(a.c_str());
                String b(FS("cd")); outs(b.c_str());
                a += FS("x"); a.concat(FS("y")); outs(a.c_str());
                String c = b + FS("!"); outs(c.c_str());
                a = FS("z"); outs(a.c_str()); out(a == FS("z"));
                out(which("q")); out(which(FS("q")));
                """).out("ab", "cd", "abxy", "cd!", "z", "1", "1", "2"));
        l.add(t("an F() string is not a const char*", "struct __FlashStringHelper;\nvoid setup() { out(strlen((const __FlashStringHelper*)\"ab\")); }\nvoid loop() {}\n")
                .error("const __FlashStringHelper"));
        l.add(t("a communication library keeps its message", "#include <Wire.h>\nvoid setup() {}\nvoid loop() {}\n")
                .error("no communication or storage peripherals"));
    }

    // ---------------- memory: .noinit and LDRD alignment ----------------

    private static void memoryAndFaults(List<LangTests.Case> l) {
        // .noinit is outside the zeroed .bss, so a warm reset (here: the CPU's own reset
        // over the same SRAM) keeps it; an ordinary global starts again from zero.
        String boots = """
                uint32_t boots __attribute__((section(".noinit")));
                uint32_t magic __attribute__((section(".noinit")));
                uint32_t gcc12 __attribute__((noinit));
                uint32_t plain;
                void setup() {
                  if (magic != 0xC0FFEE) { magic = 0xC0FFEE; boots = 0; gcc12 = 0; }
                  boots++; gcc12 += 10; plain++;
                  out(boots); out(gcc12); out(plain);
                  done();
                }
                void loop() {}
                """;
        l.add(t(".noinit survives a warm reset", boots).out("1", "10", "1").then(e -> {
            e.cpu.reset();
            e.done = false;
            e.out.clear();
            e.runUntilDone(e.ledger.now() + 72_000_000L);
            check(e.out.equals(List.of("2", "20", "1")), "after the reset: " + e.out);
        }));
        l.add(t(".noinit with an initializer warns", "int n __attribute__((section(\".noinit\"))) = 5;\nvoid setup() { out(n); done(); }\nvoid loop() {}\n")
                .out("0").onCompile(r -> check(hasWarning(r, "initializer is never applied"), "warning: " + r.diagnostics())));
        // LDRD/STRD fault on an address that is not word aligned, whatever UNALIGN_TRP says.
        l.add(t("unaligned 64-bit load faults", "void setup() {\n  uint8_t b[16] = {0};\n  volatile uint64_t* p = (uint64_t*)(b + 1);\n  outll(*p);\n}\nvoid loop() {}\n")
                .fault(HardFault.Kind.USAGE_FAULT, 4));
        l.add(t("unaligned double store faults", "void setup() {\n  uint8_t b[16] = {0};\n  volatile double* p = (double*)(b + 2);\n  *p = 1.5;\n}\nvoid loop() {}\n")
                .fault(HardFault.Kind.USAGE_FAULT, 4));
        l.add(s("packed and word-aligned 64-bit accesses do not fault", """
                struct __attribute__((packed)) S { uint8_t a; uint64_t v; double d; };
                S s;
                """, """
                s.v = 5; s.d = 7.0; s.v++; s.v += 2; outll(s.v); out((int)s.d);
                S* ps = &s; ps->v = 9; outll(ps->v);
                uint32_t w[4] = {0}; uint64_t* q = (uint64_t*)(w + 1); *q = 11; outll(*q);
                """).out("8", "7", "9", "11"));
    }

    // ---------------- what the emulator charges ----------------

    private static void timing(List<LangTests.Case> l) {
        // STM32duino's EXTI path: HAL_GPIO_EXTI_IRQHandler, the get_pin_id loop and
        // std::function, more for the shared vectors EXTI9_5 and EXTI15_10.
        l.add(t("EXTI interrupts pay the HAL dispatch", """
                int hits;
                void isr() { hits++; }
                void setup() { attachInterrupt(0, isr, RISING); attachInterrupt(7, isr, RISING); done(); }
                void loop() {}
                """).then(e -> {
            long[] per = new long[2];
            int[] lines = { 0, 7 };
            for (int k = 0; k < 2; k++) {
                long before = e.ledger.cycles(CycleClass.INTERRUPT);
                e.cpu.pendInterrupt(lines[k], e.handlers[lines[k]]);
                e.cpu.run(e.ledger.now() + 100_000, System.nanoTime() + 5_000_000_000L);
                per[k] = e.ledger.cycles(CycleClass.INTERRUPT) - before;
            }
            check(Vm.extiDispatchCycles(0) == 50 && Vm.extiDispatchCycles(7) == 50 + 60 + 28 && Vm.extiDispatchCycles(11) == 50 + 72 + 44,
                    "dispatch table");
            check(per[1] - per[0] == Vm.extiDispatchCycles(7) - Vm.extiDispatchCycles(0), "line 7 vs line 0: " + per[1] + " vs " + per[0]);
            check(per[0] >= 12 + 50 + 12 + Vm.EXTI_RETURN_CYCLES, "an empty handler on line 0 costs " + per[0]);
        }));
        // A taken branch refills the pipeline from flash with two wait states at 72 MHz.
        l.add(t("taken branches pay the flash wait states", "void setup() { for (volatile int i = 0; i < 10000; i++) {} done(); }\nvoid loop() {}\n")
                .then(e -> {
                    long br = e.ledger.cycles(CycleClass.BRANCH);
                    check(CodeGen.TAKEN == 3 + Stm32f103cb.FLASH_WAIT_STATES, "TAKEN " + CodeGen.TAKEN);
                    check(br >= 10000L * CodeGen.TAKEN && br < 10000L * CodeGen.TAKEN + 400, "BRANCH cycles " + br);
                }));
        // A fast-forwarded polling loop is the core running flat out, not sleeping.
        l.add(t("fast-forwarded loops are charged as what they run", """
                void setup() {
                  unsigned long t0 = millis();
                  while (millis() - t0 < 100) { }
                  done();
                }
                void loop() {}
                """).then(e -> {
            long busy = 0;
            for (CycleClass c : CycleClass.values())
                if (c != CycleClass.WAIT)
                    busy += e.ledger.cycles(c);
            check(((Vm) e.cpu).skippedIterations > 0, "the loop was not skipped");
            check(busy >= 100L * 72_000, "busy cycles " + busy + ", WAIT " + e.ledger.cycles(CycleClass.WAIT));
        }));
    }

    // ---------------- compiler limits ----------------

    private static void compilerLimits(List<LangTests.Case> l) {
        // Folding asks at every operator whether the chain beneath is constant: linear, not quadratic.
        // (Inside sizeof: checked and folded operator by operator, but no code, which would not fit in flash.)
        String chain = "volatile int v;\nint f() { return sizeof(" + "!".repeat(60000) + "v); }\nvoid setup() { out(f()); done(); }\nvoid loop() {}\n";
        l.add(t("a 60 000-operator unary chain compiles quickly", chain).out("1").then(e -> {
            long t0 = System.nanoTime();
            SketchCompiler.compile(chain, LangTests.LIB);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(ms < 3000, "compiling took " + ms + " ms");
        }));
        l.add(t("a 10 000-dimension array type", "int x" + "[1]".repeat(10000) + ";\nvoid setup() { out(sizeof(x)); done(); }\nvoid loop() {}\n")
                .out("4"));
        // '##' doubling a token at each level would otherwise build one as large as the heap.
        StringBuilder paste = new StringBuilder("#define C(a, b) a##b\n#define D(a) C(a, a)\nint ");
        paste.append("D(".repeat(27)).append('x').append(")".repeat(27)).append(";\nvoid setup() {}\nvoid loop() {}\n");
        l.add(t("token pasting cannot build an unbounded token", paste.toString()).error("longer than 65536 characters"));
        StringBuilder str = new StringBuilder("#define S(x) #x\n#define T(x) S(x)\n#define C(a, b) a##b\n#define D(a) C(a, a)\nconst char* s = T(");
        str.append("D(".repeat(27)).append('x').append(")".repeat(27)).append(");\nvoid setup() {}\nvoid loop() {}\n");
        l.add(t("stringizing cannot build an unbounded literal", str.toString()).error("longer than 65536 characters"));
        StringBuilder ok = new StringBuilder("#define C(a, b) a##b\n#define D(a) C(a, a)\nint ");
        ok.append("D(".repeat(10)).append('x').append(")".repeat(10)).append(" = 3;\nvoid setup() { out(sizeof(int)); done(); }\nvoid loop() {}\n");
        l.add(t("token pasting within the limit", ok.toString()).out("4"));
    }

    private LangRegressions2() {}
}
