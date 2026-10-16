package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.nectariich.create_flippies.content.plc.mcu.ArduinoLibrary;
import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.Builtin;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;

/**
 * A small stand-in for the real Arduino core, enough to test the toolchain and the
 * CPU on their own: pins with a settable input level, millis/micros/delay with a
 * Blocker, attachInterrupt, and {@code out*} builtins that record what a sketch
 * prints so tests can compare it with what the chip would produce.
 */
final class TestLibrary implements ArduinoLibrary {

    record B(String prototype, Consumer<Invocation> body) implements Builtin {
        @Override
        public void invoke(Invocation call) {
            body.accept(call);
        }
    }

    final List<Builtin> builtins = new ArrayList<>();

    TestLibrary() {
        add("void out(long v)", c -> env(c).out.add(Long.toString(c.arg(0))));
        add("void outu(unsigned long v)", c -> env(c).out.add(Long.toString(c.arg(0))));
        add("void outll(long long v)", c -> env(c).out.add(Long.toString(c.arg(0))));
        add("void outull(unsigned long long v)", c -> env(c).out.add(Long.toUnsignedString(c.arg(0))));
        add("void outf(double v)", c -> env(c).out.add(fmt(c.argDouble(0))));
        add("void outs(const char* s)", c -> env(c).out.add(c.arg(0) == 0 ? "(null)" : c.memory().readCString(c.arg(0), 1000)));
        add("void outx(unsigned long v)", c -> env(c).out.add(Long.toHexString(c.arg(0))));
        add("void done()", c -> {
            env(c).done = true;
            c.block(new Blocker() {
                @Override
                public long nextPollCycle() {
                    return Long.MAX_VALUE;
                }

                @Override
                public boolean poll(Invocation call) {
                    return false;
                }
            });
        });
        add("void pinMode(uint32_t pin, uint32_t mode)", c -> c.charge(CycleClass.PERIPHERAL, 200));
        add("void digitalWrite(uint32_t pin, uint32_t val)", c -> {
            TestEnv e = env(c);
            int p = (int) c.arg(0);
            boolean v = c.arg(1) != 0;
            c.charge(CycleClass.PERIPHERAL, 50);
            if (e.outputs[p] != v) {
                e.outputs[p] = v;
                e.writes++;
                e.writeLog.add(c.ledger().now());
                c.noteSideEffect();
            }
        });
        add("int digitalRead(uint32_t pin)", c -> {
            c.charge(CycleClass.PERIPHERAL, 50);
            c.returnValue(env(c).inputs[(int) c.arg(0)] ? 1 : 0);
        });
        add("unsigned long millis()", c -> {
            c.charge(CycleClass.ALU, 20);
            c.noteTimeRead(72000);
            c.returnValue(c.ledger().now() / 72000);
        });
        add("unsigned long micros()", c -> {
            c.charge(CycleClass.ALU, 30);
            c.noteTimeRead(72);
            c.returnValue(c.ledger().now() / 72);
        });
        add("void delay(uint32_t ms)", c -> {
            long until = c.ledger().now() + c.arg(0) * 72000;
            c.charge(CycleClass.ALU, 20);
            c.block(new Blocker() {
                @Override
                public long nextPollCycle() {
                    return until;
                }

                @Override
                public boolean poll(Invocation call) {
                    return call.ledger().now() >= until;
                }
            });
        });
        add("void attachInterrupt(uint32_t pin, void (*callback)(void), uint32_t mode)", c -> {
            env(c).handlers[(int) c.arg(0)] = c.arg(1);
            c.noteSideEffect();
        });
        // A variadic builtin with as many arguments as a long sprintf: sums the n after the count.
        add("long sumv(int n, ...)", c -> {
            long s = 0;
            for (int i = 1; i < c.argCount(); i++)
                s += c.arg(i);
            c.returnValue(s);
        });
        // STM32duino declares yield() weak (hooks.c); the compiler knows it by name.
        add("void yield(void)", c -> c.charge(CycleClass.ALU, 1));
        // A builtin the core itself marks weak in its prototype.
        add("__attribute__((weak)) int hook(void)", c -> c.returnValue(1));
        add("void noInterrupts()", c -> c.setInterruptsEnabled(false));
        add("void interrupts()", c -> c.setInterruptsEnabled(true));
        add("void abort()", c -> {
            throw new HardFault(HardFault.Kind.ABORT, -1, "abort()");
        });
        add("size_t strlen(const char* s)", c -> {
            long a = c.arg(0), n = 0;
            while (c.memory().read8(a + n) != 0)
                n++;
            c.charge(CycleClass.LOAD, 4 * n + 4);
            c.returnValue(n);
        });
        add("int strcmp(const char* a, const char* b)", c -> {
            long a = c.arg(0), b = c.arg(1);
            for (long i = 0; ; i++) {
                int x = c.memory().read8(a + i), y = c.memory().read8(b + i);
                if (x != y || x == 0) {
                    c.returnValue(x - y);
                    return;
                }
            }
        });
        add("void* memset(void* d, int v, size_t n)", c -> {
            for (long i = 0; i < c.arg(2); i++)
                c.memory().write8(c.arg(0) + i, (int) c.arg(1));
            c.returnValue(c.arg(0));
        });
        add("void* memcpy(void* d, const void* s, size_t n)", c -> {
            for (long i = 0; i < c.arg(2); i++)
                c.memory().write8(c.arg(0) + i, c.memory().read8(c.arg(1) + i));
            c.returnValue(c.arg(0));
        });
        add("char* strcpy(char* d, const char* s)", c -> {
            long i = 0;
            int ch;
            do {
                ch = c.memory().read8(c.arg(1) + i);
                c.memory().write8(c.arg(0) + i, ch);
                i++;
            } while (ch != 0);
            c.returnValue(c.arg(0));
        });
        add("double sqrt(double x)", c -> {
            c.charge(CycleClass.SOFT_FLOAT, 500);
            c.returnDouble(Math.sqrt(c.argDouble(0)));
        });
        add("float sqrtf(float x)", c -> {
            c.charge(CycleClass.SOFT_FLOAT, 250);
            c.returnFloat((float) Math.sqrt(c.argFloat(0)));
        });
        add("long map(long x, long in_min, long in_max, long out_min, long out_max)", c -> {
            long x = c.arg(0), a = c.arg(1), b = c.arg(2), oa = c.arg(3), ob = c.arg(4);
            c.returnValue((int) ((x - a) * (ob - oa) / (b - a) + oa));
        });
        add("long random(long max)", c -> c.returnValue(4));
        add("long random(long min, long max)", c -> c.returnValue(c.arg(0)));
        add("int sprintf(char* buf, const char* fmt, ...)", c -> {
            // Enough of printf for tests: %d %ld %u %s %c %x.
            String f = c.memory().readCString(c.arg(1), 1000);
            StringBuilder sb = new StringBuilder();
            int ai = 2;
            for (int i = 0; i < f.length(); i++) {
                char ch = f.charAt(i);
                if (ch != '%') {
                    sb.append(ch);
                    continue;
                }
                char k = f.charAt(++i);
                if (k == 'l')
                    k = f.charAt(++i);
                switch (k) {
                    case 'd' -> sb.append((int) c.arg(ai++));
                    case 'u' -> sb.append(c.arg(ai++) & 0xFFFFFFFFL);
                    case 'x' -> sb.append(Long.toHexString(c.arg(ai++) & 0xFFFFFFFFL));
                    case 'c' -> sb.append((char) c.arg(ai++));
                    case 's' -> sb.append(c.memory().readCString(c.arg(ai++), 1000));
                    default -> sb.append(k);
                }
            }
            for (int i = 0; i < sb.length(); i++)
                c.memory().write8(c.arg(0) + i, sb.charAt(i));
            c.memory().write8(c.arg(0) + sb.length(), 0);
            c.returnValue(sb.length());
        });
    }

