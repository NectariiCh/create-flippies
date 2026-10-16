package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.nio.charset.StandardCharsets;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.Memory;

/**
 * newlib-nano's vfprintf, which is what STM32duino links by default: integers,
 * characters, strings and pointers with flags, width and precision — and no floating
 * point. Without {@code -u _printf_float} (off in the default "Newlib Nano" setting) a
 * {@code %f}, {@code %e} or {@code %g} prints nothing and swallows its double; that is
 * the classic surprise on these boards, and dtostrf() is the cure, so we keep it.
 *
 * Variadic arguments arrive one per {@link Invocation} slot after the fixed ones,
 * promoted as C promotes them: integers as their (sign-extended) value, floats as
 * doubles. {@code %lld} is formatted from the full 64 bits.
 *
 * <h2>Bounded output</h2>
 *
 * A width or precision can ask for up to 2^31 characters ({@code "%999999999d"},
 * {@code "%*d"} with a huge argument), and a sketch can do that every loop. The text
 * is therefore never built beyond what the destination can take: the caller says how
 * many bytes it will store ({@code cap}), padding is counted rather than written, and
 * the {@link Result} carries the logical length separately. That length is what the
 * call returns and what it is charged for (the chip's putc loop really runs that many
 * times), so emulated time pays for the work and the host does not.
 */
final class Printf {

    /**
     * The text as far as the destination takes it, the length the whole output has
     * (which {@code %n} and the return value report), and how many conversions and
     * digits it took, for the cost.
     */
    record Result(byte[] bytes, long length, int conversions, int digits) {

        /** What sprintf/snprintf return: the length, or -1 (EOVERFLOW) past INT_MAX, as C requires. */
        int returned() {
            return length > Integer.MAX_VALUE ? -1 : (int) length;
        }
    }

    /** The output: at most {@code cap} characters kept, every one counted. */
    private static final class Out {

        final StringBuilder text = new StringBuilder();
        final int cap;
        long length;

        Out(int cap) {
            this.cap = Math.max(0, cap);
        }

        void append(char c) {
            if (text.length() < cap)
                text.append(c);
            length++;
        }

        void append(String s) {
            int room = cap - text.length();
            if (room > 0)
                text.append(s, 0, Math.min(s.length(), room));
            length += s.length();
        }

        void repeat(char c, long count) {
            if (count <= 0)
                return;
            long keep = Math.min(count, cap - text.length());
            for (long k = 0; k < keep; k++)
                text.append(c);
            length += count;
        }
    }

