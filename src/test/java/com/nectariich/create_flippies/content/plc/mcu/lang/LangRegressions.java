package com.nectariich.create_flippies.content.plc.mcu.lang;

import static com.nectariich.create_flippies.content.plc.mcu.lang.LangTests.check;
import static com.nectariich.create_flippies.content.plc.mcu.lang.LangTests.s;
import static com.nectariich.create_flippies.content.plc.mcu.lang.LangTests.t;

import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;

/**
 * Regression cases for the review findings against the language (round 1): each
 * one pins what arm-none-eabi-g++ -std=gnu++17 does for STM32duino, or what the
 * compiler must do instead of hanging or running out of memory. They run as part
 * of {@link LangTests} (and so under JUnit through {@link SketchCompilerTest}).
 */
final class LangRegressions {

    static void add(List<LangTests.Case> l) {
        callsAndOverloads(l);
        stringApi(l);
        layout(l);
        conformance(l);
        jumps(l);
        arithmetic(l);
        heapAndLimits(l);
    }

    private static boolean hasWarning(com.nectariich.create_flippies.content.plc.mcu.CompileResult r, String text) {
        for (Diagnostic d : r.diagnostics())
            if (d.severity() == Diagnostic.Severity.WARNING && d.toString().contains(text))
                return true;
        return false;
    }

    // ---------------- calls, overloads, conversions ----------------

    private static void callsAndOverloads(List<LangTests.Case> l) {
        // A builtin call with more arguments than the invocation's first array held.
        l.add(s("builtin with 20 variadic arguments", "", """
                out(sumv(20, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20));
                out(sumv(3, 1, 2, 3));
                out(sumv(40, 1,1,1,1,1,1,1,1,1,1, 1,1,1,1,1,1,1,1,1,1, 1,1,1,1,1,1,1,1,1,1, 1,1,1,1,1,1,1,1,1,1));
                """).out("210", "6", "40"));
        // STM32duino's yield() is weak: a sketch may define its own, and every call reaches it.
        l.add(s("sketch replaces the weak yield()", """
                int n;
                void yield() { n++; }
                void twice() { yield(); yield(); }
                """, """
                yield(); twice();
                out(n);
                """).out("3"));
        l.add(s("weak builtin by attribute", "int hook() { return 5; }", "out(hook());").out("5"));
        l.add(s("weak builtin kept when not replaced", "", "out(hook());").out("1"));
        l.add(t("non-weak builtin still cannot be redefined", "long sumv(int n, ...) { return 0; }\nvoid setup() {}\nvoid loop() {}\n")
                .error("redefinition of 'sumv'"));
        // [over.over]: an overload set converted to a function pointer picks by type.
        l.add(s("overloaded function as a function pointer", """
                int add(int a, int b = 10) { return a + b; }
                float add(float a, float b) { return a * b; }
                int apply(int (*fn)(int, int), int v) { return fn(v, 3); }
                int g(int) { return 7; }
                int g(float) { return 8; }
                int (*gp)(int) = g;
                """, """
                out(apply(add, 5));
                int (*p)(float) = g; out(p(1));
                int (*q)(int); q = &g; out(q(1));
                out(gp(2));
                """).out("8", "8", "7", "7"));
        l.add(t("overload set with no matching type", "int g(int) { return 7; }\nint g(float) { return 8; }\nvoid setup() { long (*p)(int) = g; }\nvoid loop() {}\n")
                .error("no matches converting function 'g' to type 'long int (*)(int)'"));
        // [over.ics.rank]/3.2.6: the less cv-qualified reference binding wins.
        l.add(s("int& beats const int& for a non-const lvalue", """
                int f(int& x) { return 1; }
                int f(const int& x) { return 2; }
                int h(char* p) { return 1; }
                int h(const char* p) { return 2; }
                """, """
                int a = 1; const int b = 2; char buf[2] = "x";
                out(f(a)); out(f(b)); out(f(3));
                out(h(buf)); out(h("lit"));
                """).out("1", "2", "2", "1", "2"));
        l.add(s("reference-to-array parameter", """
                int sum(int (&a)[3]) { return a[0] + a[1] + a[2]; }
                void fill(int (&a)[4]) { for (int i = 0; i < 4; i++) a[i] = i * i; }
                int count(const int (&a)[2]) { return sizeof(a); }
                """, """
                int a[3] = {1, 2, 3}; out(sum(a));
                int b[4]; fill(b); out(b[3]);
                int c[2] = {0}; out(count(c));
                """).out("6", "9", "8"));
        l.add(t("reference-to-array needs the right size", "int sum(int (&a)[3]) { return 0; }\nvoid setup() { int a[4]; sum(a); }\nvoid loop() {}\n")
                .error("no matching function for call to 'sum(int [4])'"));
        // A braced list as an argument list-initialises the parameter.
        l.add(s("braced-init-list as a function argument", """
                struct P { int x, y, z; };
                int sx(P q) { return q.x + q.y * 10 + q.z * 100; }
                int cref(const P& q) { return q.y; }
                int scalar(long v) { return v; }
                int str(const String& s) { return s.length(); }
                """, """
                out(sx({1, 2}));
                out(cref({4, 5, 6}));
                out(scalar({7}));
                out(str({"abcd"}));
                """).out("21", "5", "7", "4"));
    }

