package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;

/**
 * Maths: newlib's libm, Arduino's map() and random(), and the small integer helpers.
 *
 * <h2>Overloads</h2>
 *
 * Arduino.h pulls in libstdc++'s {@code <math.h>}, so a C++ sketch sees
 * {@code float sqrt(float)} beside {@code double sqrt(double)}: {@code sqrt(x)} on a
 * float runs sqrtf and costs what sqrtf costs. Unary functions therefore come as
 * double, float and the four integer types (libstdc++'s integral template promotes
 * those to double; listing them keeps {@code sqrt(16)} from being ambiguous for an
 * overload resolver without templates). Two-argument functions (pow, atan2, fmod,
 * hypot, fmin, fmax) likewise: libstdc++ has {@code float pow(float, float)} beside the
 * double one, which runs powf (half the time, powf's rounding), and a template for
 * every other mix that promotes both arguments to double ({@code pow(float, int)} is
 * double in C++11). Without templates, a double and a float overload alone would make
 * {@code pow(2, 8)} ambiguous, so the template is spelt out: one overload for every
 * pair of argument types out of double, float and the integer types (see
 * {@link #BINARY_TYPES}), each an exact match for its pair. (float, float) is the
 * float function; every other pair converts to double and is the double function,
 * plus the conversions. ldexp, whose second argument is always int, has the full set
 * like a unary function.
 *
 * {@code round} and {@code abs} are these functions, not AVR's macros (see
 * {@link Prelude}): {@code round} returns a double, and {@code abs} comes as
 * {@code std::abs}'s int, long, long long, float and double overloads.
 *
 * <h2>Costs</h2>
 *
 * Soft-float on the M3 (libgcc's ieee754 routines, newlib's libm built -Os): the
 * float figures follow the usual measurements of those routines on Cortex-M3
 * (fadd ~50, fmul ~45, fdiv ~110 cycles; sqrtf ~250, sinf ~1100, powf ~2000), the
 * double versions roughly twice that. Results are computed in Java's IEEE arithmetic
 * and rounded to the result type, which matches newlib to within its last bit or two.
 */
final class MathBuiltins {

    private static final String[] INTEGER_TYPES = { "int", "unsigned int", "long", "unsigned long" };

    /**
     * The argument types a two-argument function has an exact overload for, each
     * against each: smaller integer types, bool and enums promote to int, which beats
     * the conversions every other candidate needs, so they resolve as C++ does.
     */
    private static final String[] BINARY_TYPES = {
            "double", "float", "int", "unsigned int", "long", "unsigned long", "long long", "unsigned long long" };

    /** Float argument to double: __aeabi_f2d. */
    private static final int FLOAT_TO_DOUBLE_CYCLES = 20;

    /** 64-bit integer argument to double: __aeabi_l2d / __aeabi_ul2d. */
    private static final int LONG_LONG_TO_DOUBLE_CYCLES = 50;

    /** Integer argument to double: __aeabi_i2d / __aeabi_ui2d. */
    private static final int INT_TO_DOUBLE_CYCLES = 30;

