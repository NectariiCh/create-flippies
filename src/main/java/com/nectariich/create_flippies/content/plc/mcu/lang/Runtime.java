package com.nectariich.create_flippies.content.plc.mcu.lang;

import java.nio.charset.StandardCharsets;

import com.nectariich.create_flippies.content.plc.mcu.CycleClass;

/**
 * The compiler's own runtime library: Arduino's {@code String} (WString.cpp, as
 * STM32duino ships it, inherited from the AVR core) and the heap entry points,
 * executed directly against simulated memory.
 *
 * Each member is a transcription of its C++ original, statement for statement,
 * including the parts that are surprising: {@code concat(char)} appends one byte
 * even if it is NUL, {@code String()} allocates a one-byte buffer, a failed
 * allocation in {@code operator+} leaves an invalid (NULL-buffer) String,
 * {@code toInt()} is {@code atol()} and saturates, and number formatting is the
 * core's {@code itoa}/{@code ultoa} and its hand-rolled {@code dtostrf} (which rounds
 * by adding half a unit and truncates, not printf's round-to-nearest-even). All
 * reads and writes go through {@link SimMemory} and all allocation through
 * {@link Heap}, so the bytes in SRAM are what the board would hold.
 *
 * Cycle costs are counted per byte touched (string functions in newlib-nano are
 * byte loops: about 4 cycles a byte for strlen/strcpy/strcmp, 2 for memcpy/memset)
 * plus a fixed overhead per call for the call itself and the member's own work.
 */
final class Runtime {

    interface Host extends Heap.Cost {
        /** The stack pointer now, for _sbrk's collision check. */
        long stackPointer();
    }

    private final SimMemory m;
    private final Heap heap;
    private final Host host;
    /** Where {@code operator[]} points when the index is out of range (WString's dummy_writable_char). */
    private final long dummyChar;
    /** A "" literal in flash, which {@code String()} copies. */
    private final long emptyStr;

    Runtime(SimMemory m, Heap heap, Host host, long dummyChar, long emptyStr) {
        this.m = m;
        this.heap = heap;
        this.host = host;
        this.dummyChar = dummyChar;
        this.emptyStr = emptyStr;
    }

    private void cost(CycleClass c, long n) {
        host.charge(c, n);
    }

    private void call(int fixed) {
        host.charge(CycleClass.BRANCH, 6);
        host.charge(CycleClass.ALU, fixed);
    }

    // ---- the object: {char *buffer; unsigned capacity; unsigned len} ----

    private long buf(long s) {
        return m.read32(s) & 0xFFFFFFFFL;
    }

    private long cap(long s) {
        return m.read32(s + 4) & 0xFFFFFFFFL;
    }

    private long len(long s) {
        return m.read32(s + 8) & 0xFFFFFFFFL;
    }

    private void setBuf(long s, long v) {
        m.write32(s, (int) v);
    }

    private void setCap(long s, long v) {
        m.write32(s + 4, (int) v);
    }

    private void setLen(long s, long v) {
        m.write32(s + 8, (int) v);
    }

    private void init(long s) {
        setBuf(s, 0);
        setCap(s, 0);
        setLen(s, 0);
        cost(CycleClass.STORE, 3);
    }

    // ---- libc pieces, on simulated memory ----

    long strlen(long a) {
        long n = 0;
        while (m.read8(a + n) != 0)
            n++;
        cost(CycleClass.LOAD, 2 * (n + 1));
        cost(CycleClass.ALU, 2 * (n + 1));
        return n;
    }

    /** strcpy: copies through the NUL. */
    private void strcpy(long dst, long src) {
        long n = 0;
        while (true) {
            int c = m.read8(src + n);
            m.write8(dst + n, c);
            n++;
            if (c == 0)
                break;
        }
        cost(CycleClass.LOAD, 2 * n);
        cost(CycleClass.STORE, 2 * n);
    }

    /**
     * memcpy(dst, src, n) then a NUL at dst + n: what ArduinoCore-API's length-taking
     * copy() and concat() do, so embedded NULs are copied too, unlike strcpy.
     */
    private void copyN(long dst, long src, long n) {
        for (long i = 0; i < n; i++)
            m.write8(dst + i, m.read8(src + i));
        m.write8(dst + n, 0);
        cost(CycleClass.LOAD, n);
        cost(CycleClass.STORE, n + 1);
    }

    private void writeBytes(long dst, byte[] b, int n) {
        for (int i = 0; i < n; i++)
            m.write8(dst + i, b[i]);
        m.write8(dst + n, 0);
        cost(CycleClass.STORE, 2L * (n + 1));
        cost(CycleClass.LOAD, n + 1);
    }