    // ---------------- String: ArduinoCore-API members ----------------

    private static void stringApi(List<LangTests.Case> l) {
        l.add(s("String isEmpty, length constructors and concat", "", """
                String e; String s = "abc";
                out(e.isEmpty()); out(s.isEmpty());
                String t("abcdef", 3); outs(t.c_str()); out(t.length());
                const uint8_t raw[] = { 'x', 'y', 'z', 'w' };
                String u(raw, 2); outs(u.c_str());
                t.concat("xyzw", 2); outs(t.c_str());
                u.concat(raw + 2, 2); outs(u.c_str()); out(u.length());
                out(t.concat((const char*)0, 3));
                """).out("1", "0", "abc", "3", "xy", "abcxy", "xyzw", "4", "0"));
        l.add(s("String length constructor copies embedded NULs", "", """
                const char data[] = { 'a', 0, 'b' };
                String s(data, 3);
                out(s.length()); out(s[2]); out(s.c_str()[1]);
                """).out("3", "98", "0"));
        l.add(s("range-for over a String", "", """
                String s = "abc";
                int n = 0;
                for (char c : s) n += c;
                out(n);
                for (char& c : s) c = c - 32;
                outs(s.c_str());
                out(s.end() - s.begin());
                const String k = "hi";
                int m = 0;
                for (char c : k) m++;
                out(m);
                """).out("294", "ABC", "3", "2"));
        l.add(s("range-for over a String temporary", """
                String mk() { return String("xyz"); }
                int cnt(const String& s) { int n = 0; for (char c : s) n++; return n; }
                """, """
                int n = 0;
                for (char c : mk()) n += c;
                out(n);
                for (char c : String("ab")) n++;
                out(n); out(cnt("hello"));
                """).out("363", "365", "5"));
    }

    // ---------------- layout: enums, packed, aligned, anonymous members ----------------

