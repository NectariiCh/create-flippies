package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.CpuCore;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;

/**
 * The language test suite, runnable without Gradle:
 * {@code java -cp <out> com.nectariich.create_flippies.content.plc.mcu.lang.LangTests}.
 *
 * Each case compiles a sketch with {@link TestLibrary}, runs it on a fresh CPU and
 * compares what it printed (through the {@code out*} builtins) with what
 * arm-none-eabi-gcc's code prints on the board, or checks the diagnostic or fault
 * it must produce.
 */
public final class LangTests {

    static int passed, failed;
    static final List<String> failures = new ArrayList<>();
    static final TestLibrary LIB = new TestLibrary();

    public static void main(String[] args) {
        String only = args.length > 0 ? args[0] : null;
        for (Case c : cases()) {
            if (only != null && !c.name.contains(only))
                continue;
            try {
                c.run();
            } catch (Throwable t) {
                fail(c.name, "threw " + t);
                t.printStackTrace();
            }
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        for (String f : failures)
            System.out.println("  FAIL " + f);
        if (failed > 0)
            System.exit(1);
    }

    static void pass(String name) {
        passed++;
        System.out.println("ok   " + name);
    }

    /** The most recent failure, for the JUnit wrapper's message. */
    static String lastFailure;

    static void fail(String name, String why) {
        failed++;
        lastFailure = name + ": " + why;
        failures.add(name + ": " + why);
        System.out.println("FAIL " + name + ": " + why);
    }

    static final class Case {
        final String name;
        final String src;
        List<String> expect;
        String compileError;
        HardFault.Kind fault;
        int faultLine;
        java.util.function.Consumer<TestEnv> extra;
        java.util.function.Consumer<CompileResult> compiled;

        Case(String name, String src) {
            this.name = name;
            this.src = src;
        }

        Case out(String... e) {
            expect = Arrays.asList(e);
            return this;
        }

        Case error(String e) {
            compileError = e;
            return this;
        }

        Case fault(HardFault.Kind k, int line) {
            fault = k;
            faultLine = line;
            return this;
        }

        Case then(java.util.function.Consumer<TestEnv> f) {
            extra = f;
            return this;
        }

        Case onCompile(java.util.function.Consumer<CompileResult> f) {
            compiled = f;
            return this;
        }

        void run() {
            CompileResult r = SketchCompiler.compile(src, LIB);
            if (compileError != null) {
                for (Diagnostic dg : r.diagnostics()) {
                    if (dg.toString().contains(compileError) && (dg.severity() == Diagnostic.Severity.ERROR || dg.severity() == Diagnostic.Severity.NOTE || dg.severity() == Diagnostic.Severity.WARNING)) {
                        if (compiled != null)
                            compiled.accept(r);
                        pass(name);
                        return;
                    }
                }
                fail(name, "expected diagnostic containing '" + compileError + "', got " + r.diagnostics());
                return;
            }
            if (!r.ok()) {
                fail(name, "did not compile: " + r.diagnostics());
                return;
            }
            if (compiled != null)
                compiled.accept(r);
            TestEnv e = TestEnv.boot(r);
            CpuCore.Exit x = e.runUntilDone(72_000_000L * 30);
            if (fault != null) {
                if (!e.cpu.halted()) {
                    fail(name, "expected " + fault + ", ran to " + x + " out=" + e.out);
                    return;
                }
                if (e.cpu.fault().kind() != fault) {
                    fail(name, "expected " + fault + ", got " + e.cpu.fault().kind() + ": " + e.cpu.fault().getMessage());
                    return;
                }
                if (faultLine > 0 && e.cpu.currentLine() != faultLine) {
                    fail(name, "fault at line " + e.cpu.currentLine() + ", expected " + faultLine + " (" + e.cpu.fault().getMessage() + ")");
                    return;
                }
            } else if (e.cpu.halted()) {
                fail(name, "faulted: " + e.cpu.fault().kind() + " " + e.cpu.fault().getMessage() + " at line " + e.cpu.currentLine() + " out=" + e.out);
                return;
            } else if (!e.done) {
                fail(name, "did not finish; out=" + e.out);
                return;
            }
            if (expect != null && !expect.equals(e.out)) {
                fail(name, "\n   expected " + expect + "\n   got      " + e.out);
                return;
            }
            if (extra != null) {
                try {
                    extra.accept(e);
                } catch (AssertionError ae) {
                    fail(name, ae.getMessage());
                    return;
                }
            }
            pass(name);
        }
    }

    static void check(boolean b, String what) {
        if (!b)
            throw new AssertionError(what);
    }

    static Case t(String name, String body) {
        return new Case(name, body);
    }

    /** A sketch whose setup() is {@code body} followed by done(). */
    static Case s(String name, String decls, String body) {
        return new Case(name, decls + "\nvoid setup() {\n" + body + "\n done();\n}\nvoid loop() {}\n");
    }

    static List<Case> cases() {
        List<Case> l = new ArrayList<>();

        // ---------------- integer semantics ----------------
        l.add(s("promotions", "", """
                uint8_t a = 200, b = 100;
                out(a + b);
                uint8_t c = a + b;
                out(c);
                unsigned int u = 0; u--;
                outu(u);
                int i = 2147483647; i++;
                out(i);
                out(-1 < 1u);
                out((int8_t)200);
                char ch = 200;
                out(ch > 127);
                out(ch);
                signed char sc = -5;
                out(sc);
                short sh = 32767; sh++;
                out(sh);
                unsigned short us = 65535;
                out(us + 1);
                out(sizeof(us + 1));
                long l1 = -1; unsigned int u1 = 1;
                out(l1 < u1);
                long long ll = -1; unsigned long ul = 1;
                out(ll < ul);
                """).out("300", "44", "4294967295", "-2147483648", "0", "-56", "1", "200", "-5", "-32768", "65536", "4", "0", "1"));
        l.add(s("division", "", """
                out(7 / -2); out(7 % -2); out(-7 / 2); out(-7 % 2);
                volatile int z = 0;
                out(5 / z); out(5 % z);
                unsigned uz = 0; outu(100u / uz); outu(100u % uz);
                int mn = -2147483647 - 1; volatile int m1 = -1;
                out(mn / m1); out(mn % m1);
                long long a = 1LL << 40; outll(a / 3); outll(a % 7); outll(-a / 5);
                unsigned long long b = 0xFFFFFFFFFFFFFFFFULL; outull(b / 10); outull(b % 1000);
                """).out("-3", "1", "-3", "-1", "0", "5", "0", "100", "-2147483648", "0", "366503875925", "2", "-219902325555",
                "1844674407370955161", "615"));
        l.add(s("shifts", "", """
                out(1 << 31);
                volatile int s = 33;
                out(1 << s);
                out(-16 >> 2);
                unsigned int u = 0x80000000u;
                outu(u >> 31);
                volatile int t = 32;
                outu(u >> t);
                out(-1 >> t);
                long long ll = 1; outll(ll << 40); outll(-ll >> 1);
                uint8_t b = 1; out(b << 9);
                """).out("-2147483648", "0", "-4", "1", "0", "-1", "1099511627776", "-1", "512"));
        l.add(s("literals", "", """
                out(sizeof(4294967295)); out(sizeof(0xFFFFFFFF)); out(0xFFFFFFFF == -1); out(4294967295 == -1);
                out(0b1011); out(017); out('A'); out(sizeof('A')); out('\\n'); out('\\xff'); out(B101);
                out(sizeof(1L)); out(sizeof(1LL)); out(sizeof(1.0f)); out(sizeof(1.0)); out(10'000);
                outu(3000000000u); out(sizeof(3000000000));
                """).out("8", "4", "1", "0", "11", "15", "65", "1", "10", "255", "5", "4", "8", "4", "8", "10000", "3000000000", "8"));
        l.add(s("sizes", "", """
                out(sizeof(int)); out(sizeof(long)); out(sizeof(long long)); out(sizeof(double)); out(sizeof(char*));
                out(sizeof(bool)); out(sizeof(float)); out(sizeof(short)); out(sizeof(size_t)); out(sizeof(long double));
                out(sizeof(int32_t)); out(sizeof(uint64_t)); out(sizeof(String));
                """).out("4", "4", "8", "8", "4", "1", "4", "2", "4", "8", "4", "8", "12"));

        // ---------------- floating point ----------------
        l.add(s("floats", "", """
                float f = 0.1f; double d = 0.1;
                outf(f); outf(d); outf(f * 3); outf(1.0f / 3); outf(1.0 / 3);
                out((int)3.99f); out((int)-3.99); outf((float)16777217);
                out((unsigned)-1.0f); out((int)1e20f); out((int)-1e20);
                float nan = 0.0f / 0.0f; out(nan == nan); out(nan != nan);
                double x = 2; outf(sqrt(x)); outf(sqrtf(2.0f));
                int i = 7; outf(i / 2); outf(i / 2.0); outf(i / 2.0f);
                out(0.1 + 0.2 == 0.3); out(0.1f + 0.2f == 0.3f);
                long long big = 9007199254740993LL; outf((double)big);
                unsigned long long ub = 0xFFFFFFFFFFFFFFFFULL; outf((float)ub);
                """).out("0.10000000149011612", "0.1", "0.30000001192092896", "0.3333333432674408", "0.3333333333333333",
                "3", "-3", "16777216.0", "0", "2147483647", "-2147483648", "0", "1", "1.4142135623730951", "1.4142135381698608",
                "3.0", "3.5", "3.5", "0", "1", "9.007199254740992E15", "1.8446744073709552E19"));

        // ---------------- structs and layout ----------------
        l.add(s("struct layout", """
                struct A { char c; int i; };
                struct B { char c; double d; char e; };
                struct C { char a; short b; char c; };
                struct D { long long x; char y; };
                union U { int i; char c[5]; };
                struct BF { unsigned a:3; unsigned b:5; unsigned c:30; };
                struct SB { int x:4; int y:4; };
                struct N { A a; B b[2]; };
                """, """
                out(sizeof(A)); out(sizeof(B)); out(sizeof(C)); out(sizeof(D)); out(sizeof(U)); out(sizeof(BF));
                out(offsetof(B, d)); out(offsetof(B, e)); out(sizeof(N)); out(offsetof(N, b));
                BF bf = {}; bf.a = 9; out(bf.a); bf.b = 31; out(bf.b); bf.c = 0x3FFFFFFF; outu(bf.c); out(bf.a);
                SB sb; sb.x = 7; sb.x++; out(sb.x); sb.y = -1; out(sb.y); out(sb.x);
                U u; u.i = 0x41424344; out(u.c[0]); out(u.c[3]);
                A a1 = {'x', 5}; A a2 = a1; a2.i = 9; out(a1.i); out(a2.i); out(a2.c);
                B bb = {1, 2.5, 3}; outf(bb.d); out(bb.e);
                N n = {{1, 2}, {{3, 4.0, 5}, {6, 7.0, 8}}}; out(n.b[1].e); outf(n.b[0].d);
                A *pa = &a1; pa->i = 42; out(a1.i); out((*pa).c);
                struct P { int x, y; } pts[3] = {{1, 2}, {3, 4}}; out(pts[1].y); out(pts[2].x);
                """).out("8", "24", "6", "16", "8", "8", "8", "16", "56", "8", "1", "31", "1073741823", "1", "-8", "-1", "-8",
                "68", "65", "5", "9", "120", "2.5", "3", "8", "4.0", "42", "120", "4", "0"));
        l.add(s("struct copy and return", """
                struct V { int x, y, z; };
                V make(int a) { V v = {a, a * 2, a * 3}; return v; }
                V addv(V a, V b) { V r; r.x = a.x + b.x; r.y = a.y + b.y; r.z = a.z + b.z; return r; }
                void bump(V &v) { v.x++; }
                int sum(const V *v) { return v->x + v->y + v->z; }
                """, """
                V a = make(2); V b = make(10);
                V c = addv(a, b);
                out(c.x); out(c.y); out(c.z);
                bump(c); out(c.x);
                out(sum(&c));
                a = b; out(a.z);
                out(make(5).y);
                """).out("12", "24", "36", "13", "73", "30", "10"));

        // ---------------- pointers and arrays ----------------
        l.add(s("pointers", "", """
                int arr[5] = {1, 2, 3};
                out(arr[3]);
                int *p = arr; p += 2; out(*p); out(p - arr); out(p[-1]);
                int m[2][3] = {{1, 2, 3}, {4, 5, 6}}; out(m[1][2]); out(sizeof(m)); out(sizeof(m[0]));
                int m2[2][2] = {1, 2, 3, 4}; out(m2[1][0]);
                char s[] = "hello"; out(sizeof(s)); s[0] = 'H'; outs(s);
                const char *msg = "abc"; out(msg[1]); out(*(msg + 2));
                int *q = &arr[4]; out(q > p); out(q - p);
                int *np = nullptr; out(np == 0); out(!np);
                void *vp = arr; int *ip = (int*)vp; out(ip[1]);
                char buf[4] = "ab"; out(buf[3]);
                int x = 5; int *px = &x; int **ppx = &px; **ppx = 9; out(x);
                out(2[arr]);
                uintptr_t addr = (uintptr_t)&arr[1] - (uintptr_t)&arr[0]; out(addr);
                for (int i = 0; i < 5; i++) arr[i] = i * i;
                int total = 0; for (int *it = arr; it != arr + 5; ++it) total += *it; out(total);
                int r[3] = {7, 8, 9}; int acc = 0; for (int v : r) acc += v; out(acc);
                for (auto &v : r) v *= 2; out(r[2]);
                """).out("0", "3", "2", "2", "6", "24", "12", "3", "6", "Hello", "98", "99", "1", "2", "1", "1", "2", "0", "9",
                "3", "4", "30", "24", "18"));

        // ---------------- functions ----------------
        // later() is defined after its use: the .ino auto-prototype.
        l.add(new Case("functions", """
                int add(int a, int b) { return a + b; }
                int mul(int a, int b) { return a * b; }
                int (*ops[2])(int, int) = {add, mul};
                int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                long fib(int n) { if (n < 2) return n; return fib(n - 1) + fib(n - 2); }
                typedef int (*binop)(int, int);
                int apply(binop f, int a, int b) { return f(a, b); }
                int over(int x) { return 1; }
                int over(long x) { return 2; }
                int over(double x) { return 3; }
                int over(const char *s) { return 4; }
                int defs(int a, int b = 10, int c = 100) { return a + b + c; }
                void swapr(int &a, int &b) { int t = a; a = b; b = t; }
                void setup() {
                  out(ops[0](3, 4)); out(ops[1](3, 4)); out(fact(10)); out(fib(20));
                  binop f = &add; out(f(1, 2)); out((*f)(2, 3)); out(apply(mul, 6, 7));
                  out(over(1)); out(over(1L)); out(over(1.0)); out(over("x")); out(over('c'));
                  out(defs(1)); out(defs(1, 2)); out(defs(1, 2, 3));
                  int a = 1, b = 2; swapr(a, b); out(a); out(b);
                  out(later(5));
                  auto sq = [](int v) { return v * v; };
                  out(sq(9));
                  done();
                }
                void loop() {}
                int later(int x) { return x * 100; }
                """).out("7", "12", "3628800", "6765", "3", "5", "42", "1", "2", "3", "4", "1", "111", "103", "6", "2", "1", "500", "81"));

        // ---------------- control flow ----------------
        l.add(s("control", """
                int counter() { static int n = 0; return ++n; }
                int sw(int x) {
                  int r = 0;
                  switch (x) {
                    case 1: r += 1;
                    case 2: r += 2; break;
                    case 3: r = 30; break;
                    case 10 ... 12: r = 100; break;
                    default: r = -1;
                  }
                  return r;
                }
                enum Color { RED, GREEN = 5, BLUE };
                enum class Mode : uint8_t { Off, On, Auto };
                """, """
                counter(); counter(); out(counter());
                out(sw(1)); out(sw(2)); out(sw(3)); out(sw(9)); out(sw(11));
                int i = 0;
                again:
                i++;
                if (i < 5) goto again;
                out(i);
                int n = 0; while (true) { if (++n == 7) break; } out(n);
                int k = 0; do { k += 2; } while (k < 9); out(k);
                int odd = 0; for (int j = 0; j < 10; j++) { if (j % 2 == 0) continue; odd += j; } out(odd);
                out(BLUE); Color c = GREEN; out(c);
                Mode m = Mode::Auto; out((int)m); out(sizeof(Mode)); out(m == Mode::Auto);
                switch (m) { case Mode::On: out(1); break; case Mode::Auto: out(2); break; default: break; }
                int x = 3, y = (x++, x * 10); out(y);
                out(x > 2 && y < 100 || false);
                int cnt = 0; for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) { if (b == 2) break; cnt++; } out(cnt);
                """).out("3", "3", "2", "30", "-1", "100", "5", "7", "10", "25", "6", "5", "2", "1", "1", "2", "40", "1", "6"));

        // ---------------- preprocessor ----------------
        l.add(s("preprocessor", """
                #define SQUARE(x) ((x) * (x))
                #define STR(x) #x
                #define XSTR(x) STR(x)
                #define CAT(a, b) a ## b
                #define VA(fmt, ...) out(fmt + sum3(__VA_ARGS__))
                #define NUM 42
                #if NUM > 40 && defined(NUM)
                #define BIG 1
                #elif NUM > 10
                #define BIG 2
                #else
                #define BIG 3
                #endif
                #ifndef UNDEFINED_THING
                #define FLAG 7
                #endif
                #undef NUM
                #ifdef NUM
                #error should not happen
                #endif
                int sum3(int a, int b, int c) { return a + b + c; }
                int CAT(my, var) = 11;
                """, """
                out(SQUARE(1 + 2)); outs(STR(hello world)); outs(XSTR(BIG)); out(myvar); VA(100, 1, 2, 3);
                out(BIG); out(FLAG); out(__LINE__); out(__COUNTER__); out(__COUNTER__);
                outs(__FILE__); out(ARDUINO > 100); out(__cplusplus >= 201103L);
                out(min(3, 9)); out(max(3, 9)); out(abs(-4)); out(bitRead(5, 2));
                """).out("9", "hello world", "1", "11", "106", "1", "7", "26", "0", "1", "sketch.ino", "1", "1", "3", "9", "4", "1"));

        // ---------------- types ----------------
        l.add(s("types", """
                typedef unsigned char u8;
                using u16 = unsigned short;
                struct Pt { int x = 3; int y = 4; };
                const int N = 4;
                int table[N * 2];
                constexpr int K = N + 1;
                """, """
                u8 a = 255; a++; out(a);
                u16 b = 65535; b++; out(b);
                Pt p; out(p.x + p.y);
                Pt q = {10}; out(q.y);
                out(sizeof(table)); out(K);
                auto z = 3.5f; out(sizeof(z));
                auto w = 1ULL; out(sizeof(w));
                bool t = 5; out(t); boolean bb = false; out(bb);
                byte by = 300; out(by);
                word wd = 70000; outu(wd);
                int x = int(3.7); out(x); out(static_cast<int>(-2.5)); out(byte(258));
                int v = 10; int &r = v; r = 20; out(v);
                const int &cr = 5 + 6; out(cr);
                long l = (long)1e9; out(l);
                char cs[] = {'a', 'b', 0}; outs(cs);
                volatile int vol = 3; vol += 2; out(vol);
                register int reg = 6; out(reg);
                """).out("0", "0", "7", "4", "32", "5", "4", "8", "1", "0", "44", "70000", "3", "-2", "2", "20", "11", "1000000000",
                "ab", "5", "6"));

        // ---------------- String ----------------
        l.add(s("String basics", "", """
                String s = "Hello";
                s += ' '; s += "World"; s += 42;
                outs(s.c_str()); out(s.length());
                String t = String("x=") + 3.14159 + "," + String(255, 16) + ";" + 7 + 'c';
                outs(t.c_str());
                String u = s.substring(6, 11); outs(u.c_str());
                out(s.indexOf('W')); out(s.indexOf("World")); out(s.lastIndexOf('l')); out(s.indexOf("zz"));
                out(s == "Hello World42"); out(s != "x"); out(s.equalsIgnoreCase("hello world42"));
                String n = "  123abc "; n.trim(); outs(n.c_str()); out(n.toInt());
                String fl = "3.5"; outf(fl.toFloat());
                s.replace("World", "There"); outs(s.c_str());
                s.replace("e", "EE"); outs(s.c_str());
                s.replace("EE", ""); outs(s.c_str());
                s.remove(5); outs(s.c_str());
                s.toUpperCase(); outs(s.c_str());
                outs(String(-42).c_str()); outs(String(255, 16).c_str()); outs(String(1.5f).c_str());
                outs(String(2.345, 1).c_str()); outs(String(0.5, 0).c_str()); outs(String(-1.005, 2).c_str());
                outs(String((unsigned char)200).c_str()); outs(String(-1, 16).c_str()); outs(String(4294967295UL).c_str());
                char buf[10]; s.toCharArray(buf, 10); outs(buf);
                out(s.charAt(1)); s.setCharAt(0, 'h'); out(s[0]); s[1] = 'E'; outs(s.c_str());
                out(s.startsWith("hE")); out(s.endsWith("LO")); out(s.endsWith("x"));
                String a = "abc"; String b = "abd"; out(a < b); out(a.compareTo(b)); out(b > a); out(a >= "abc");
                String e; out(e.length()); out((bool)e); outs(e.c_str());
                String c('Z'); outs(c.c_str());
                """).out("Hello World42", "13", "x=3.14,ff;7c", "World", "6", "6", "9", "-1", "1", "1", "1", "123abc", "123", "3.5",
                "Hello There42", "HEEllo ThEErEE42", "Hllo Thr42", "Hllo ", "HLLO ", "-42", "ff", "1.50", "2.3", " 1", "-1.00", "200",
                "ffffffff", "4294967295", "HLLO ", "76", "104", "hELO ", "1", "0", "0", "1", "-1", "1", "1", "0", "1", "", "Z"));
        l.add(s("String functions", """
                String greet(String name) { return "Hi " + name + "!"; }
                String pad(const String &s, int n) { String r = s; while ((int)r.length() < n) r = "_" + r; return r; }
                int countChar(const String &s, char c) { int k = 0; for (unsigned i = 0; i < s.length(); i++) if (s[i] == c) k++; return k; }
                String names[3] = {"ann", "bob"};
                String g = greet("glob");
                """, """
                String n = "Bo";
                outs(greet(n).c_str()); outs(n.c_str());
                outs(pad("7", 3).c_str());
                out(countChar("banana", 'a'));
                outs(names[1].c_str()); out(names[2].length());
                outs(g.c_str());
                String parts = "a,b,,c"; int idx = 0; int k = 0;
                while ((idx = parts.indexOf(',', idx)) >= 0) { k++; idx++; } out(k);
                String concat; for (int i = 0; i < 5; i++) concat += String(i); outs(concat.c_str());
                String x = "abc"; x = x + x; outs(x.c_str()); x += x; outs(x.c_str());
                String y = x.substring(2); outs(y.c_str());
                String *hp = new String("heap"); outs(hp->c_str()); out(hp->length()); delete hp;
                String *arr = new String[3]; arr[2] = "two"; outs(arr[2].c_str()); delete[] arr;
                """).out("Hi Bo!", "Bo", "__7", "3", "bob", "0", "Hi glob!", "3", "01234", "abcabc", "abcabcabcabc", "cabcabcabc",
                "heap", "4", "two"));
        l.add(s("String heap is released", "", """
                long before;
                {
                  String a = "some text that is long enough";
                  String b = a + a;
                  b += b;
                }
                void *p = malloc(16);
                void *q = malloc(16);
                free(q); free(p);
                for (int i = 0; i < 200; i++) {
                  String t = String(i) + " items " + String(i * 3.5);
                  if (t.length() == 0) out(-1);
                }
                """).out().then(e -> check(((Vm) e.cpu).heap.inUse == 0, "leaked " + ((Vm) e.cpu).heap.inUse + " bytes")));

        // ---------------- heap ----------------
        l.add(s("heap", "", """
                void *p = malloc(100); out(p != 0); free(p);
                void *q = malloc(100); out(p == q); free(q);
                int *arr = new int[10]; arr[9] = 5; out(arr[9]); delete[] arr;
                int *one = new int(77); out(*one); delete one;
                int *z = new int[4](); out(z[3]); delete[] z;
                char *r = (char*)malloc(4); r[0] = 'x'; r = (char*)realloc(r, 64); out(r[0]); free(r);
                int *c = (int*)calloc(8, 4); out(c[7]); free(c);
                int count = 0; void *last = (void*)1; void *blocks[64];
                while ((last = malloc(1000)) != 0 && count < 64) blocks[count++] = last;
                out(count > 10); out(count < 25); out(last == 0);
                for (int i = 0; i < count; i++) free(blocks[i]);
                void *again = malloc(1000); out(again != 0);
                """).out("1", "1", "5", "77", "0", "120", "0", "1", "1", "1", "1"));
        l.add(s("String out of memory", "", """
                String big;
                unsigned prev = 0;
                for (int i = 0; i < 4000; i++) { big += "0123456789"; if (big.length() == prev) break; prev = big.length(); }
                out(big.length() > 5000); out(big.length() < 20000);
                String fail = big + big;
                out(fail.length()); out((bool)fail);
                out(big.reserve(30000));
                """).out("1", "1", "0", "0", "0"));

        // ---------------- faults ----------------
        l.add(t("stack overflow", """
                int deep(int n) {
                  volatile char buf[100];
                  buf[0] = n;
                  return deep(n + 1) + buf[0];
                }
                void setup() { out(deep(0)); }
                void loop() {}
                """).fault(HardFault.Kind.STACK_OVERFLOW, 0));
        l.add(t("null dereference", """
                void setup() {
                  int *p = 0;
                  out(1);
                  out(*p);
                }
                void loop() {}
                """).fault(HardFault.Kind.BUS_FAULT, 4).then(e -> check(e.out.equals(List.of("1")), "printed " + e.out)));
        l.add(t("write to flash", """
                void setup() {
                  char *s = (char*)"abc";
                  s[0] = 'x';
                }
                void loop() {}
                """).fault(HardFault.Kind.MEM_MANAGE, 3));
        l.add(t("null function pointer", """
                void (*cb)() = 0;
                void setup() {
                  cb();
                }
                void loop() {}
                """).fault(HardFault.Kind.USAGE_FAULT, 3));
        l.add(t("smashed frame", """
                int idx;
                void smash() {
                  int a[2];
                  for (idx = 0; idx < 4; idx++) a[idx] = 0x1234;
                }
                void setup() { smash(); out(1); }
                void loop() {}
                """).fault(HardFault.Kind.USAGE_FAULT, 0));
        l.add(t("abort", """
                void setup() { out(5); abort(); }
                void loop() {}
                """).fault(HardFault.Kind.ABORT, 1));

        // ---------------- link errors ----------------
        l.add(t("flash overflow", """
                const char big[140000] = {1};
                void setup() { out(big[100]); }
                void loop() {}
                """).error("region `FLASH' overflowed by"));
        l.add(t("ram overflow", """
                char big[20000];
                void setup() { big[5] = 1; out(big[5]); }
                void loop() {}
                """).error("region `RAM' overflowed by"));
        l.add(t("missing loop", "void setup() {}\n").error("undefined reference to `loop'"));
        l.add(t("undefined function", "int f(int);\nvoid setup() { f(1); }\nvoid loop() {}\n").error("undefined reference to `f(int)'"));

        // ---------------- diagnostics ----------------
        l.add(t("serial", "void setup() {\n  Serial.begin(9600);\n}\nvoid loop() {}\n").error("sketch.ino:2:3: error: 'Serial' was not declared in this scope")
                .onCompile(r -> check(r.diagnostics().stream().anyMatch(dg -> dg.severity() == Diagnostic.Severity.NOTE && dg.message().contains("serial")), "no note")));
        l.add(t("wire include", "#include <Wire.h>\nvoid setup() {}\nvoid loop() {}\n").error("Wire.h: not available on this PLC"));
        l.add(t("type mismatch", "void setup() {\n  int *p = 5;\n}\nvoid loop() {}\n").error("2:12: error: invalid conversion from 'int' to 'int*'"));
        l.add(t("void*", "void setup() {\n  int *p = malloc(4);\n}\nvoid loop() {}\n").error("invalid conversion from 'void*' to 'int*'"));
        l.add(t("arg count", "int f(int a) { return a; }\nvoid setup() {\n  f(1, 2);\n}\nvoid loop() {}\n").error("3:3: error: no matching function for call to 'f(int, int)'"));
        l.add(t("redefinition", "int x;\nint x;\nvoid setup() {}\nvoid loop() {}\n").error("redefinition of 'int x'"));
        l.add(t("missing return value", "int f() {\n  return;\n}\nvoid setup() {}\nvoid loop() {}\n").error("2:3: error: return-statement with no value"));
        l.add(t("undeclared", "void setup() {\n  foo = 1;\n}\nvoid loop() {}\n").error("2:3: error: 'foo' was not declared in this scope"));
        l.add(t("syntax", "void setup() {\n  int x = ;\n}\nvoid loop() {}\n").error("2:11: error: expected primary-expression before ';' token"));
        l.add(t("enum class conversion", "enum class E { A };\nvoid setup() {\n  int x = E::A;\n}\nvoid loop() {}\n").error("cannot convert 'E' to 'int'"));
        l.add(t("const assignment", "const int k = 1;\nvoid setup() {\n  k = 2;\n}\nvoid loop() {}\n").error("assignment of read-only variable 'k'"));
        l.add(t("error directive", "#error custom stop\nvoid setup() {}\nvoid loop() {}\n").error("1:1: error: #error custom stop"));

        // ---------------- globals and constructors ----------------
        l.add(t("global constructors", """
                int f() { return 7; }
                int g = f();
                String gs = "glob";
                int arr[3] = {1, 2, 3};
                const char *names[] = {"zero", "one"};
                int *ptr = &arr[2];
                struct S { int a; const char *n; } table[] = {{1, "a"}, {2, "b"}};
                void setup() {
                  out(g); outs(gs.c_str()); out(arr[1]); outs(names[1]); out(*ptr); outs(table[1].n); out(sizeof(table));
                  done();
                }
                void loop() {}
                """).out("7", "glob", "2", "one", "3", "b", "16"));
        l.add(t("progmem", """
                const uint8_t table[] PROGMEM = {10, 20, 30};
                const char msg[] PROGMEM = "flash";
                void setup() {
                  out(pgm_read_byte(&table[2]));
                  outs(F("lit"));
                  outs(msg);
                  done();
                }
                void loop() {}
                """).out("30", "lit", "flash"));

        // ---------------- time and interrupts ----------------
        l.add(t("delay and millis", """
                void setup() {
                  unsigned long t0 = millis();
                  delay(250);
                  unsigned long t1 = millis();
                  out(t1 - t0 >= 250);
                  out(t1 - t0 <= 251);
                  done();
                }
                void loop() {}
                """).out("1", "1"));
        // ---------------- more semantics ----------------
        l.add(s("compound assignment", "", """
                uint8_t x = 250; x += 10; out(x);
                x <<= 1; out(x); x >>= 3; out(x);
                int i = 5; i /= 2.5; out(i);
                float f = 1; f += 1; f *= 2.5f; outf(f);
                int8_t s8 = 127; s8 += 1; out(s8);
                unsigned u = 10; u -= 20; outu(u);
                int m = 7; m %= 3; out(m); m ^= 6; out(m); m |= 8; out(m); m &= 12; out(m);
                long long big = 1; big <<= 40; big |= 5; outll(big);
                char c = 'a'; c += 1; out(c); c = 'a' + 2; out(c);
                bool b = 0.5; out(b); b = 0; out(b);
                double d = 10; d -= 0.25; outf(d);
                int arr[3] = {1, 2, 3}; int *p = arr; p += 2; *p *= 10; out(arr[2]); p -= 1; out(*p);
                short sh = -1; unsigned short ush = sh; out(ush);
                int mul = 65536; mul *= 65536; out(mul);
                unsigned int um = 4000000000u; um += um; outu(um);
                """).out("4", "8", "1", "2", "5.0", "-128", "4294967286", "1", "7", "15", "12", "1099511627781", "98", "99", "1", "0",
                "9.75", "30", "2", "65535", "0", "3705032704"));
        l.add(s("struct members and unions", """
                struct Node { int value; Node *next; };
                struct Ctr { int count; void (*cb)(int); };
                int seen;
                void hook(int v) { seen = v; }
                typedef struct { uint8_t r, g, b; } Rgb;
                union Conv { float f; uint32_t u; uint8_t b[4]; };
                struct Outer { struct Inner { int a, b; } in; int c; };
                """, """
                Node n3 = {3, nullptr}, n2 = {2, &n3}, n1 = {1, &n2};
                int sum = 0; for (Node *p = &n1; p; p = p->next) sum += p->value; out(sum);
                Ctr c = {0, hook}; c.cb(42); out(seen); c.count++; c.count += 2; out(c.count);
                Ctr *pc = &c; pc->count--; out(pc->count); (*pc).cb(7); out(seen);
                Rgb col = {255, 128, 1}; out(sizeof(Rgb)); out(col.g);
                Conv cv; cv.f = 1.0f; outx(cv.u); out(cv.b[3]);
                Outer o = {{1, 2}, 3}; out(o.in.b + o.c); Inner in2 = {5, 6}; out(in2.a);
                Node *heap = new Node{9, nullptr}; out(heap->value); delete heap;
                Rgb *rs = new Rgb[2](); out(rs[1].b); delete[] rs;
                """).out("6", "42", "3", "2", "7", "3", "128", "3f800000", "63", "5", "5", "9", "0"));
        l.add(s("struct with String", """
                struct Item { String name; int qty; String note; };
                Item make(const char *n, int q) { Item it; it.name = n; it.qty = q; return it; }
                """, """
                Item a = {"apple", 3};
                outs(a.name.c_str()); out(a.qty); out(a.note.length()); out((bool)a.note);
                Item b = a; b.name += "s"; outs(a.name.c_str()); outs(b.name.c_str());
                Item c; c = b; c.qty = 9; outs(c.name.c_str()); out(b.qty);
                Item d = make("pear", 2); outs(d.name.c_str()); out(d.qty);
                Item list[2] = {{"x", 1}, {"y", 2}}; outs(list[1].name.c_str());
                String arr[4]; arr[3] = "last"; out(arr[0].length()); outs(arr[3].c_str());
                """).out("apple", "3", "0", "1", "apple", "apples", "apples", "3", "pear", "2", "y", "0", "last"));
        l.add(s("String lifetimes", """
                bool flag(int i) { return i & 1; }
                String pick(bool b) { return b ? String("odd") : String("even"); }
                int lenOf(String s) { return s.length(); }
                """, """
                void *probe = malloc(8); free(probe);
                for (int i = 0; i < 50; i++) {
                  String a = String("item") + i;
                  if (i == 10) continue;
                  String b = flag(i) ? a : String("even");
                  if (lenOf(pick(flag(i))) > 100) out(-1);
                  if (i == 40) break;
                }
                int k = 0;
                while (k < 5) { String t = "loop"; k++; if (k == 3) { String u = t + t; continue; } }
                {
                  String g1 = "goto";
                  if (k == 5) goto out_of_block;
                  String g2 = "never";
                }
                out_of_block:
                out(pick(true) == "odd"); out(pick(false).length());
                {
                  const String &ref = String("bound") + "!"; outs(ref.c_str());
                }
                """).out("1", "4", "bound!").then(e -> check(((Vm) e.cpu).heap.inUse == 0, "leaked " + ((Vm) e.cpu).heap.inUse + " bytes")));
        l.add(s("String conversions and compare", """
                void takes(const String &s) { outs(s.c_str()); }
                void takesC(const char *s) { outs(s); }
                """, """
                takes("literal"); takes(String(12)); String s = "obj"; takes(s); takesC(s.c_str());
                String a = "10", b = "9"; out(a < b); out(a.toInt() < b.toInt());
                out(String("abc") == String("abc")); out(String("a") != "b");
                String e = ""; out(e == ""); out(e.equals("")); out(e.length());
                String num = "-12.5e1x"; outf(num.toDouble()); out(String("  -42xyz").toInt()); out(String("99999999999").toInt());
                String z = String(0.0f, 3); outs(z.c_str()); String neg = String(-0.5, 1); outs(neg.c_str());
                String big = String(123456.789, 2); outs(big.c_str()); String bigf = String(1e10f, 1); outs(bigf.c_str());
                String h = String(254, 2); outs(h.c_str()); String o = String(8, 8); outs(o.c_str());
                String up = "MiXeD"; up.toLowerCase(); outs(up.c_str());
                String gb = "hello"; unsigned char bytes[4]; gb.getBytes(bytes, 4, 1); outs((char*)bytes);
                out(gb.lastIndexOf("l")); out(gb.lastIndexOf('l', 2)); out(gb.indexOf('l', 3)); out(gb.startsWith("ll", 2));
                gb.remove(1, 2); outs(gb.c_str()); gb.replace('o', '0'); outs(gb.c_str());
                String cat = "a"; out(cat.concat(5)); out(cat.concat(2.5f)); outs(cat.c_str());
                """).out("literal", "12", "obj", "obj", "1", "0", "1", "1", "1", "1", "0", "-125.0", "-42", "2147483647", "0.000", "-0.5",
                "123456.79", "-1.2147483647", "11111110", "10", "mixed", "ell", "3", "2", "3", "1", "hlo", "hl0", "1", "1", "a52.50"));
        l.add(s("misc expressions", """
                int g = 5;
                int shadow() { int g = 7; return g; }
                static int helper(int v) { return v * 2; }
                extern int later_global;
                int later_global = 33;
                const char *const names[] = {"a", "bb", "ccc"};
                char grid[3][4] = {"ab", "cd", "ef"};
                int counter = 0;
                int next() { return ++counter; }
                """, """
                out(shadow()); out(g); out(helper(4)); out(later_global);
                out(sizeof(names) / sizeof(names[0])); outs(names[2]); outs(grid[1]); out(sizeof(grid));
                int i = 0, j = 10; for (; i < j; i++, j--) ; out(i);
                int vals[3] = {next(), next(), next()}; out(vals[0] * 100 + vals[1] * 10 + vals[2]);
                int x = 5; x = x++ + 1; out(x);
                out(sizeof(vals) / sizeof(*vals));
                char c = 'x'; switch (c) { default: out(0); break; case 'x': out(1); case 'y': out(2); }
                int dw = 0; do { dw++; if (dw < 3) continue; } while (dw < 5); out(dw);
                long long acc = 0; for (long long q = 0; q < 100000LL; q += 25000) acc += q; outll(acc);
                unsigned long long uu = 1ULL << 63; out(uu > 0); out((long long)uu < 0);
                out(10 - 3 - 2); out(2 * 3 + 4 % 3); out(1 + 2 << 1); out((5 & 3) == 1); out(-5 / 2 * 2);
                int neg = -7; out(neg >> 1); out((unsigned)neg >> 28);
                out(!!42); out(~0); out(-(-2147483647 - 1) == (-2147483647 - 1));
                """).out("7", "5", "8", "33", "3", "ccc", "cd", "12", "5", "123", "6", "3", "1", "2", "5", "150000", "1", "1",
                "5", "7", "6", "1", "-4", "-4", "15", "1", "-1", "1"));
        l.add(s("many arguments", """
                long long mix(int a, long long b, float c, double d, char e, int f, short g, unsigned h) {
                  return a + b + (long long)c + (long long)d + e + f + g + h;
                }
                double avg(double a, double b, double c, double d, double e) { return (a + b + c + d + e) / 5; }
                """, """
                outll(mix(1, 10000000000LL, 2.5f, 3.9, 'a', -1, -2, 3000000000u));
                outf(avg(1, 2, 3, 4, 5.5));
                """).out("13000000100", "3.1"));
        l.add(s("ternary and logic", "", """
                int a = 3, b = 4;
                int &m = a > b ? a : b; m = 40; out(b);
                const char *s = a ? "yes" : "no"; outs(s);
                int calls = 0;
                auto bump = [](int &c) { c++; return true; };
                bool r = false && bump(calls); out(calls); r = true || bump(calls); out(calls); r = true && bump(calls); out(calls);
                out(a < b ? 1.5 : 2); out(sizeof(a < b ? 'a' : 1));
                void *vp = nullptr; out(vp ? 1 : 0); int *ip = a ? &a : nullptr; out(*ip);
                """).out("40", "yes", "0", "0", "1", "1", "4", "0", "3"));
        l.add(s("integer edge cases", "", """
                volatile int32_t mn = INT32_MIN; out(mn); out(-mn); out(mn - 1);
                volatile uint8_t u8 = 0; u8--; out(u8);
                volatile int8_t i8 = -128; i8--; out(i8);
                out((uint16_t)-1); out((int16_t)40000); out((uint8_t)3.99); out((int)(uint8_t)-1.5f);
                out(UINT32_MAX == 4294967295UL); out(sizeof(UINT32_MAX)); out(INT_MIN < 0);
                volatile long long l1 = 0x7FFFFFFFFFFFFFFFLL; l1++; outll(l1);
                volatile unsigned long long q = 10; outull(q - 11);
                out(5u > -1); out(5 > -1);
                int64_t pr = (int64_t)100000 * 100000; outll(pr); int32_t pr32 = 100000 * 100000; out(pr32);
                """).out("-2147483648", "-2147483648", "2147483647", "255", "127", "65535", "-25536", "3", "0", "1", "4", "1",
                "-9223372036854775808", "18446744073709551615", "0", "1", "10000000000", "1410065408"));
        // ---------------- batch 3 ----------------
        l.add(s("preprocessor corners", """
                #include <Arduino.h>
                #include <stdint.h>
                #include "math.h"
                #pragma once
                #warning this is a warning
                #define EMPTY()
                #define LOG(fmt, ...) logv(fmt, ## __VA_ARGS__)
                #define SELF SELF
                #define TWICE(x) ((x) * 2)
                #define APPLY(f, v) f(v)
                #define GLUE3(a, b, c) a ## b ## c
                #define MULTI_LINE(a, \\
                                   b) ((a) + \\
                                       (b))
                #if (3 * 4 == 12) && !defined(NOPE) || 0
                #define OK_A 1
                #endif
                #if 0
                  this is not C at all ((((
                #elif 1
                #define OK_B 2
                #endif
                #ifdef __arm__
                #define ARM 1
                #endif
                int logv(int f) { return f; }
                int logv(int f, int a) { return f + a; }
                int SELF = 1;
                """, """
                out(LOG(5)); out(LOG(5, 6)); EMPTY() out(SELF + 1); out(APPLY(TWICE, 21)); out(GLUE3(1, 2, 3));
                out(MULTI_LINE(2, 3)); out(OK_A + OK_B); out(ARM); out(sizeof(__CHAR_UNSIGNED__));
                outs("con" "cat" "enated");
                #line 500
                out(__LINE__);
                """).out("5", "11", "2", "42", "123", "5", "3", "1", "4", "concatenated", "500"));
        l.add(s("static locals", """
                int calls = 0;
                int next() { return ++calls; }
                int once() { static int v = next() * 10; return v; }
                const char *label() { static String s = String("L") + next(); return s.c_str(); }
                inline int twice(int x) { return 2 * x; }
                int sum(int n) { static int total; total += n; return total; }
                """, """
                out(once()); out(once()); out(calls);
                outs(label()); outs(label()); out(calls);
                out(twice(21)); sum(5); out(sum(6));
                """).out("10", "10", "1", "L2", "L2", "2", "42", "11"));
        l.add(t("stack overflow line", """
                int deep(int n) {
                  volatile char buf[100];
                  buf[0] = n;
                  return deep(n + 1) + buf[0];
                }
                void setup() { out(deep(0)); }
                void loop() {}
                """).fault(com.nectariich.create_flippies.content.plc.mcu.HardFault.Kind.STACK_OVERFLOW, 4));
        l.add(t("return type warning", "int f(int x) {\n  if (x) return 1;\n}\nvoid setup() { f(1); }\nvoid loop() {}\n")
                .error("sketch.ino:3:1: warning: control reaches end of non-void function [-Wreturn-type]"));
        l.add(t("no such String member", "void setup() {\n  String s;\n  s.println();\n}\nvoid loop() {}\n").error("'class String' has no member named 'println'"));
        l.add(t("dot on pointer", "struct P { int x; };\nvoid setup() {\n  P p; P *pp = &p;\n  pp.x = 1;\n}\nvoid loop() {}\n").error("maybe you meant to use '->'"));
        l.add(t("array designators", "int a[3] = {[1] = 2};\nvoid setup() {}\nvoid loop() {}\n").error("array designators"));
        l.add(t("class member function", "class C {\n public:\n  int get() { return 1; }\n};\nvoid setup() {}\nvoid loop() {}\n").error("member functions are not supported"));
        l.add(t("template", "template <typename T> T id(T v) { return v; }\nvoid setup() {}\nvoid loop() {}\n").error("templates are not supported"));
        l.add(t("too many initializers", "int a[2] = {1, 2, 3};\nvoid setup() {}\nvoid loop() {}\n").error("too many initializers for 'int [2]'"));
        l.add(t("string too long", "char s[3] = \"abc\";\nvoid setup() {}\nvoid loop() {}\n").error("initializer-string for 'char [3]' is too long"));
        l.add(t("ambiguous overload", "void f(long a) {}\nvoid f(unsigned a) {}\nvoid setup() {\n  f(1);\n}\nvoid loop() {}\n").error("call of overloaded 'f(int)' is ambiguous"));
        l.add(t("explicit String ctor", "void setup() {\n  String s = 5;\n}\nvoid loop() {}\n").error("conversion from 'int' to non-scalar type 'String' requested"));
        l.add(t("break outside loop", "void setup() {\n  break;\n}\nvoid loop() {}\n").error("break statement not within loop or switch"));
        l.add(t("duplicate case", "void setup() {\n  switch (1) { case 1: case 1: break; }\n}\nvoid loop() {}\n").error("duplicate case value"));
        l.add(t("unknown type", "Foo x;\nvoid setup() {}\nvoid loop() {}\n").error("sketch.ino:1:1: error: 'Foo' does not name a type"));
        l.add(t("missing semicolon", "void setup() {\n  int x = 1\n  x++;\n}\nvoid loop() {}\n").error("expected ';' before 'x' token"));
        l.add(t("unknown header", "#include <Servo.h>\nvoid setup() {}\nvoid loop() {}\n").error("Servo.h: No such file or directory"));
        l.add(t("sizes reported", """
                char buf[100];
                const int table[50] = {1};
                void setup() { buf[0] = table[1]; done(); }
                void loop() {}
                """).onCompile(r -> {
                    check(r.program().staticRamBytes() >= LIB.coreRam + 100 && r.program().staticRamBytes() < LIB.coreRam + 140,
                            "static RAM " + r.program().staticRamBytes());
                    check(r.program().flashBytes() > LIB.coreFlash + 200 && r.program().flashBytes() < LIB.coreFlash + 600,
                            "flash " + r.program().flashBytes());
                }).out());
        l.add(t("unused globals and functions are dropped", """
                char unusedBuf[4000];
                int unusedFn(int a) { return a * 3 + 7; }
                void setup() { done(); }
                void loop() {}
                """).onCompile(r -> check(r.program().staticRamBytes() < LIB.coreRam + 40, "static RAM " + r.program().staticRamBytes())).out());
        l.add(s("volatile and registers", """
                volatile int ticks;
                """, """
                for (int i = 0; i < 10; i++) ticks++;
                out(ticks);
                volatile int local = 0;
                for (int i = 0; i < 5; i++) local += i;
                out(local);
                """).out("10", "10"));
        l.add(s("function pointer to builtin", """
                typedef void (*PrintFn)(long);
                void apply(PrintFn f, long v) { f(v); }
                """, """
                apply(out, 77);
                PrintFn p = &out; p(78);
                long (*m)(long, long, long, long, long) = map; out(m(5, 0, 10, 0, 100));
                """).out("77", "78", "50"));
        l.add(s("promotion of bit-fields and enums", """
                struct F { unsigned a : 3; unsigned long b : 31; unsigned c : 32; };
                enum E { E0, E1 };
                enum class Big : unsigned { X = 1 };
                enum Fixed : unsigned char { FA = 200 };
                """, """
                F f; f.a = 1; f.b = 1; f.c = 1;
                out(f.a - 2 < 0); out(f.b - 2 < 0); out(f.c - 2 < 0);
                out(E0 - 1 < 0); out(sizeof(E0 + 0)); out(FA + 100);
                Fixed fx = FA; out(fx - 201 < 0);
                """).out("1", "1", "0", "1", "4", "300", "1"));
        l.add(t("64-bit bit-field", "struct S { long long x : 40; };\nvoid setup() { S s; s.x = -2; outll(s.x); done(); }\nvoid loop() {}\n").out("-2"));
        l.add(t("deep expression recursion", """
                int a = 1, b = 2;
                int g() { return a + g() / b; } // a + b * g() would be a loop, as GCC's tail recursion makes it
                void setup() { out(g()); }
                void loop() {}
                """).fault(com.nectariich.create_flippies.content.plc.mcu.HardFault.Kind.STACK_OVERFLOW, 0));
        l.add(s("recursion that fits", """
                int depth(int n) { return n == 0 ? 0 : 1 + depth(n - 1); }
                """, """
                out(depth(500));
                """).out("500"));
        LangRegressions.add(l);
        LangRegressions2.add(l);
        return l;
    }
}