    private int strcmp(long a, long b) {
        long i = 0;
        while (true) {
            int ca = m.read8(a + i), cb = m.read8(b + i);
            i++;
            if (ca != cb || ca == 0) {
                cost(CycleClass.LOAD, 4 * i);
                cost(CycleClass.ALU, 2 * i);
                return ca - cb;
            }
        }
    }

    private int strncmp(long a, long b, long n) {
        for (long i = 0; i < n; i++) {
            int ca = m.read8(a + i), cb = m.read8(b + i);
            if (ca != cb || ca == 0) {
                cost(CycleClass.LOAD, 4 * (i + 1));
                return ca - cb;
            }
        }
        cost(CycleClass.LOAD, 4 * n);
        return 0;
    }

    /** strstr: the first occurrence of needle in hay, or 0. */
    private long strstr(long hay, long needle) {
        int first = m.read8(needle);
        if (first == 0)
            return hay;
        long nlen = strlen(needle);
        long steps = 0;
        for (long p = hay; ; p++) {
            int c = m.read8(p);
            steps++;
            if (c == 0)
                break;
            if (c == first && strncmp(p, needle, nlen) == 0) {
                cost(CycleClass.LOAD, 3 * steps);
                return p;
            }
        }
        cost(CycleClass.LOAD, 3 * steps);
        return 0;
    }

    private long strchr(long s, int ch) {
        ch &= 0xFF;
        for (long p = s; ; p++) {
            int c = m.read8(p);
            if (c == ch) {
                cost(CycleClass.LOAD, 3 * (p - s + 1));
                return p;
            }
            if (c == 0) {
                cost(CycleClass.LOAD, 3 * (p - s + 1));
                return 0;
            }
        }
    }

    // ---- WString internals ----

    private boolean changeBuffer(long s, long maxStrLen) {
        long nb = heap.realloc(buf(s), maxStrLen + 1, host.stackPointer(), host);
        if (nb != 0) {
            setBuf(s, nb);
            setCap(s, maxStrLen);
            return true;
        }
        return false;
    }

    private boolean reserve(long s, long size) {
        size &= 0xFFFFFFFFL;
        if (buf(s) != 0 && cap(s) >= size)
            return true;
        if (changeBuffer(s, size)) {
            if (len(s) == 0)
                m.write8(buf(s), 0);
            return true;
        }
        return false;
    }

    private void invalidate(long s) {
        long b = buf(s);
        if (b != 0)
            heap.free(b, host);
        setBuf(s, 0);
        setCap(s, 0);
        setLen(s, 0);
    }

    /** copy(const char *cstr, unsigned length). */
    private void copy(long s, long cstr, long length) {
        if (!reserve(s, length)) {
            invalidate(s);
            return;
        }
        setLen(s, length);
        strcpy(buf(s), cstr);
    }

    private void copyBytes(long s, byte[] b) {
        if (!reserve(s, b.length)) {
            invalidate(s);
            return;
        }
        setLen(s, b.length);
        writeBytes(buf(s), b, b.length);
    }

    private void assignCstr(long s, long cstr) {
        if (cstr != 0)
            copy(s, cstr, strlen(cstr));
        else
            invalidate(s);
    }

    private void assignCopy(long s, long rhs) {
        if (s == rhs)
            return;
        long rb = buf(rhs);
        if (rb != 0)
            copy(s, rb, len(rhs));
        else
            invalidate(s);
    }

    private void move(long s, long rhs) {
        long b = buf(s);
        if (b != 0) {
            long rb = buf(rhs);
            if (rb != 0 && cap(s) >= len(rhs)) {
                strcpy(b, rb);
                setLen(s, len(rhs));
                setLen(rhs, 0);
                return;
            }
            heap.free(b, host);
        }
        setBuf(s, buf(rhs));
        setCap(s, cap(rhs));
        setLen(s, len(rhs));
        setBuf(rhs, 0);
        setCap(rhs, 0);
        setLen(rhs, 0);
    }

    private boolean concat(long s, long cstr, long length) {
        long newlen = len(s) + length;
        if (cstr == 0)
            return false;
        if (length == 0)
            return true;
        if (!reserve(s, newlen))
            return false;
        strcpy(buf(s) + len(s), cstr);
        setLen(s, newlen);
        return true;
    }

    private boolean concatBytes(long s, byte[] b, int length) {
        long newlen = len(s) + length;
        if (length == 0)
            return true;
        if (!reserve(s, newlen))
            return false;
        writeBytes(buf(s) + len(s), b, length);
        setLen(s, newlen);
        return true;
    }

    // ---- number formatting (the core's itoa.c and avr/dtostrf.c) ----

    static byte[] ltoa(long value, int radix) {
        if (radix > 36 || radix <= 1)
            return new byte[0];
        boolean sign = radix == 10 && value < 0;
        long v = sign ? -value : value & 0xFFFFFFFFL;
        v &= 0xFFFFFFFFL;
        return digits(v, radix, sign);
    }