    private static void layout(List<LangTests.Case> l) {
        // arm-none-eabi: AAPCS short enums (ARM_DEFAULT_SHORT_ENUMS).
        l.add(s("unscoped enums are short", """
                enum Color { RED, GREEN = 5, BLUE };
                enum { NA = -1, NB = 1 };
                enum Wide { W0, W1 = 300 };
                enum Neg { M0 = -200 };
                enum Big { B0 = 70000 };
                enum Huge { H0 = 0x100000000LL };
                enum class Scoped { S0 };
                enum Fixed : uint32_t { F0 };
                struct SE { Color c; uint8_t b; };
                """, """
                out(sizeof(Color)); out(sizeof(NA)); out(sizeof(Wide)); out(sizeof(Neg)); out(sizeof(Big)); out(sizeof(Huge));
                out(sizeof(Scoped)); out(sizeof(Fixed)); out(sizeof(SE)); out(alignof(Color));
                out(RED - 1 < 0); out(sizeof(RED + 0));
                Color c = BLUE; out(c); Neg n = M0; out(n);
                """).out("1", "1", "2", "2", "4", "8", "4", "4", "2", "1", "1", "4", "6", "-200"));
        l.add(s("packed, aligned and #pragma pack", """
                struct __attribute__((packed)) P { uint8_t a; uint32_t b; uint16_t c; };
                struct Q { uint8_t a; uint32_t b; uint16_t c; } __attribute__((packed));
                struct A { int x; } __attribute__((aligned(16)));
                #pragma pack(push, 1)
                struct R { uint8_t a; uint32_t b; };
                #pragma pack(push, 2)
                struct R2 { uint8_t a; uint32_t b; };
                #pragma pack(pop)
                struct R1 { uint8_t a; uint16_t b; };
                #pragma pack(pop)
                struct N { uint8_t a; uint32_t b; };
                struct alignas(8) B { int x; };
                struct F { uint8_t a; uint32_t b __attribute__((packed)); uint8_t c; };
                struct G { char c; alignas(8) int x; };
                struct H { char c; int x __attribute__((aligned(8))); };
                struct __attribute__((packed)) BF { uint8_t a : 3; uint16_t b : 10; uint8_t c; };
                union __attribute__((packed)) U { uint8_t bytes[5]; struct __attribute__((packed)) { uint8_t tag; uint32_t value; } f; };
                """, """
                out(sizeof(P)); out(offsetof(P, b)); out(offsetof(P, c)); out(alignof(P));
                out(sizeof(Q)); out(sizeof(A)); out(alignof(A)); out(sizeof(R)); out(sizeof(R2)); out(sizeof(R1)); out(sizeof(N));
                out(sizeof(B)); out(sizeof(F)); out(offsetof(F, c)); out(sizeof(G)); out(offsetof(H, x)); out(sizeof(BF));
                U u; u.bytes[0] = 7; u.bytes[1] = 0x78; u.bytes[2] = 0x56; u.bytes[3] = 0x34; u.bytes[4] = 0x12;
                out(u.f.tag); outx(u.f.value); out(sizeof(U));
                u.f.value = 0xA1B2C3D4; outx(u.bytes[1]); outx(u.bytes[4]);
                BF bf; bf.a = 5; bf.b = 1000; bf.c = 9; out(bf.a); out(bf.b); out(bf.c);
                """).out("7", "1", "5", "1", "7", "16", "16", "5", "6", "3", "8", "8", "6", "5", "16", "8", "3",
                        "7", "12345678", "5", "d4", "a1", "5", "1000", "9"));
        l.add(t("pragma pack pop without push", "#pragma pack(pop)\nvoid setup() {}\nvoid loop() {}\n")
                .error("#pragma pack (pop) encountered without matching #pragma pack (push)"));
        l.add(t("bad alignment", "struct S { int x; } __attribute__((aligned(3)));\nvoid setup() {}\nvoid loop() {}\n")
                .error("requested alignment '3' is not a positive power of 2"));
        l.add(s("attribute spellings", """
                [[maybe_unused]] static int gx = 3;
                struct [[gnu::packed]] P { uint8_t a; uint32_t b; };
                struct __attribute__((__packed__, aligned(2))) Q { uint8_t a; uint32_t b; };
                """, """
                [[maybe_unused]] int x = 1; char pad = 0; alignas(8) char buf[8]; __attribute__((unused)) int y = 2;
                out(sizeof(P)); out(sizeof(Q)); out(((unsigned long)buf & 7) == 0); out(gx + x + y + pad);
                """).out("5", "6", "1", "6"));
        l.add(s("aligned global variable","char pad; int x __attribute__((aligned(16))) = 1;", "out(((unsigned long)&x & 15) == 0); out(x);").out("1", "1"));
        l.add(s("anonymous unions and structs", """
                struct V { int tag; union { float f; uint32_t u; }; };
                union R { struct { uint8_t lo, hi; }; uint16_t w; };
                struct Deep { union { struct { uint8_t a, b; }; uint16_t ab; }; uint8_t c; };
                V gv = { 1, { 2.0f } };
                """, """
                V v; v.f = 1.0f; out(v.u == 0x3f800000); out(sizeof(V));
                R r; r.w = 0x1234; out(r.lo); out(r.hi); out(sizeof(R));
                Deep d; d.ab = 0x0201; d.c = 3; out(d.a); out(d.b); out(sizeof(Deep)); out(offsetof(Deep, c));
                out(gv.tag); out(gv.u == 0x40000000);
                V* pv = &v; pv->u = 0; out(pv->f == 0.0f);
                """).out("1", "8", "52", "18", "2", "1", "2", "4", "2", "1", "1", "1"));
        l.add(t("anonymous member name clash", "struct S { int a; union { int a; float f; }; };\nvoid setup() {}\nvoid loop() {}\n")
                .error("redeclaration of 'a'"));
        l.add(s("64-bit bit-fields", """
                struct S { uint64_t a : 40; uint64_t b : 24; int64_t c : 36; uint64_t d : 20; };
                S g = { 0x123456789ULL, 5, -3, 1 };
                """, """
                S s; s.a = 0xFFFFFFFFFFULL; s.b = 0x123456; s.c = -5; s.d = 7;
                outull(s.a); out(s.b); outll(s.c); out(s.d); out(sizeof(S));
                s.a++; outull(s.a); s.c += 10; outll(s.c);
                outull(g.a); outll(g.c);
                out(s.d - 8 < 0);
                """).out("1099511627775", "1193046", "-5", "7", "16", "0", "5", "4886718345", "-3", "1"));
    }

