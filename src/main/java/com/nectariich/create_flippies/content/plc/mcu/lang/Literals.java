package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Literal spellings to values, with the types C++ gives them on a 32-bit target.
 *
 * An unsuffixed decimal literal is the first of int, long, long long that holds it
 * (so on ARM, where long is int-sized, 3000000000 is a long long); an unsuffixed hex,
 * octal or binary one may also become unsigned on the way ([lex.icon] table 7).
 * That difference is visible: {@code 0xFFFFFFFF} is an unsigned int, {@code 4294967295}
 * is a long long, and comparing either with -1 gives different answers.
 */
final class Literals {

    record IntValue(long value, CType type, boolean tooLarge) {}

    /** Parses an integer pp-number, or returns null if it is not one (a float, or garbage). */
    static IntValue parseInt(String text) {
        String s = text.replace("'", "");
        int end = s.length();
        boolean u = false;
        int l = 0;
        while (end > 0) {
            char c = s.charAt(end - 1);
            if ((c == 'u' || c == 'U') && !u) {
                u = true;
                end--;
            } else if ((c == 'l' || c == 'L') && l == 0) {
                if (end >= 2 && (s.charAt(end - 2) == 'l' || s.charAt(end - 2) == 'L')) {
                    l = 2;
                    end -= 2;
                } else {
                    l = 1;
                    end--;
                }
            } else {
                break;
            }
        }
        String digits = s.substring(0, end);
        int radix = 10;
        int start = 0;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            radix = 16;
            start = 2;
        } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
            radix = 2;
            start = 2;
        } else if (digits.length() > 1 && digits.charAt(0) == '0') {
            radix = 8;
            start = 1;
        }
        if (start >= digits.length() && radix != 8)
            return null;
        long v = 0;
        boolean overflow = false;
        for (int i = start; i < digits.length(); i++) {
            int d = Character.digit(digits.charAt(i), radix);
            if (d < 0)
                return null;
            if (Long.compareUnsigned(v, Long.divideUnsigned(-1L, radix)) > 0)
                overflow = true;
            long m = v * radix;
            long nv = m + d;
            if (Long.compareUnsigned(nv, m) < 0)
                overflow = true;
            v = nv;
        }
        boolean decimal = radix == 10;
        CType t;
        boolean fitsInt = Long.compareUnsigned(v, 0x7FFFFFFFL) <= 0;
        boolean fitsUInt = Long.compareUnsigned(v, 0xFFFFFFFFL) <= 0;
        boolean fitsLL = v >= 0;
        if (u) {
            if (l == 2)
                t = CType.ULLONG;
            else if (fitsUInt)
                t = l == 1 ? CType.ULONG : CType.UINT;
            else
                t = CType.ULLONG;
        } else if (l == 2) {
            t = fitsLL || decimal ? CType.LLONG : CType.ULLONG;
        } else if (l == 1) {
            if (fitsInt)
                t = CType.LONG;
            else if (!decimal && fitsUInt)
                t = CType.ULONG;
            else
                t = fitsLL ? CType.LLONG : CType.ULLONG;
        } else {
            if (fitsInt)
                t = CType.INT;
            else if (!decimal && fitsUInt)
                t = CType.UINT;
            else
                t = fitsLL ? CType.LLONG : CType.ULLONG;
        }
        return new IntValue(v, t, overflow);
    }

    static boolean isFloat(String text) {
        String s = text.toLowerCase();
        if (s.startsWith("0x"))
            return s.contains("p");
        return s.contains(".") || s.contains("e");
    }

    record FloatValue(double value, CType type) {}

    static FloatValue parseFloat(String text) {
        String s = text.replace("'", "");
        char last = s.charAt(s.length() - 1);
        CType t = CType.DOUBLE;
        if (last == 'f' || last == 'F') {
            t = CType.FLOAT;
            s = s.substring(0, s.length() - 1);
        } else if (last == 'l' || last == 'L') {
            t = CType.LDOUBLE;
            s = s.substring(0, s.length() - 1);
        }
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                double d = Double.parseDouble(s.endsWith("p") ? s + "0" : s);
                return new FloatValue(t == CType.FLOAT ? (float) d : d, t);
            }
            if (t == CType.FLOAT)
                return new FloatValue(Float.parseFloat(s), t);
            return new FloatValue(Double.parseDouble(s), t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A character literal's value and whether it is a plain one-char {@code char}
     * ({@code [1] == 1}) or a multicharacter int ({@code [1] == 0}); null if malformed.
     */
    static long[] parseChar(String text) {
        int q = text.indexOf('\'');
        if (q < 0 || text.length() < q + 2 || text.charAt(text.length() - 1) != '\'')
            return null;
        // L'a', u'a', U'a' are wide; C++17's u8'a' is a plain char.
        boolean wide = q > 0 && !text.startsWith("u8");
        byte[] bytes = decode(text.substring(q + 1, text.length() - 1));
        if (bytes == null || bytes.length == 0)
            return null;
        if (bytes.length == 1 && !wide)
            return new long[] { bytes[0] & 0xFF, 1 };
        // GCC: multichar constants accumulate big-endian into an int.
        long v = 0;
        for (byte b : bytes)
            v = (v << 8) | (b & 0xFF);
        return new long[] { (int) v, 0 };
    }

    /** A string literal's bytes, without the terminating NUL; null if malformed. */
    static byte[] parseString(String text) {
        int q = text.indexOf('"');
        if (q < 0 || text.length() < q + 2 || text.charAt(text.length() - 1) != '"')
            return null;
        return decode(text.substring(q + 1, text.length() - 1));
    }

    private static byte[] decode(String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                if (c < 0x80) {
                    out.write(c);
                } else {
                    int cp = body.codePointAt(i);
                    if (Character.charCount(cp) == 2)
                        i++;
                    byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                    out.write(b, 0, b.length);
                }
                continue;
            }
            if (++i >= body.length())
                return null;
            char e = body.charAt(i);
            switch (e) {
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case 'a' -> out.write(7);
                case 'b' -> out.write('\b');
                case 'f' -> out.write('\f');
                case 'v' -> out.write(11);
                case 'e', 'E' -> out.write(27);
                case '\\', '\'', '"', '?' -> out.write(e);
                case 'x' -> {
                    int v = 0, n = 0;
                    while (i + 1 < body.length() && Character.digit(body.charAt(i + 1), 16) >= 0) {
                        v = v * 16 + Character.digit(body.charAt(++i), 16);
                        n++;
                    }
                    if (n == 0)
                        return null;
                    out.write(v & 0xFF);
                }
                default -> {
                    if (e >= '0' && e <= '7') {
                        int v = e - '0', n = 1;
                        while (n < 3 && i + 1 < body.length() && body.charAt(i + 1) >= '0' && body.charAt(i + 1) <= '7') {
                            v = v * 8 + (body.charAt(++i) - '0');
                            n++;
                        }
                        out.write(v & 0xFF);
                    } else {
                        out.write(e);
                    }
                }
            }
        }
        return out.toByteArray();
    }

    private Literals() {}
}