    static byte[] ultoa(long value, int radix) {
        if (radix > 36 || radix <= 1)
            return new byte[0];
        return digits(value & 0xFFFFFFFFL, radix, false);
    }

    private static byte[] digits(long v, int radix, boolean sign) {
        StringBuilder sb = new StringBuilder();
        do {
            int i = (int) (v % radix);
            v /= radix;
            sb.append((char) (i < 10 ? '0' + i : 'a' + i - 10));
        } while (v != 0);
        if (sign)
            sb.append('-');
        return sb.reverse().toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * STM32duino's dtostrf: rounds by adding half of the last place, truncates the
     * integer part to an unsigned long (printed with %ld), takes the fraction as an
     * int, and pads on the left to the field width.
     */
    static byte[] dtostrf(double val, int width, int prec) {
        boolean negative = false;
        if (val < 0.0) {
            negative = true;
            val = -val;
        }
        double rounding = 0.5;
        for (int i = 0; i < prec; ++i)
            rounding /= 10.0;
        val += rounding;
        long intPart = SemaBase.floatToInt(val, CType.ULONG);
        double remainder = val - (double) intPart;
        String out;
        String ip = Integer.toString((int) intPart);
        if (prec > 0) {
            double decade = 1.0;
            for (int i = 0; i < prec; i++)
                decade *= 10.0;
            remainder *= decade;
            int dec = (int) SemaBase.floatToInt(remainder, CType.INT);
            String ds = Integer.toString(dec);
            StringBuilder pad = new StringBuilder();
            boolean neg = ds.startsWith("-");
            String digitsOnly = neg ? ds.substring(1) : ds;
            for (int i = digitsOnly.length() + (neg ? 1 : 0); i < prec; i++)
                pad.append('0');
            out = (negative ? "-" : "") + ip + "." + (neg ? "-" + pad + digitsOnly : pad + digitsOnly);
        } else {
            out = (negative ? "-" : "") + ip;
        }
        int w = width;
        boolean left = false;
        if (width < 0) {
            left = true;
            w = -width;
        }
        if (out.length() < w && w < 129) {
            StringBuilder sb = new StringBuilder(out);
            while (sb.length() < w) {
                if (left)
                    sb.append(' ');
                else
                    sb.insert(0, ' ');
            }
            out = sb.toString();
        }
        return out.getBytes(StandardCharsets.ISO_8859_1);
    }

    private void numberCost(byte[] digits) {
        cost(CycleClass.DIV, 6L * digits.length);
        cost(CycleClass.ALU, 8L * digits.length + 10);
    }

    private void floatCost(int prec) {
        // Soft-double: a division per decimal, add, convert, subtract, multiply; then sprintf("%ld.%0*ld").
        cost(CycleClass.SOFT_FLOAT, 250L * prec + 100 + 70 + 100 + 110L * prec + 50);
        cost(CycleClass.ALU, 600);
    }

    // ---- strtol / strtod (newlib), for toInt/toFloat ----

    private String cstring(long a) {
        if (a == 0)
            return "";
        String s = m.readCString(a, 4096);
        cost(CycleClass.LOAD, 2L * s.length());
        return s;
    }

    static long atol(String s) {
        int i = 0, n = s.length();
        while (i < n && isspace(s.charAt(i)))
            i++;
        boolean neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        long v = 0;
        boolean overflow = false;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            v = v * 10 + (s.charAt(i) - '0');
            if (v > 0x80000000L)
                overflow = true;
            i++;
        }
        if (overflow || (!neg && v > Integer.MAX_VALUE))
            return neg ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        return (int) (neg ? -v : v);
    }