    // ---------------- syntax and semantics GCC accepts or rejects ----------------

    private static void conformance(List<LangTests.Case> l) {
        // A string literal converts to char* with a warning (-Wwrite-strings), as g++ does.
        l.add(s("string literal to char*", """
                void show(char* s) { outs(s); }
                char* names[] = { "a", "b" };
                struct Item { char* label; int v; };
                Item items[] = { { "one", 1 } };
                """, """
                char* p = "abc"; outs(p);
                show("hi");
                outs(names[1]); outs(items[0].label);
                """).out("abc", "hi", "b", "one").onCompile(r -> check(hasWarning(r,
                "ISO C++ forbids converting a string constant to 'char*' [-Wwrite-strings]"), "warning: " + r.diagnostics())));
        l.add(s("writing through a literal's char* faults", "", "char* p = \"abc\";\np[0] = 'x';\n")
                .fault(HardFault.Kind.MEM_MANAGE, 4));
        l.add(t("a const char* variable is not a literal", "const char* c = \"x\";\nvoid setup() { char* p = c; }\nvoid loop() {}\n")
                .error("invalid conversion from 'const char*' to 'char*'"));
        l.add(t("unsigned char* from a literal is still an error", "void setup() { unsigned char* p = \"x\"; }\nvoid loop() {}\n")
                .error("invalid conversion"));
        l.add(s("__func__", "const char* who() { return __func__; }", """
                outs(__func__); outs(who()); out(sizeof(__func__));
                outs(__PRETTY_FUNCTION__);
                auto f = []() { return __func__; }; outs(f());
                """).out("setup", "who", "6", "void setup()", "operator()"));
        l.add(s("raw string literals", "", """
                const char* r = R"(a\\nb"c)"; outs(r);
                const char* d = R"xy(close )" here)xy"; outs(d);
                out(sizeof(R"(ab)"));
                const char* m = R"(line1
                line2)"; out(strlen(m)); out(m[5] == 10);
                """).out("a\\nb\"c", "close )\" here", "3", "11", "1"));
        l.add(s("designated initialisers", """
                struct P3 { int x, y, z; };
                struct D { int a = 4; int b; String s; };
                union U { int i; float f; };
                P3 p = { .x = 2, .z = 7 };
                """, """
                out(p.x); out(p.y); out(p.z);
                P3 q = { .y = 5 }; out(q.x); out(q.y); out(q.z);
                D d = { .b = 3, .s = "hi" }; out(d.a); out(d.b); outs(d.s.c_str());
                U u = { .f = 1.0f }; out(u.i == 0x3f800000);
                P3 arr[] = { { .z = 1 }, { .x = 9 } }; out(arr[0].z); out(arr[1].x);
                """).out("2", "0", "7", "0", "5", "0", "4", "3", "hi", "1", "1", "9"));
        l.add(t("designators out of order", "struct P { int x, y; };\nP p = { .y = 1, .x = 2 };\nvoid setup() {}\nvoid loop() {}\n")
                .error("designator order for field 'P::x' does not match declaration order in 'P'"));
        l.add(t("designated and positional mixed", "struct P { int x, y; };\nP p = { .x = 1, 2 };\nvoid setup() {}\nvoid loop() {}\n")
                .error("either all initializer clauses should be designated or none of them should be"));
        l.add(t("designator naming no member", "struct P { int x; };\nP p = { .q = 1 };\nvoid setup() {}\nvoid loop() {}\n")
                .error("'P' has no non-static data member named 'q'"));
        l.add(s("constexpr function in a constant expression", """
                constexpr int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                constexpr int pick(int v) { if (v > 3) return 1; else if (v > 1) return 2; return 3; }
                int arr[fact(3)];
                const int N = fact(4);
                int other[N];
                enum { E = fact(5) };
                static_assert(pick(9) == 1 && pick(2) == 2 && pick(0) == 3, "pick");
                """, """
                out(sizeof(arr)); out(sizeof(other)); out(E);
                volatile int v = 6; out(fact(v));
                switch (v) { case fact(3): out(1); break; default: out(0); }
                """).out("24", "96", "120", "720", "1"));
        l.add(t("a non-constant argument is not a constant expression", "constexpr int id(int n) { return n; }\nint v = 3;\nint arr[id(v)];\nvoid setup() {}\nvoid loop() {}\n")
                .error("array bound is not an integer constant"));
        l.add(s("sizeof a member through its class", "struct S { int x; char name[10]; double d; };", """
                out(sizeof(S::x)); out(sizeof(S::name)); out(sizeof S::d);
                """).out("4", "10", "8"));
        l.add(t("a member through its class outside sizeof", "struct S { int x; };\nvoid setup() { int y = S::x; }\nvoid loop() {}\n")
                .error("invalid use of non-static data member 'S::x'"));
        l.add(s("__VA_OPT__ and __has_include", """
                #define LOG(fmt, ...) sumv(1 __VA_OPT__(,) __VA_ARGS__)
                #define CNT(...) (0 __VA_OPT__(+ 1))
                #if defined(__has_include)
                #if __has_include(<Arduino.h>) && !__has_include(<Wire.h>) && !__has_include("nothere.h")
                int hi = 1;
                #endif
                #endif
                #ifdef __has_include
                int hd = 2;
                #endif
                """, """
                out(LOG(0)); out(LOG(0, 5)); out(CNT()); out(CNT(x)); out(hi); out(hd);
                """).out("0", "5", "0", "1", "1", "2"));
        // C++11 list-initialisation forbids narrowing: an error for a constant, a warning otherwise.
        l.add(t("narrowing constant in braces", "uint8_t a[] = { 300 };\nvoid setup() {}\nvoid loop() {}\n")
                .error("sketch.ino:1:17: error: narrowing conversion of '300' from 'int' to 'unsigned char' [-Wnarrowing]"));
        l.add(t("narrowing negative constant to unsigned", "unsigned u[] = { -1 };\nvoid setup() {}\nvoid loop() {}\n")
                .error("narrowing conversion of '-1' from 'int' to 'unsigned int'"));
        l.add(s("narrowing non-constant is a warning", "int x = 300;", "uint8_t a[] = { x }; out(a[0]); uint8_t ok[] = { 200, 'c' }; out(ok[0]);")
                .out("44", "200").onCompile(r -> check(hasWarning(r, "narrowing conversion of 'x' from 'int' to 'uint8_t'")
                        || hasWarning(r, "narrowing conversion of 'x' from 'int' to 'unsigned char'"), "warning: " + r.diagnostics())));
        l.add(t("postfix ++ on an enum", "enum E { A, B };\nvoid setup() { E e = A; e++; }\nvoid loop() {}\n")
                .error("no 'operator++(int)' declared for postfix '++' [-fpermissive]"));
        l.add(t("prefix -- on an enum", "enum E { A, B };\nvoid setup() { E e = B; --e; }\nvoid loop() {}\n")
                .error("no match for 'operator--' (operand type is 'E')"));
        l.add(t("control reaches end", "int f(int x) {\n  if (x) return 1;\n  }\nvoid setup() { f(1); }\nvoid loop() {}\n")
                .error("sketch.ino:3:3: warning: control reaches end of non-void function [-Wreturn-type]"));
        l.add(t("no return at all", "int f(int x) {\n  x++;\n}\nvoid setup() { f(1); }\nvoid loop() {}\n")
                .error("sketch.ino:3:1: warning: no return statement in function returning non-void [-Wreturn-type]"));
    }