    /** Formats from argument {@code firstArg} on, keeping at most {@code cap} bytes of the text. */
    static Result format(Invocation call, String fmt, int firstArg, int cap) {
        Memory mem = call.memory();
        Out out = new Out(cap);
        int arg = firstArg;
        int conversions = 0;
        int digits = 0;
        int i = 0;
        int n = fmt.length();
        while (i < n) {
            char ch = fmt.charAt(i++);
            if (ch != '%') {
                out.append(ch);
                continue;
            }
            if (i >= n)
                break;
            boolean left = false;
            boolean plus = false;
            boolean space = false;
            boolean alt = false;
            boolean zero = false;
            for (; i < n; i++) {
                char f = fmt.charAt(i);
                if (f == '-') left = true;
                else if (f == '+') plus = true;
                else if (f == ' ') space = true;
                else if (f == '#') alt = true;
                else if (f == '0') zero = true;
                else break;
            }
            // width and precision as longs, clamped to INT_MAX: neither can overflow into a sign
            long width = 0;
            if (i < n && fmt.charAt(i) == '*') {
                width = (int) argAt(call, arg++);
                if (width < 0) {
                    left = true;
                    width = -width;
                }
                i++;
            } else {
                while (i < n && Character.isDigit(fmt.charAt(i)))
                    width = Math.min(Integer.MAX_VALUE, width * 10 + (fmt.charAt(i++) - '0'));
            }
            long precision = -1;
            if (i < n && fmt.charAt(i) == '.') {
                i++;
                precision = 0;
                if (i < n && fmt.charAt(i) == '*') {
                    precision = (int) argAt(call, arg++); // negative: as if omitted
                    i++;
                } else {
                    while (i < n && Character.isDigit(fmt.charAt(i)))
                        precision = Math.min(Integer.MAX_VALUE, precision * 10 + (fmt.charAt(i++) - '0'));
                }
            }
            int length = 0; // -2 hh, -1 h, 0 int, 1 l, 2 ll
            while (i < n) {
                char l = fmt.charAt(i);
                if (l == 'h') length = length == -1 ? -2 : -1;
                else if (l == 'l') length = length == 1 ? 2 : 1;
                else if (l == 'j' || l == 'L') length = 2;
                else if (l == 'z' || l == 't') length = 0;
                else break;
                i++;
            }
            if (i >= n)
                break;
            char conv = fmt.charAt(i++);
            conversions++;
            String body;
            String sign = "";
            String prefix = "";
            switch (conv) {
                case '%':
                    out.append('%');
                    continue;
                case 'c':
                    body = String.valueOf((char) (argAt(call, arg++) & 0xFF));
                    precision = -1;
                    zero = false;
                    break;
                case 's': {
                    long p = argAt(call, arg++) & 0xFFFF_FFFFL;
                    body = p == 0 ? "(null)" : mem.readCString(p, (int) (precision >= 0 ? Math.min(precision, 0x10000) : 0x10000));
                    if (precision >= 0 && body.length() > precision)
                        body = body.substring(0, (int) precision);
                    precision = -1;
                    zero = false;
                    break;
                }
                case 'd':
                case 'i': {
                    long v = signed(argAt(call, arg++), length);
                    if (v < 0) {
                        sign = "-";
                        body = Long.toUnsignedString(-v);
                    } else {
                        sign = plus ? "+" : space ? " " : "";
                        body = Long.toString(v);
                    }
                    break;
                }
                case 'u':
                    body = Long.toUnsignedString(unsigned(argAt(call, arg++), length));
                    break;
                case 'o':
                    body = Long.toOctalString(unsigned(argAt(call, arg++), length));
                    if (alt && !body.startsWith("0"))
                        body = "0" + body;
                    break;
                case 'x':
                case 'X': {
                    long v = unsigned(argAt(call, arg++), length);
                    body = Long.toHexString(v);
                    if (alt && v != 0)
                        prefix = "0x";
                    if (conv == 'X') {
                        body = body.toUpperCase();
                        prefix = prefix.toUpperCase();
                    }
                    break;
                }
                case 'p':
                    body = Long.toHexString(argAt(call, arg++) & 0xFFFF_FFFFL);
                    prefix = "0x";
                    break;
                case 'n': {
                    long p = argAt(call, arg++) & 0xFFFF_FFFFL;
                    if (p != 0)
                        mem.write32(p, (int) out.length);
                    continue;
                }
                case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': case 'a': case 'A':
                    arg++; // no _printf_float: the double is consumed and nothing is printed
                    continue;
                default:
                    continue;
            }
            long zeros = 0; // the precision's leading zeros, counted rather than built
            if (conv != 'c' && conv != 's') {
                digits += body.length();
                if (precision >= 0) {
                    zero = false;
                    if (precision == 0 && body.equals("0"))
                        body = "";
                    zeros = Math.max(0, precision - body.length());
                }
            }
            long pad = width - sign.length() - prefix.length() - zeros - body.length();
            if (left) {
                out.append(sign);
                out.append(prefix);
                out.repeat('0', zeros);
                out.append(body);
                out.repeat(' ', pad);
            } else if (zero) {
                out.append(sign);
                out.append(prefix);
                out.repeat('0', pad);
                out.append(body);
            } else {
                out.repeat(' ', pad);
                out.append(sign);
                out.append(prefix);
                out.repeat('0', zeros);
                out.append(body);
            }
        }
        return new Result(out.text.toString().getBytes(StandardCharsets.ISO_8859_1), out.length, conversions, digits);
    }

    private static long argAt(Invocation call, int index) {
        return index < call.argCount() ? call.arg(index) : 0;
    }

    private static long signed(long v, int length) {
        switch (length) {
            case -2: return (byte) v;
            case -1: return (short) v;
            case 2: return v;
            default: return (int) v;
        }
    }

    private static long unsigned(long v, int length) {
        switch (length) {
            case -2: return v & 0xFF;
            case -1: return v & 0xFFFF;
            case 2: return v;
            default: return v & 0xFFFF_FFFFL;
        }
    }

    /** _vfprintf_r into a string FILE: setup, a scan per format character, a putc per output byte, a division loop per digit. */
    static void charge(Invocation call, int formatLength, Result r) {
        Cost.call(call);
        Cost.mix(call, 80, 50, 30, 40, 0);
        call.charge(CycleClass.ALU, 4L * formatLength);
        call.charge(CycleClass.LOAD, 2L * formatLength);
        call.charge(CycleClass.BRANCH, 3L * formatLength);
        call.charge(CycleClass.ALU, 30L * r.conversions());
        call.charge(CycleClass.BRANCH, 12L * r.conversions());
        call.charge(CycleClass.DIV, 8L * r.digits());
        call.charge(CycleClass.ALU, 6L * r.digits());
        Cost.bytes(call, r.length(), 0, 1);
    }

    private Printf() {}
}