    static boolean isspace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 11;
    }

    private static final java.util.regex.Pattern FLOAT = java.util.regex.Pattern.compile(
            "^[ \\t\\n\\r\\f\\x0B]*([+-]?(?:(?:inf(?:inity)?|nan)|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?))",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static double atof(String s) {
        java.util.regex.Matcher mt = FLOAT.matcher(s);
        if (!mt.find())
            return 0;
        String t = mt.group(1).toLowerCase();
        boolean neg = t.startsWith("-");
        String body = t.replaceFirst("^[+-]", "");
        if (body.startsWith("inf"))
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (body.startsWith("nan"))
            return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================= dispatch =================

    /**
     * Runs one intrinsic. {@code a} holds its arguments as the VM passes them (32-bit
     * values sign-extended, floats as raw bits). Returns the result in the same form.
     */
    long exec(Intrinsics.Op op, long[] a) {
        switch (op) {
            case MALLOC:
                return heap.malloc(a[0], host.stackPointer(), host);
            case CALLOC:
                return heap.calloc(a[0], a[1], host.stackPointer(), host);
            case REALLOC:
                return heap.realloc(a[0], a[1], host.stackPointer(), host);
            case FREE:
                heap.free(a[0], host);
                return 0;
            case NEW:
                return heap.malloc(a[0], host.stackPointer(), host);
            case DELETE:
                heap.free(a[0], host);
                return 0;
            case MEMSET0: {
                long n = a[1] & 0xFFFFFFFFL;
                m.fill(a[0], 0, n);
                cost(CycleClass.STORE, n / 4 + 8);
                return 0;
            }
            default:
                return string(op, a);
        }
    }

    private long string(Intrinsics.Op op, long[] a) {
        long s = a[0] & 0xFFFFFFFFL;
        switch (op) {
            // ---- construction / destruction ----
            case STR_CTOR_EMPTY: {
                call(8);
                init(s);
                copy(s, emptyLiteral(), 0);
                return 0;
            }
            case STR_CTOR_ARRAY: {
                long n = a[1] & 0xFFFFFFFFL, stride = a[2] & 0xFFFFFFFFL;
                for (long i = 0; i < n; i++) {
                    call(8);
                    init(s + i * stride);
                    copy(s + i * stride, emptyLiteral(), 0);
                }
                return 0;
            }
            case STR_CTOR_CSTR:
                call(8);
                init(s);
                if (a[1] != 0)
                    copy(s, a[1] & 0xFFFFFFFFL, strlen(a[1] & 0xFFFFFFFFL));
                return 0;
            case STR_CTOR_COPY:
                call(8);
                init(s);
                assignCopy(s, a[1] & 0xFFFFFFFFL);
                return 0;
            case STR_CTOR_CSTR_LEN, STR_CTOR_BYTES_LEN: {
                call(8);
                init(s);
                long src = a[1] & 0xFFFFFFFFL, n = a[2] & 0xFFFFFFFFL;
                if (src != 0) {
                    if (!reserve(s, n)) {
                        invalidate(s);
                    } else {
                        setLen(s, n);
                        copyN(buf(s), src, n);
                    }
                }
                return 0;
            }
            case STR_IS_EMPTY:
                call(2);
                return len(s) == 0 ? 1 : 0;
            case STR_BEGIN:
                call(1);
                return buf(s);
            case STR_END:
                call(2);
                return (buf(s) + len(s)) & 0xFFFFFFFFL;
            case STR_CONCAT_CSTR_LEN, STR_CONCAT_BYTES_LEN: {
                call(6);
                long src = a[1] & 0xFFFFFFFFL, n = a[2] & 0xFFFFFFFFL;
                long newlen = (len(s) + n) & 0xFFFFFFFFL;
                if (src == 0)
                    return 0;
                if (n == 0)
                    return 1;
                if (!reserve(s, newlen))
                    return 0;
                copyN(buf(s) + len(s), src, n);
                setLen(s, newlen);
                return 1;
            }
            case STR_CTOR_MOVE:
                call(6);
                init(s);
                move(s, a[1] & 0xFFFFFFFFL);
                return 0;
            case STR_CTOR_CHAR: {
                call(8);
                init(s);
                byte[] b = new byte[] { (byte) a[1] };
                copyBytes(s, b[0] == 0 ? new byte[0] : b);
                return 0;
            }
            case STR_CTOR_UCHAR: {
                call(8);
                init(s);
                byte[] b = ultoa(a[1] & 0xFF, (int) (a[2] & 0xFF));
                numberCost(b);
                copyBytes(s, b);
                return 0;
            }
            case STR_CTOR_INT, STR_CTOR_LONG: {
                call(8);
                init(s);
                byte[] b = ltoa((int) a[1], (int) (a[2] & 0xFF));
                numberCost(b);
                copyBytes(s, b);
                return 0;
            }
            case STR_CTOR_UINT, STR_CTOR_ULONG: {
                call(8);
                init(s);
                byte[] b = ultoa(a[1], (int) (a[2] & 0xFF));
                numberCost(b);
                copyBytes(s, b);
                return 0;
            }
            case STR_CTOR_FLOAT: {
                call(8);
                init(s);
                int prec = (int) (a[2] & 0xFF);
                byte[] b = dtostrf(Float.intBitsToFloat((int) a[1]), prec + 2, prec);
                floatCost(prec);
                copyBytes(s, b);
                return 0;
            }
            case STR_CTOR_DOUBLE: {
                call(8);
                init(s);
                int prec = (int) (a[2] & 0xFF);
                byte[] b = dtostrf(Double.longBitsToDouble(a[1]), prec + 2, prec);
                floatCost(prec);
                copyBytes(s, b);
                return 0;
            }
            case STR_DTOR: {
                call(4);
                long b = buf(s);
                if (b != 0)
                    heap.free(b, host);
                // The object is dead; clearing it keeps a reused temporary's slot unchanged.
                m.write32(s, 0);
                m.write32(s + 4, 0);
                m.write32(s + 8, 0);
                return 0;
            }
            case STR_DTOR_ARRAY: {
                long n = a[1] & 0xFFFFFFFFL, stride = a[2] & 0xFFFFFFFFL;
                for (long i = n - 1; i >= 0; i--) {
                    long o = s + i * stride;
                    call(4);
                    long b = buf(o);
                    if (b != 0)
                        heap.free(b, host);
                    m.write32(o, 0);
                    m.write32(o + 4, 0);
                    m.write32(o + 8, 0);
                }
                return 0;
            }
            // ---- assignment ----
            case STR_ASSIGN_COPY:
                call(6);
                assignCopy(s, a[1] & 0xFFFFFFFFL);
                return s;
            case STR_ASSIGN_CSTR:
                call(6);
                assignCstr(s, a[1] & 0xFFFFFFFFL);
                return s;
            case STR_ASSIGN_MOVE:
                call(6);
                if (s != (a[1] & 0xFFFFFFFFL))
                    move(s, a[1] & 0xFFFFFFFFL);
                return s;
            // ---- capacity ----
            case STR_RESERVE:
                call(6);
                return reserve(s, a[1]) ? 1 : 0;
            case STR_LENGTH:
                call(2);
                return (int) len(s);
            // ---- concatenation ----
            case STR_CONCAT_STR:
                call(6);
                return concat(s, buf(a[1] & 0xFFFFFFFFL), len(a[1] & 0xFFFFFFFFL)) ? 1 : 0;
            case STR_CONCAT_CSTR: {
                call(6);
                long c = a[1] & 0xFFFFFFFFL;
                if (c == 0)
                    return 0;
                return concat(s, c, strlen(c)) ? 1 : 0;
            }
            case STR_CONCAT_CHAR:
                call(6);
                return concatBytes(s, new byte[] { (byte) a[1] }, 1) ? 1 : 0;
            case STR_CONCAT_UCHAR:
            case STR_CONCAT_INT:
            case STR_CONCAT_UINT:
            case STR_CONCAT_LONG:
            case STR_CONCAT_ULONG:
            case STR_CONCAT_FLOAT:
            case STR_CONCAT_DOUBLE: {
                call(6);
                byte[] b = formatOperand(op, a[1]);
                return concatBytes(s, b, b.length) ? 1 : 0;
            }
            case STR_SUM_STR:
            case STR_SUM_CSTR:
            case STR_SUM_CHAR:
            case STR_SUM_UCHAR:
            case STR_SUM_INT:
            case STR_SUM_UINT:
            case STR_SUM_LONG:
            case STR_SUM_ULONG:
            case STR_SUM_FLOAT:
            case STR_SUM_DOUBLE: {
                // operator+(const StringSumHelper &lhs, x): if (!a.concat(x)) a.invalidate();
                call(4);
                boolean ok;
                if (op == Intrinsics.Op.STR_SUM_STR) {
                    ok = concat(s, buf(a[1] & 0xFFFFFFFFL), len(a[1] & 0xFFFFFFFFL));
                } else if (op == Intrinsics.Op.STR_SUM_CSTR) {
                    long c = a[1] & 0xFFFFFFFFL;
                    ok = c != 0 && concat(s, c, strlen(c));
                } else if (op == Intrinsics.Op.STR_SUM_CHAR) {
                    ok = concatBytes(s, new byte[] { (byte) a[1] }, 1);
                } else {
                    byte[] b = formatOperand(op, a[1]);
                    ok = concatBytes(s, b, b.length);
                }
                if (!ok)
                    invalidate(s);
                return 0;
            }
            // ---- comparison ----
            case STR_EQUALS_STR: {
                call(4);
                long o = a[1] & 0xFFFFFFFFL;
                return len(s) == len(o) && compareTo(s, o) == 0 ? 1 : 0;
            }
            case STR_EQUALS_CSTR: {
                call(4);
                long c = a[1] & 0xFFFFFFFFL;
                if (len(s) == 0)
                    return c == 0 || m.read8(c) == 0 ? 1 : 0;
                if (c == 0)
                    return m.read8(buf(s)) == 0 ? 1 : 0;
                return strcmp(buf(s), c) == 0 ? 1 : 0;
            }
            case STR_EQUALS_IC: {
                call(6);
                long o = a[1] & 0xFFFFFFFFL;
                if (s == o)
                    return 1;
                if (len(s) != len(o))
                    return 0;
                if (len(s) == 0)
                    return 1;
                long p1 = buf(s), p2 = buf(o);
                long n = 0;
                while (true) {
                    int c1 = m.read8(p1 + n), c2 = m.read8(p2 + n);
                    n++;
                    if (c1 == 0) {
                        cost(CycleClass.LOAD, 4 * n);
                        return 1;
                    }
                    if (tolower(c1) != tolower(c2)) {
                        cost(CycleClass.LOAD, 4 * n);
                        return 0;
                    }
                }
            }
            case STR_COMPARE:
                call(4);
                return compareTo(s, a[1] & 0xFFFFFFFFL);
            case STR_STARTS: {
                call(4);
                long o = a[1] & 0xFFFFFFFFL;
                if (len(s) < len(o))
                    return 0;
                return startsWith(s, o, 0);
            }
            case STR_STARTS_AT:
                call(4);
                return startsWith(s, a[1] & 0xFFFFFFFFL, a[2] & 0xFFFFFFFFL);
            case STR_ENDS: {
                call(4);
                long o = a[1] & 0xFFFFFFFFL;
                if (len(s) < len(o) || buf(s) == 0 || buf(o) == 0)
                    return 0;
                return strcmp(buf(s) + len(s) - len(o), buf(o)) == 0 ? 1 : 0;
            }
            // ---- characters ----
            case STR_CHAR_AT: {
                call(2);
                long i = a[1] & 0xFFFFFFFFL;
                if (i >= len(s) || buf(s) == 0)
                    return 0;
                return m.read8(buf(s) + i);
            }
            case STR_SET_CHAR_AT: {
                call(2);
                long i = a[1] & 0xFFFFFFFFL;
                if (i < len(s))
                    m.write8(buf(s) + i, (int) a[2]);
                return 0;
            }
            case STR_INDEX: {
                call(2);
                long i = a[1] & 0xFFFFFFFFL;
                if (i >= len(s) || buf(s) == 0) {
                    m.write8(dummyChar, 0);
                    return (int) dummyChar;
                }
                return (int) (buf(s) + i);
            }
            case STR_GET_BYTES:
            case STR_TO_CHAR_ARRAY: {
                call(6);
                long dst = a[1] & 0xFFFFFFFFL, size = a[2] & 0xFFFFFFFFL, index = a[3] & 0xFFFFFFFFL;
                if (size == 0 || dst == 0)
                    return 0;
                if (index >= len(s)) {
                    m.write8(dst, 0);
                    return 0;
                }
                long n = size - 1;
                if (n > len(s) - index)
                    n = len(s) - index;
                // strncpy(buf, buffer + index, n): copies up to n, padding with NULs after a terminator.
                long src = buf(s) + index;
                boolean ended = false;
                for (long i = 0; i < n; i++) {
                    int c = ended ? 0 : m.read8(src + i);
                    if (c == 0)
                        ended = true;
                    m.write8(dst + i, c);
                }
                m.write8(dst + n, 0);
                cost(CycleClass.LOAD, 2 * n);
                cost(CycleClass.STORE, 2 * (n + 1));
                return 0;
            }
            case STR_C_STR:
                call(1);
                return (int) buf(s);
            case STR_VALID:
                call(1);
                return buf(s) != 0 ? 1 : 0;
            // ---- search ----
            case STR_INDEX_OF_CHAR:
                call(4);
                return indexOfChar(s, (int) a[1], 0);
            case STR_INDEX_OF_CHAR_FROM:
                call(4);
                return indexOfChar(s, (int) a[1], a[2] & 0xFFFFFFFFL);
            case STR_INDEX_OF_STR:
                call(4);
                return indexOfStr(s, a[1] & 0xFFFFFFFFL, 0);
            case STR_INDEX_OF_STR_FROM:
                call(4);
                return indexOfStr(s, a[1] & 0xFFFFFFFFL, a[2] & 0xFFFFFFFFL);
            case STR_LAST_INDEX_OF_CHAR:
                call(4);
                return lastIndexOfChar(s, (int) a[1], (len(s) - 1) & 0xFFFFFFFFL);
            case STR_LAST_INDEX_OF_CHAR_FROM:
                call(4);
                return lastIndexOfChar(s, (int) a[1], a[2] & 0xFFFFFFFFL);
            case STR_LAST_INDEX_OF_STR: {
                call(4);
                long o = a[1] & 0xFFFFFFFFL;
                return lastIndexOfStr(s, o, (len(s) - len(o)) & 0xFFFFFFFFL);
            }
            case STR_LAST_INDEX_OF_STR_FROM:
                call(4);
                return lastIndexOfStr(s, a[1] & 0xFFFFFFFFL, a[2] & 0xFFFFFFFFL);
            // ---- modification ----
            case STR_SUBSTRING:
            case STR_SUBSTRING2: {
                // a[0] is the result slot, a[1] this.
                long out = s;
                long self = a[1] & 0xFFFFFFFFL;
                long left = a[2] & 0xFFFFFFFFL;
                long right = op == Intrinsics.Op.STR_SUBSTRING ? len(self) : a[3] & 0xFFFFFFFFL;
                call(10);
                if (left > right) {
                    long t = left;
                    left = right;
                    right = t;
                }
                init(out);
                copy(out, emptyLiteral(), 0);
                if (left >= len(self))
                    return 0;
                if (right > len(self))
                    right = len(self);
                // out = buffer + left, with buffer[right] temporarily NUL: copy exactly right-left bytes.
                // The lengths are the object's own words, so a corrupted String can claim 4 GB;
                // the buffer grows with what is really read, and reading off the map faults.
                long b = buf(self);
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                for (long i = left; i < right; i++) {
                    int c = m.read8(b + i);
                    if (c == 0)
                        break;
                    bytes.write(c);
                }
                cost(CycleClass.LOAD, 2L * bytes.size());
                copyBytes(out, bytes.toByteArray());
                return 0;
            }
            case STR_REPLACE_CHAR: {
                call(4);
                long b = buf(s);
                if (b == 0)
                    return 0;
                int find = (int) a[1] & 0xFF, repl = (int) a[2] & 0xFF;
                long n = 0;
                for (long p = b; ; p++) {
                    int c = m.read8(p);
                    if (c == 0)
                        break;
                    if (c == find)
                        m.write8(p, repl);
                    n++;
                }
                cost(CycleClass.LOAD, 3 * n);
                return 0;
            }
            case STR_REPLACE_STR:
                call(10);
                replace(s, a[1] & 0xFFFFFFFFL, a[2] & 0xFFFFFFFFL);
                return 0;
            case STR_REMOVE:
                call(4);
                remove(s, a[1] & 0xFFFFFFFFL, (len(s) - (a[1] & 0xFFFFFFFFL)) & 0xFFFFFFFFL);
                return 0;
            case STR_REMOVE2:
                call(4);
                remove(s, a[1] & 0xFFFFFFFFL, a[2] & 0xFFFFFFFFL);
                return 0;
            case STR_TO_LOWER:
            case STR_TO_UPPER: {
                call(4);
                long b = buf(s);
                if (b == 0)
                    return 0;
                long n = 0;
                for (long p = b; ; p++) {
                    int c = m.read8(p);
                    if (c == 0)
                        break;
                    m.write8(p, op == Intrinsics.Op.STR_TO_LOWER ? tolower(c) : toupper(c));
                    n++;
                }
                cost(CycleClass.LOAD, 4 * n);
                cost(CycleClass.STORE, 2 * n);
                return 0;
            }
            case STR_TRIM: {
                call(6);
                long b = buf(s), l = len(s);
                if (b == 0 || l == 0)
                    return 0;
                long begin = b;
                while (isspace((char) m.read8(begin)))
                    begin++;
                long end = b + l - 1;
                while (isspace((char) m.read8(end)) && end >= begin)
                    end--;
                long nl = end + 1 - begin;
                setLen(s, nl);
                if (begin > b)
                    m.move(b, begin, nl);
                m.write8(b + nl, 0);
                cost(CycleClass.LOAD, 3 * l);
                return 0;
            }
            // ---- conversion ----
            case STR_TO_INT: {
                call(4);
                if (buf(s) == 0)
                    return 0;
                String t = cstring(buf(s));
                cost(CycleClass.ALU, 6L * t.length() + 20);
                return atol(t);
            }
            case STR_TO_FLOAT:
            case STR_TO_DOUBLE: {
                call(4);
                double v = buf(s) == 0 ? 0 : atof(cstring(buf(s)));
                cost(CycleClass.SOFT_FLOAT, 600);
                if (op == Intrinsics.Op.STR_TO_FLOAT)
                    return Float.floatToRawIntBits((float) v);
                return Double.doubleToRawLongBits(v);
            }
            default:
                throw new IllegalStateException("intrinsic " + op);
        }
    }

    private long emptyLiteral() {
        return emptyStr;
    }

    private byte[] formatOperand(Intrinsics.Op op, long v) {
        byte[] b;
        switch (op) {
            case STR_CONCAT_UCHAR, STR_SUM_UCHAR -> b = ultoa(v & 0xFF, 10);
            case STR_CONCAT_INT, STR_SUM_INT, STR_CONCAT_LONG, STR_SUM_LONG -> b = ltoa((int) v, 10);
            case STR_CONCAT_UINT, STR_SUM_UINT, STR_CONCAT_ULONG, STR_SUM_ULONG -> b = ultoa(v, 10);
            case STR_CONCAT_FLOAT, STR_SUM_FLOAT -> {
                b = dtostrf(Float.intBitsToFloat((int) v), 4, 2);
                floatCost(2);
                return b;
            }
            case STR_CONCAT_DOUBLE, STR_SUM_DOUBLE -> {
                b = dtostrf(Double.longBitsToDouble(v), 4, 2);
                floatCost(2);
                return b;
            }
            default -> throw new IllegalStateException(op.toString());
        }
        numberCost(b);
        return b;
    }

    private int compareTo(long s, long o) {
        long b = buf(s), ob = buf(o);
        if (b == 0 || ob == 0) {
            if (ob != 0 && len(o) > 0)
                return -m.read8(ob);
            if (b != 0 && len(s) > 0)
                return m.read8(b);
            return 0;
        }
        return strcmp(b, ob);
    }

    private long startsWith(long s, long o, long offset) {
        long diff = (len(s) - len(o)) & 0xFFFFFFFFL;
        if (offset > diff || buf(s) == 0 || buf(o) == 0)
            return 0;
        return strncmp(buf(s) + offset, buf(o), len(o)) == 0 ? 1 : 0;
    }

    private int indexOfChar(long s, int ch, long from) {
        if (from >= len(s))
            return -1;
        long t = strchr(buf(s) + from, ch);
        if (t == 0)
            return -1;
        return (int) (t - buf(s));
    }

    private int indexOfStr(long s, long o, long from) {
        if (from >= len(s))
            return -1;
        long ob = buf(o);
        if (ob == 0)
            return (int) from;
        long found = strstr(buf(s) + from, ob);
        if (found == 0)
            return -1;
        return (int) (found - buf(s));
    }

    private int lastIndexOfChar(long s, int ch, long from) {
        if (from >= len(s))
            return -1;
        // strrchr over buffer[0..from], as if buffer[from + 1] were NUL.
        long b = buf(s);
        int found = -1;
        for (long i = 0; i <= from; i++) {
            int c = m.read8(b + i);
            if (c == (ch & 0xFF))
                found = (int) i;
            if (c == 0)
                break;
        }
        cost(CycleClass.LOAD, 3 * (from + 1));
        return found;
    }

    private int lastIndexOfStr(long s, long o, long from) {
        long sl = len(s), ol = len(o);
        if (ol == 0 || sl == 0 || ol > sl)
            return -1;
        if (from >= sl)
            from = sl - 1;
        int found = -1;
        long b = buf(s);
        for (long p = b; p <= b + from; p++) {
            p = strstr(p, buf(o));
            if (p == 0)
                break;
            if (p - b <= from)
                found = (int) (p - b);
        }
        return found;
    }

    private void remove(long s, long index, long count) {
        long l = len(s);
        if (index >= l)
            return;
        if (count == 0)
            return;
        if (count > l - index)
            count = l - index;
        long b = buf(s);
        long nl = l - count;
        setLen(s, nl);
        // strncpy(writeTo, buffer + index + count, len - index); buffer[len] = 0;
        long n = nl - index;
        long src = b + index + count, dst = b + index;
        boolean ended = false;
        for (long i = 0; i < n; i++) {
            int c = ended ? 0 : m.read8(src + i);
            if (c == 0)
                ended = true;
            m.write8(dst + i, c);
        }
        m.write8(b + nl, 0);
        cost(CycleClass.LOAD, 2 * n);
        cost(CycleClass.STORE, 2 * n);
    }

    /** replace(const String& find, const String& replace), the AVR algorithm in all three cases. */
    private void replace(long s, long find, long repl) {
        long l = len(s), fl = len(find), rl = len(repl);
        if (l == 0 || fl == 0)
            return;
        long diff = rl - fl;
        long readFrom = buf(s);
        long foundAt;
        long fb = buf(find), rb = buf(repl);
        if (diff == 0) {
            while ((foundAt = strstr(readFrom, fb)) != 0) {
                m.move(foundAt, rb, rl);
                readFrom = foundAt + rl;
            }
        } else if (diff < 0) {
            long writeTo = buf(s);
            while ((foundAt = strstr(readFrom, fb)) != 0) {
                long n = foundAt - readFrom;
                m.move(writeTo, readFrom, n);
                writeTo += n;
                m.move(writeTo, rb, rl);
                writeTo += rl;
                readFrom = foundAt + fl;
                setLen(s, len(s) + diff);
            }
            strcpy(writeTo, readFrom);
        } else {
            long size = l;
            while ((foundAt = strstr(readFrom, fb)) != 0) {
                readFrom = foundAt + fl;
                size += diff;
            }
            if (size == l)
                return;
            if (size > cap(s) && !changeBuffer(s, size))
                return;
            long index = len(s) - 1;
            while (index >= 0 && (index = lastIndexOfStr(s, find, index)) >= 0) {
                long b = buf(s);
                readFrom = b + index + fl;
                long cl = len(s);
                m.move(readFrom + diff, readFrom, cl - (readFrom - b));
                setLen(s, cl + diff);
                m.write8(b + cl + diff, 0);
                m.move(b + index, rb, rl);
                index--;
            }
        }
    }

    static int tolower(int c) {
        return c >= 'A' && c <= 'Z' ? c + 32 : c;
    }

    static int toupper(int c) {
        return c >= 'a' && c <= 'z' ? c - 32 : c;
    }
}