    // ---------------- jumping past initialisation ----------------

    private static void jumps(List<LangTests.Case> l) {
        l.add(t("case label crosses an initialised int", "int r; volatile int x = 1;\nvoid setup() {\n  switch (x) {\n    case 1: int y = 5; r = y; break;\n    case 2: r = 2; break;\n  }\n}\nvoid loop() {}\n")
                .error("sketch.ino:5:5: error: jump to case label"));
        l.add(t("case label crossing note", "int r; volatile int x = 1;\nvoid setup() {\n  switch (x) {\n    case 1: int y = 5; r = y; break;\n    case 2: r = 2; break;\n  }\n}\nvoid loop() {}\n")
                .error("sketch.ino:4:17: note:   crosses initialization of 'int y'"));
        l.add(t("default crosses a String", "volatile int x = 1;\nvoid setup() {\n  switch (x) {\n    case 1: String s = \"a\"; break;\n    default: break;\n  }\n}\nvoid loop() {}\n")
                .error("crosses initialization of 'String s'"));
        l.add(t("goto crosses an initialisation", "void setup() {\n  goto L;\n  int x = 5;\n L: out(x);\n}\nvoid loop() {}\n")
                .error("sketch.ino:4:2: error: jump to label 'L'"));
        l.add(s("legal jumps", """
                volatile int x = 2;
                struct Plain { int a; };
                """, """
                int r = 0;
                switch (x) {
                  case 1: { int y = 5; r = y; break; }
                  case 2: int z; z = 7; r = z; break;
                  case 3: static int st = 4; r = st; break;
                  case 4: Plain p; p.a = 1; r = p.a; break;
                }
                out(r);
                int n = 0;
                again:
                int w = n * 2;
                n++;
                if (n < 3) goto again;
                out(w);
                goto skip;
                { int inner = 9; out(inner); }
                skip:
                out(n);
                """).out("7", "4", "3"));
    }