    static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15)
            return Long.toString((long) d) + ".0";
        return Double.toString(d);
    }

    private void add(String proto, Consumer<Invocation> f) {
        builtins.add(new B(proto, f));
    }

    static TestEnv env(Invocation c) {
        return (TestEnv) c.environment();
    }

    int coreFlash = 10_000;
    int coreRam = 900;

    @Override
    public String prelude() {
        return """
                #define HIGH 0x1
                #define LOW 0x0
                #define INPUT 0x0
                #define OUTPUT 0x1
                #define INPUT_PULLUP 0x2
                #define RISING 4
                #define FALLING 3
                #define CHANGE 2
                #define LED_BUILTIN 16
                #define DI0 0
                #define DI1 1
                #define DO0 8
                #define DO1 9
                #define PI 3.1415926535897932384626433832795
                #define min(a,b) ((a)<(b)?(a):(b))
                #define max(a,b) ((a)>(b)?(a):(b))
                #define abs(x) ((x)>0?(x):-(x))
                #define bitRead(value, bit) (((value) >> (bit)) & 0x01)
                #define bitSet(value, bit) ((value) |= (1UL << (bit)))
                #define F(s) (s)
                #define B101 5
                #define pgm_read_byte(addr) (*(const unsigned char *)(addr))
                #define digitalPinToInterrupt(p) (p)
                typedef bool boolean;
                typedef uint8_t byte;
                typedef unsigned int word;
                """;
    }

    @Override
    public List<Builtin> builtins() {
        return builtins;
    }

    @Override
    public int coreFlashBytes() {
        return coreFlash;
    }

    @Override
    public int coreRamBytes() {
        return coreRam;
    }

    @Override
    public int flashBytesFor(String builtinName) {
        return switch (builtinName) {
            case "sqrt" -> 1200;
            case "digitalWrite", "digitalRead" -> 120;
            default -> 40;
        };
    }
}