    static void register(List<Fn> out) {
        unary(out, "sqrt", 520, 250, 420, 220, Math::sqrt);
        unary(out, "sin", 2300, 1100, 2600, 1300, Math::sin);
        unary(out, "cos", 2300, 1100, 2600, 1300, Math::cos);
        unary(out, "tan", 3300, 1600, 2900, 1500, Math::tan);
        unary(out, "asin", 2700, 1300, 1800, 900, Math::asin);
        unary(out, "acos", 2700, 1300, 1800, 900, Math::acos);
        unary(out, "atan", 2300, 1100, 1300, 700, Math::atan);
        unary(out, "exp", 2100, 1000, 1200, 700, Math::exp);
        unary(out, "log", 2100, 1000, 1200, 700, Math::log);
        unary(out, "log10", 2300, 1100, 1400, 800, Math::log10);
        unary(out, "fabs", 6, 4, 12, 8, Math::abs);
        unary(out, "floor", 80, 40, 260, 160, Math::floor);
        unary(out, "ceil", 80, 40, 260, 160, Math::ceil);
        unary(out, "trunc", 70, 36, 200, 120, x -> x < 0 ? Math.ceil(x) : Math.floor(x));
        // round: newlib's s_round/sf_round work on the bit pattern (unpack, add half an ulp
        // at the binary point, mask), so about trunc's cost
        unary(out, "round", 80, 40, 220, 130, MathBuiltins::roundHalfAway);
        unary(out, "cbrt", 1500, 700, 900, 520, Math::cbrt);
        unary(out, "log2", 2200, 1050, 1300, 760, MathBuiltins::log2);
        // the hyperbolic functions are exp/expm1 plus a division
        unary(out, "sinh", 2700, 1300, 1700, 1000, Math::sinh);
        unary(out, "cosh", 2500, 1200, 1500, 900, Math::cosh);
        unary(out, "tanh", 2900, 1400, 1700, 1000, Math::tanh);

        binary(out, "pow", 4200, 2000, 3400, 1800, Math::pow);
        binary(out, "atan2", 2900, 1400, 1600, 900, Math::atan2);
        binary(out, "fmod", 600, 300, 900, 500, (x, y) -> x % y);
        // fmin/fmax: two isnan tests and a compare (C99: a NaN argument loses to a number)
        binary(out, "fmin", 90, 50, 120, 80, MathBuiltins::fmin);
        binary(out, "fmax", 90, 50, 120, 80, MathBuiltins::fmax);
        // hypot: scaled to avoid overflow, then a square root
        binary(out, "hypot", 1100, 480, 700, 420, Math::hypot);
        lround(out);
        ldexp(out);

        out.add(new Fn("int isnan(double x)", 16, c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 6);
            c.returnValue(Double.isNaN(c.argDouble(0)) ? 1 : 0);
        }));
        out.add(new Fn("int isinf(double x)", 16, c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 6);
            c.returnValue(Double.isInfinite(c.argDouble(0)) ? (c.argDouble(0) > 0 ? 1 : -1) : 0);
        }));

        out.add(new Fn("long map(long x, long in_min, long in_max, long out_min, long out_max)", 40, MathBuiltins::map));
        out.add(new Fn("long random(long howbig)", 160, c -> {
            long big = (int) c.arg(0);
            Cost.call(c);
            if (big == 0) {
                c.returnValue(0);
                return;
            }
            int r = next(c);
            c.charge(CycleClass.DIV, 8);
            c.charge(CycleClass.MUL, 1);
            c.returnValue((int) (r % big));
        }));
        out.add(new Fn("long random(long howsmall, long howbig)", 160, c -> {
            int small = (int) c.arg(0);
            int big = (int) c.arg(1);
            Cost.call(c);
            c.charge(CycleClass.ALU, 3);
            if (small >= big) {
                c.returnValue(small);
                return;
            }
            int diff = big - small;
            Cost.call(c);
            if (diff == 0) {
                c.returnValue(small);
                return;
            }
            int r = next(c);
            c.charge(CycleClass.DIV, 8);
            c.charge(CycleClass.MUL, 1);
            c.returnValue(r % diff + small);
        }));
        out.add(new Fn("long random(void)", 80, c -> c.returnValue(next(c))));
        out.add(new Fn("int rand(void)", 80, c -> c.returnValue(next(c))));
        out.add(new Fn("void randomSeed(unsigned long seed)", 24, c -> {
            Cost.call(c);
            long seed = c.arg(0) & 0xFFFF_FFFFL;
            if (seed != 0)
                seed(c, seed);
        }));
        out.add(new Fn("void srandom(unsigned int seed)", 20, c -> seed(c, c.arg(0) & 0xFFFF_FFFFL)));
        out.add(new Fn("void srand(unsigned int seed)", 20, c -> seed(c, c.arg(0) & 0xFFFF_FFFFL)));

        out.add(new Fn("unsigned short makeWord(unsigned short w)", 8, c -> {
            Cost.call(c);
            c.returnValue(c.arg(0) & 0xFFFF);
        }));
        out.add(new Fn("unsigned short makeWord(unsigned char h, unsigned char l)", 8, c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 2);
            c.returnValue(((c.arg(0) & 0xFF) << 8 | (c.arg(1) & 0xFF)) & 0xFFFF);
        }));
        out.add(new Fn("int abs(int j)", 8, c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 2);
            int v = (int) c.arg(0);
            c.returnValue(v < 0 ? -v : v);
        }));
        out.add(new Fn("long labs(long j)", 8, c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 2);
            int v = (int) c.arg(0);
            c.returnValue(v < 0 ? -v : v);
        }));
        // std::abs's other overloads (<cstdlib> and <cmath>, which Arduino.h pulls in): one
        // argument evaluation, the argument's own type back
        out.add(new Fn("long abs(long j)", 8, c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 2);
            int v = (int) c.arg(0);
            c.returnValue(v < 0 ? -v : v);
        }));
        Fn.Body abs64 = c -> {
            Cost.call(c);
            c.charge(CycleClass.ALU, 4);
            long v = c.arg(0);
            c.returnValue(v < 0 ? -v : v);
        };
        out.add(new Fn("long long abs(long long j)", 12, abs64));
        out.add(new Fn("long long llabs(long long j)", 12, abs64));
        out.add(new Fn("float abs(float x)", 4, c -> {
            c.charge(CycleClass.ALU, 1); // fabsf is inline: BIC of the sign bit
            c.returnFloat(Math.abs(c.argFloat(0)));
        }));
        out.add(new Fn("double abs(double x)", 6, c -> {
            c.charge(CycleClass.ALU, 2);
            c.returnDouble(Math.abs(c.argDouble(0)));
        }));
    }

    /** C's round(): halfway cases away from zero (Java's Math.round goes up). */
    static double roundHalfAway(double x) {
        double a = Math.abs(x);
        double t = Math.floor(a);
        if (a - t >= 0.5)
            t += 1;
        return Math.copySign(t, x);
    }

    /** log2, exact on powers of two (newlib's is: the exponent is extracted, not divided out). */
    static double log2(double x) {
        if (x > 0 && !Double.isInfinite(x)) {
            long bits = Double.doubleToRawLongBits(x);
            if ((bits & 0x000F_FFFF_FFFF_FFFFL) == 0 && Math.getExponent(x) >= Double.MIN_EXPONENT)
                return Math.getExponent(x);
        }
        return Math.log(x) / Math.log(2);
    }

    static double fmin(double x, double y) {
        if (Double.isNaN(x))
            return y;
        if (Double.isNaN(y))
            return x;
        return x < y ? x : y;
    }

    static double fmax(double x, double y) {
        if (Double.isNaN(x))
            return y;
        if (Double.isNaN(y))
            return x;
        return x > y ? x : y;
    }

    /**
     * lround/lroundf: round half away from zero to a long. Out of range is undefined in
     * C; newlib's falls back to a plain conversion, which __aeabi_d2iz saturates, so
     * we saturate (NaN gives 0). Like the unary functions, integer arguments resolve to
     * libstdc++'s integral overload, which converts to double first.
     */
    private static void lround(List<Fn> out) {
        out.add(new Fn("long lround(double x)", 260, c -> {
            Cost.call(c);
            Cost.softFloat(c, 90);
            c.returnValue(saturateToLong(roundHalfAway(c.argDouble(0))));
        }));
        Fn.Body single = c -> {
            Cost.call(c);
            Cost.softFloat(c, 50);
            c.returnValue(saturateToLong(roundHalfAway(c.argFloat(0))));
        };
        out.add(new Fn("long lroundf(float x)", 160, single));
        out.add(new Fn("long lround(float x)", 160, single));
        for (String type : INTEGER_TYPES) {
            boolean unsigned = type.startsWith("unsigned");
            out.add(new Fn("long lround(" + type + " x)", 260, c -> {
                Cost.call(c);
                Cost.softFloat(c, INT_TO_DOUBLE_CYCLES + 90);
                long raw = c.arg(0);
                double x = unsigned ? (double) (raw & 0xFFFF_FFFFL) : (double) (int) raw;
                c.returnValue(saturateToLong(x));
            }));
        }
    }

    private static int saturateToLong(double v) {
        if (Double.isNaN(v))
            return 0;
        if (v >= Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        if (v <= Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
        return (int) v;
    }

    /** ldexp/ldexpf: x * 2^exp by exponent arithmetic (newlib's scalbn), correctly rounded at the bottom of the range. */
    private static void ldexp(List<Fn> out) {
        out.add(new Fn("double ldexp(double x, int exp)", 280, c -> {
            Cost.call(c);
            Cost.softFloat(c, 70);
            c.returnDouble(Math.scalb(c.argDouble(0), (int) c.arg(1)));
        }));
        Fn.Body single = c -> {
            Cost.call(c);
            Cost.softFloat(c, 45);
            c.returnFloat(Math.scalb(c.argFloat(0), (int) c.arg(1)));
        };
        out.add(new Fn("float ldexpf(float x, int exp)", 180, single));
        out.add(new Fn("float ldexp(float x, int exp)", 180, single));
        for (String type : INTEGER_TYPES) {
            boolean unsigned = type.startsWith("unsigned");
            out.add(new Fn("double ldexp(" + type + " x, int exp)", 280, c -> {
                Cost.call(c);
                Cost.softFloat(c, INT_TO_DOUBLE_CYCLES + 70);
                long raw = c.arg(0);
                double x = unsigned ? (double) (raw & 0xFFFF_FFFFL) : (double) (int) raw;
                c.returnDouble(Math.scalb(x, (int) c.arg(1)));
            }));
        }
    }

    private static void unary(List<Fn> out, String name, int doubleCycles, int floatCycles,
                              int doubleFlash, int floatFlash, DoubleUnaryOperator f) {
        out.add(new Fn("double " + name + "(double x)", doubleFlash, c -> {
            Cost.call(c);
            Cost.softFloat(c, doubleCycles);
            c.returnDouble(f.applyAsDouble(c.argDouble(0)));
        }));
        Fn.Body single = c -> {
            Cost.call(c);
            Cost.softFloat(c, floatCycles);
            c.returnFloat((float) f.applyAsDouble(c.argFloat(0)));
        };
        out.add(new Fn("float " + name + "f(float x)", floatFlash, single));
        out.add(new Fn("float " + name + "(float x)", floatFlash, single));
        for (String type : INTEGER_TYPES) {
            boolean unsigned = type.startsWith("unsigned");
            out.add(new Fn("double " + name + "(" + type + " x)", doubleFlash, c -> {
                Cost.call(c);
                Cost.softFloat(c, INT_TO_DOUBLE_CYCLES + doubleCycles);
                long raw = c.arg(0);
                double x = unsigned ? (double) (raw & 0xFFFF_FFFFL) : (double) (int) raw;
                c.returnDouble(f.applyAsDouble(x));
            }));
        }
    }

    private static void binary(List<Fn> out, String name, int doubleCycles, int floatCycles,
                               int doubleFlash, int floatFlash, DoubleBinaryOperator f) {
        out.add(new Fn("double " + name + "(double x, double y)", doubleFlash, c -> {
            Cost.call(c);
            Cost.softFloat(c, doubleCycles);
            c.returnDouble(f.applyAsDouble(c.argDouble(0), c.argDouble(1)));
        }));
        Fn.Body single = c -> {
            Cost.call(c);
            Cost.softFloat(c, floatCycles);
            c.returnFloat((float) f.applyAsDouble(c.argFloat(0), c.argFloat(1)));
        };
        out.add(new Fn("float " + name + "f(float x, float y)", floatFlash, single));
        for (String x : BINARY_TYPES) {
            for (String y : BINARY_TYPES) {
                if (x.equals("double") && y.equals("double"))
                    continue;
                if (x.equals("float") && y.equals("float")) {
                    out.add(new Fn("float " + name + "(float x, float y)", floatFlash, single));
                    continue;
                }
                int convert = toDoubleCycles(x) + toDoubleCycles(y);
                out.add(new Fn("double " + name + "(" + x + " x, " + y + " y)", doubleFlash, c -> {
                    Cost.call(c);
                    Cost.softFloat(c, convert + doubleCycles);
                    c.returnDouble(f.applyAsDouble(argAsDouble(c, 0, x), argAsDouble(c, 1, y)));
                }));
            }
        }
    }

    /** What converting an argument of this type to double costs: nothing for a double. */
    private static int toDoubleCycles(String type) {
        return switch (type) {
            case "double" -> 0;
            case "float" -> FLOAT_TO_DOUBLE_CYCLES;
            case "long long", "unsigned long long" -> LONG_LONG_TO_DOUBLE_CYCLES;
            default -> INT_TO_DOUBLE_CYCLES;
        };
    }

    /** Argument {@code i}, of C type {@code type}, as the double C++ promotes it to. */
    private static double argAsDouble(Invocation c, int i, String type) {
        return switch (type) {
            case "double" -> c.argDouble(i);
            case "float" -> c.argFloat(i);
            case "int", "long" -> (int) c.arg(i);
            case "unsigned int", "unsigned long" -> c.arg(i) & 0xFFFF_FFFFL;
            case "long long" -> c.arg(i);
            case "unsigned long long" -> unsignedToDouble(c.arg(i));
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static double unsignedToDouble(long v) {
        return v >= 0 ? v : ((v >>> 1) | (v & 1)) * 2.0;
    }

    /**
     * WMath's map: {@code (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min}
     * in 32-bit long arithmetic, wrapping; SDIV by zero gives 0.
     */
    private static void map(Invocation c) {
        int x = (int) c.arg(0);
        int inMin = (int) c.arg(1);
        int inMax = (int) c.arg(2);
        int outMin = (int) c.arg(3);
        int outMax = (int) c.arg(4);
        Cost.call(c);
        c.charge(CycleClass.ALU, 6);
        c.charge(CycleClass.MUL, 1);
        c.charge(CycleClass.DIV, 8);
        c.charge(CycleClass.LOAD, 2); // the fifth argument comes off the stack
        int num = (x - inMin) * (outMax - outMin);
        int den = inMax - inMin;
        c.returnValue((den == 0 ? 0 : num / den) + outMin);
    }

    /**
     * newlib's random()/rand(): a 64-bit LCG in the reentrancy struct,
     * {@code next = next * 6364136223846793005 + 1}, returning bits 32..62. Seeded with 1.
     */
    private static int next(Invocation c) {
        CoreState core = CoreState.of(c);
        Cost.call(c);
        c.charge(CycleClass.MUL, 8); // UMULL + two MLAs
        c.charge(CycleClass.ALU, 4);
        c.charge(CycleClass.LOAD, 6);
        c.charge(CycleClass.STORE, 2);
        core.randNext = core.randNext * 6364136223846793005L + 1;
        c.noteSideEffect();
        return (int) ((core.randNext >>> 32) & 0x7FFF_FFFFL);
    }

    private static void seed(Invocation c, long seed) {
        Cost.call(c);
        c.charge(CycleClass.STORE, 2);
        CoreState core = CoreState.of(c);
        if (core.randNext != seed) {
            core.randNext = seed;
            c.noteSideEffect();
        }
    }

    private MathBuiltins() {}
}