    // ---------------- arithmetic ----------------

    private static void arithmetic(List<LangTests.Case> l) {
        // long long division is libgcc's __aeabi_ldivmod / __aeabi_uldivmod.
        l.add(s("64-bit division by zero as libgcc does it", "volatile long long z = 0; volatile unsigned long long uz = 0; volatile long long m7 = -7, p7 = 7, zz = 0;", """
                outll(m7 / z); outll(p7 / z); outll(zz / z); outll(p7 % z); outll(m7 % z);
                outull(7ULL / uz); outull(0ULL / uz); outull(7ULL % uz);
                volatile int iz = 0; out(7 / iz); out(7 % iz);
                """).out("-9223372036854775808", "9223372036854775807", "0", "0", "0", "18446744073709551615", "0", "0", "0", "7"));
        l.add(s("64-bit constant division by zero folds the same way", "", """
                outll((-7LL) / 0LL); outll(7LL / 0LL); outll(7LL % 0LL); outull(7ULL / 0ULL);
                """).out("-9223372036854775808", "9223372036854775807", "0", "18446744073709551615"));
        l.add(s("switch on LLONG_MAX and a full-range case", "volatile long long v = 0x7FFFFFFFFFFFFFFFLL;", """
                switch (v) { case 0: out(0); break; case 0x7FFFFFFFFFFFFFFFLL: out(1); break; }
                switch (v) { case -9223372036854775807LL - 1 ... 0: out(2); break; case 1 ... 0x7FFFFFFFFFFFFFFFLL: out(3); break; }
                volatile unsigned long long u = 0xFFFFFFFFFFFFFFFFULL;
                switch (u) { case 0xFFFFFFFFFFFFFFF0ULL ... 0xFFFFFFFFFFFFFFFFULL: out(4); break; default: out(5); }
                """).out("1", "3", "4"));
    }

