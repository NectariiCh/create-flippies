package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.util.List;
import java.util.function.IntPredicate;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;

/**
 * {@code <ctype.h>} as newlib implements it in the "C" locale, and Arduino's
 * WCharacter wrappers over it.
 *
 * newlib classifies through a 257-entry table of flag bits and its {@code is*}
 * functions return the masked bits, not 0/1 — {@code isdigit('7')} is 4 — so we keep
 * the same table and masks. Characters above 127 classify as nothing; EOF (-1) is
 * accepted. Arduino's {@code isAlpha} and friends return bool.
 */
final class CharBuiltins {

    static final int U = 01;
    static final int L = 02;
    static final int N = 04;
    static final int S = 010;
    static final int P = 020;
    static final int C = 040;
    static final int X = 0100;
    static final int B = 0200;

    /**
     * The class bits of {@code c}, for -1..255; 0 outside. As in newlib's table, TAB is
     * a control and a space but not blank: the blank bit is on the space character
     * alone, since isprint() tests it and C requires {@code isprint('\t') == 0}. That is
     * why isblank and isWhitespace add TAB by hand.
     */
    static int ctype(long c) {
        if (c < 0 || c > 127)
            return 0;
        int ch = (int) c;
        if (ch >= 9 && ch <= 13)
            return C | S;
        if (ch < 32 || ch == 127)
            return C;
        if (ch == ' ')
            return S | B;
        if (ch >= '0' && ch <= '9')
            return N;
        if (ch >= 'A' && ch <= 'Z')
            return ch <= 'F' ? U | X : U;
        if (ch >= 'a' && ch <= 'z')
            return ch <= 'f' ? L | X : L;
        return P;
    }

    static boolean isSpace(int c) {
        return (ctype(c) & S) != 0;
    }

    static int toLower(long c) {
        return (ctype(c) & (U | L)) == U ? (int) c - 'A' + 'a' : (int) c;
    }

    static int toUpper(long c) {
        return (ctype(c) & (U | L)) == L ? (int) c - 'a' + 'A' : (int) c;
    }

    static void register(List<Fn> out) {
        masked(out, "isalpha", U | L);
        masked(out, "isalnum", U | L | N);
        masked(out, "isdigit", N);
        masked(out, "isxdigit", X | N);
        masked(out, "isspace", S);
        masked(out, "ispunct", P);
        masked(out, "isprint", P | U | L | N | B);
        masked(out, "isgraph", P | U | L | N);
        masked(out, "iscntrl", C);
        exact(out, "int isupper(int c)", c -> (ctype(c) & (U | L)) == U);
        exact(out, "int islower(int c)", c -> (ctype(c) & (U | L)) == L);
        exact(out, "int isblank(int c)", c -> (ctype(c) & B) != 0 || c == '\t');
        exact(out, "int isascii(int c)", c -> c >= 0 && c < 128);
        out.add(new Fn("int toupper(int c)", 24, call -> mapped(call, toUpper((int) call.arg(0)))));
        out.add(new Fn("int tolower(int c)", 24, call -> mapped(call, toLower((int) call.arg(0)))));
        out.add(new Fn("int toascii(int c)", 8, call -> mapped(call, (int) call.arg(0) & 0x7F)));

        arduino(out, "isAlpha", c -> (ctype(c) & (U | L)) != 0);
        arduino(out, "isAlphaNumeric", c -> (ctype(c) & (U | L | N)) != 0);
        arduino(out, "isAscii", c -> c >= 0 && c < 128);
        arduino(out, "isControl", c -> (ctype(c) & C) != 0);
        arduino(out, "isDigit", c -> (ctype(c) & N) != 0);
        arduino(out, "isGraph", c -> (ctype(c) & (P | U | L | N)) != 0);
        arduino(out, "isHexadecimalDigit", c -> (ctype(c) & (X | N)) != 0);
        arduino(out, "isLowerCase", c -> (ctype(c) & (U | L)) == L);
        arduino(out, "isPrintable", c -> (ctype(c) & (P | U | L | N | B)) != 0);
        arduino(out, "isPunct", c -> (ctype(c) & P) != 0);
        arduino(out, "isSpace", c -> (ctype(c) & S) != 0);
        arduino(out, "isUpperCase", c -> (ctype(c) & (U | L)) == U);
        arduino(out, "isWhitespace", c -> (ctype(c) & B) != 0 || c == '\t');
        out.add(new Fn("int toAscii(int c)", 8, call -> mapped(call, (int) call.arg(0) & 0x7F)));
        out.add(new Fn("int toLowerCase(int c)", 24, call -> mapped(call, toLower((int) call.arg(0)))));
        out.add(new Fn("int toUpperCase(int c)", 24, call -> mapped(call, toUpper((int) call.arg(0)))));
    }

    /** A call into the table: BL, bounds, LDRB from the flash table (+ wait states), AND, BX. */
    private static void charge(Invocation call) {
        Cost.call(call);
        call.charge(CycleClass.ALU, 3);
        call.charge(CycleClass.LOAD, 4);
    }

    private static void masked(List<Fn> out, String name, int mask) {
        out.add(new Fn("int " + name + "(int c)", 24, call -> {
            charge(call);
            call.returnValue(ctype((int) call.arg(0)) & mask);
        }));
    }

    private static void exact(List<Fn> out, String prototype, IntPredicate test) {
        out.add(new Fn(prototype, 24, call -> {
            charge(call);
            call.returnValue(test.test((int) call.arg(0)) ? 1 : 0);
        }));
    }

    private static void arduino(List<Fn> out, String name, IntPredicate test) {
        out.add(new Fn("bool " + name + "(int c)", 28, call -> {
            charge(call);
            call.charge(CycleClass.ALU, 2);
            call.returnValue(test.test((int) call.arg(0)) ? 1 : 0);
        }));
    }

    private static void mapped(Invocation call, int value) {
        charge(call);
        call.returnValue(value);
    }

    private CharBuiltins() {}
}