    // ---------------- heap, sizes, and the compiler's own limits ----------------

    private static void heapAndLimits(List<LangTests.Case> l) {
        // STM32duino's _sbrk keeps _Min_Stack_Size (1 KB) below _estack free.
        l.add(s("heap stops at the minimum stack reservation", "", """
                char* last = 0; char* p; int n = 0;
                while ((p = (char*)malloc(256))) { last = p; n++; }
                unsigned long end = (unsigned long)last + 256;
                out(end <= 0x20004C00UL); out(end > 0x20004C00UL - 600); out(n > 60);
                """).out("1", "1", "1"));
        l.add(t("corrupted free list stops malloc", """
                char* chunkOf(char* p) { int s = ((int*)p)[-1]; return s < 0 ? p - 4 + s : p - 4; }
                void setup() {
                  char* a = (char*)malloc(32); malloc(8); char* c = chunkOf(a); free(a);
                  ((unsigned*)c)[1] = (unsigned)c;
                  malloc(1000);
                }
                void loop() {}
                """).fault(HardFault.Kind.ABORT, 5));
        l.add(t("corrupted free list stops free", """
                char* chunkOf(char* p) { int s = ((int*)p)[-1]; return s < 0 ? p - 4 + s : p - 4; }
                void setup() {
                  char* a = (char*)malloc(32); malloc(8); char* b = (char*)malloc(32); malloc(8);
                  unsigned* c = (unsigned*)chunkOf(a); free(a); c[1] = (unsigned)c;
                  free(b);
                }
                void loop() {}
                """).fault(HardFault.Kind.ABORT, 5));
        l.add(s("String runtime faults like the chip on a wild String", "String s = \"hello\"; String u = \"ab\";", """
                unsigned* h = (unsigned*)&s;
                h[2] = 0xFFFFFFFF;
                h[1] = 0xFFFFFFFF;
                s.replace(u, s);
                s.getBytes((unsigned char*)0x20000200, 0xFFFFFFFF);
                """).fault(HardFault.Kind.BUS_FAULT, 7));
        StringBuilder bomb = new StringBuilder("#define M0 1\n");
        for (int i = 1; i <= 30; i++)
            bomb.append("#define M").append(i).append(" (M").append(i - 1).append("+M").append(i - 1).append(")\n");
        bomb.append("int x = M30;\nvoid setup() {}\nvoid loop() {}\n");
        l.add(t("macro expansion bomb", bomb.toString()).error("macro expansion exceeds 1000000 tokens"));
        StringBuilder fbomb = new StringBuilder("#define F0(x) x\n");
        for (int i = 1; i <= 30; i++)
            fbomb.append("#define F").append(i).append("(x) (F").append(i - 1).append("(x)+F").append(i - 1).append("(x))\n");
        fbomb.append("int x = F30(1);\nvoid setup() {}\nvoid loop() {}\n");
        l.add(t("function-like macro bomb", fbomb.toString()).error("macro expansion exceeds 1000000 tokens"));
        // Huge objects: reported as the linker would, without allocating them in the compiler.
        l.add(s("huge unused const array is dropped", "const char big[0x7FFFFFFF] PROGMEM = {1};", "out(1);").out("1"));
        l.add(t("huge const array overflows FLASH", "const char big[1500000000] = {1};\nvoid setup() { out(big[3]); }\nvoid loop() {}\n")
                .error("region `FLASH' overflowed by"));
        l.add(t("huge initialised array overflows RAM", "char big[0x7FFFFFF0] = \"abc\";\nvoid setup() { big[5] = 1; }\nvoid loop() {}\n")
                .error("region `RAM' overflowed by"));
        l.add(t("huge static local const", "void setup() { static const char big[0x7FFFFF00] = {1}; out(big[2]); }\nvoid loop() {}\n")
                .error("region `FLASH' overflowed by"));
        l.add(t("two arrays that sum past 4 GB", "char a[0x7FFFFFFF]; char b[0x7FFFFFFF];\nvoid setup() { a[1] = b[1]; }\nvoid loop() {}\n")
                .error("region `RAM' overflowed by"));
        l.add(t("struct array whose size wraps to zero", "struct S { char a[0x10000000]; };\nS s[16];\nvoid setup() { s[0].a[0] = 1; }\nvoid loop() {}\n")
                .error("size of array is too large"));
        l.add(t("struct bigger than any object", "struct S { char a[0x7FFFFFFF]; char b[0x7FFFFFFF]; };\nS s;\nvoid setup() { s.b[0] = 1; }\nvoid loop() {}\n")
                .error("size of type 'struct S' is too large"));
        l.add(t("huge local frame faults with its real size", "void setup() {\n  volatile char big[0x7FFFFFFF];\n  big[0] = 1;\n}\nvoid loop() {}\n")
                .fault(HardFault.Kind.STACK_OVERFLOW, 0).then(e -> check(e.cpu.fault().getMessage().contains("needs 2147483"),
                        "message: " + e.cpu.fault().getMessage())));
        // Deep nesting compiles on the compiler's own thread, whatever the caller's stack.
        int depth = 3000;
        l.add(s("deeply nested parentheses", "int x = 1;", "out(" + "(".repeat(depth) + "x" + ")".repeat(depth) + ");").out("1"));
        StringBuilder chain = new StringBuilder("volatile int x = 2999;\nint f() {\n");
        for (int i = 0; i < 3000; i++)
            chain.append(i == 0 ? "  " : "  else ").append("if (x == ").append(i).append(") return ").append(i).append(";\n");
        chain.append("  return -1;\n}\nvoid setup() { out(f()); done(); }\nvoid loop() {}\n");
        l.add(t("3000-branch else-if chain", chain.toString()).out("2999"));
        l.add(s("long left-leaning chains", "volatile int a = 1;", "int s = " + "a+".repeat(5000) + "a; out(s); bool b = " + "a && ".repeat(5000)
                + "a; out(b);").out("5001", "1"));
        l.add(t("nesting past the limit is a diagnostic", "int x = 1;\nvoid setup() { out(" + "(".repeat(5000) + "x" + ")".repeat(5000) + "); }\nvoid loop() {}\n")
                .error("bracket nesting level exceeded maximum of 4096"));
        l.add(s("fault line after a default argument", "String s = \"hello\";", """
                unsigned* h = (unsigned*)&s;
                h[2] = 0xFFFFFFFF;
                s.getBytes((unsigned char*)0x20000200, 0xFFFFFFFF);
                """).fault(HardFault.Kind.BUS_FAULT, 5));
    }

    private LangRegressions() {}
}
