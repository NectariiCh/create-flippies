package com.nectariich.create_flippies.content.plc.mcu.arduino;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.nectariich.create_flippies.content.plc.mcu.Blocker;
import com.nectariich.create_flippies.content.plc.mcu.CycleClass;
import com.nectariich.create_flippies.content.plc.mcu.HardFault;
import com.nectariich.create_flippies.content.plc.mcu.Invocation;
import com.nectariich.create_flippies.content.plc.mcu.Memory;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;

/**
 * The parts of newlib (nano) and STM32duino's avr-libc shims sketches routinely call:
 * memory and string functions, number parsing and formatting, abort().
 *
 * Every byte goes through the sketch's {@link Memory}, so overruns, writes into string
 * literals (flash: MemManage) and wild pointers fault exactly as on the chip, and a
 * {@code memcpy} between overlapping buffers copies forwards byte by byte, as
 * newlib-nano's size-optimised loop does. The heap is not ours: malloc and friends
 * belong to the language runtime.
 */
final class LibcBuiltins {

    private static final String DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int MAX_STRING = 0x10000;
    private static final long SRAM_END = Stm32f103cb.SRAM_BASE + Stm32f103cb.SRAM_SIZE;

    /**
     * Everything here but abort() and exit() is a loop over memory that newlib runs as
     * ordinary, interruptible code, so a long call is charged in pieces that interrupts
     * can come between (see {@link Preemptible}); short ones are charged as before.
     */
    static void register(List<Fn> out) {
        List<Fn> own = new ArrayList<>();
        registerOwn(own);
        for (Fn fn : own) {
            boolean ends = fn.name().equals("abort") || fn.name().equals("exit");
            out.add(ends ? fn : new Fn(fn.prototype(), fn.flashBytes(), Preemptible.wrap(fn.body())));
        }
    }

    private static void registerOwn(List<Fn> out) {
        out.add(new Fn("void* memset(void* s, int c, unsigned int n)", 28, c -> {
            long s = ptr(c, 0);
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Cost.call(c);
            Cost.bytes(c, n, 0, 1);
            for (long k = 0; k < n; k++)
                c.memory().write8(s + k, (int) c.arg(1) & 0xFF);
            c.returnValue(s);
        }));
        out.add(new Fn("void* memcpy(void* dest, const void* src, unsigned int n)", 28, c -> {
            long d = ptr(c, 0);
            long s = ptr(c, 1);
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Cost.call(c);
            Cost.bytes(c, n, 1, 1);
            for (long k = 0; k < n; k++)
                c.memory().write8(d + k, c.memory().read8(s + k));
            c.returnValue(d);
        }));
        out.add(new Fn("void* memmove(void* dest, const void* src, unsigned int n)", 52, c -> {
            long d = ptr(c, 0);
            long s = ptr(c, 1);
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Cost.call(c);
            c.charge(CycleClass.ALU, 4);
            Cost.bytes(c, n, 1, 1);
            Memory m = c.memory();
            if (d < s || d >= s + n) {
                for (long k = 0; k < n; k++)
                    m.write8(d + k, m.read8(s + k));
            } else {
                for (long k = n - 1; k >= 0; k--)
                    m.write8(d + k, m.read8(s + k));
            }
            c.returnValue(d);
        }));
        out.add(new Fn("int memcmp(const void* s1, const void* s2, unsigned int n)", 36, c -> {
            long a = ptr(c, 0);
            long b = ptr(c, 1);
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Cost.call(c);
            int result = 0;
            long k = 0;
            for (; k < n; k++) {
                int x = c.memory().read8(a + k);
                int y = c.memory().read8(b + k);
                if (x != y) {
                    result = x - y;
                    k++;
                    break;
                }
            }
            Cost.bytes(c, k, 2, 0);
            c.returnValue(result);
        }));
        out.add(new Fn("void* memchr(const void* s, int c, unsigned int n)", 28, c -> {
            long s = ptr(c, 0);
            int ch = (int) c.arg(1) & 0xFF;
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Cost.call(c);
            long found = 0;
            long k = 0;
            for (; k < n; k++) {
                if (c.memory().read8(s + k) == ch) {
                    found = s + k;
                    k++;
                    break;
                }
            }
            Cost.bytes(c, k, 1, 0);
            c.returnValue(found);
        }));

        out.add(new Fn("unsigned int strlen(const char* s)", 20, c -> {
            Cost.call(c);
            long n = strlen(c.memory(), ptr(c, 0));
            Cost.bytes(c, n + 1, 1, 0);
            c.returnValue(n);
        }));
        out.add(new Fn("char* strcpy(char* dest, const char* src)", 24, c -> {
            Cost.call(c);
            long n = copy(c.memory(), ptr(c, 0), ptr(c, 1), Long.MAX_VALUE);
            Cost.bytes(c, n + 1, 1, 1);
            c.returnValue(ptr(c, 0));
        }));
        out.add(new Fn("char* strncpy(char* dest, const char* src, unsigned int n)", 40, c -> {
            long d = ptr(c, 0);
            long s = ptr(c, 1);
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Cost.call(c);
            Memory m = c.memory();
            long k = 0;
            for (; k < n; k++) {
                int b = m.read8(s + k);
                m.write8(d + k, b);
                if (b == 0)
                    break;
            }
            for (; k < n; k++)
                m.write8(d + k, 0);
            Cost.bytes(c, n, 1, 1);
            c.returnValue(d);
        }));
        out.add(new Fn("char* strcat(char* dest, const char* src)", 40, c -> {
            Cost.call(c);
            long d = ptr(c, 0);
            long end = strlen(c.memory(), d);
            long n = copy(c.memory(), d + end, ptr(c, 1), Long.MAX_VALUE);
            Cost.bytes(c, end + 1, 1, 0);
            Cost.bytes(c, n + 1, 1, 1);
            c.returnValue(d);
        }));
        out.add(new Fn("char* strncat(char* dest, const char* src, unsigned int n)", 48, c -> {
            Cost.call(c);
            long d = ptr(c, 0);
            long s = ptr(c, 1);
            long n = c.arg(2) & 0xFFFF_FFFFL;
            Memory m = c.memory();
            long end = strlen(m, d);
            long k = 0;
            for (; k < n; k++) {
                int b = m.read8(s + k);
                if (b == 0)
                    break;
                m.write8(d + end + k, b);
            }
            m.write8(d + end + k, 0);
            Cost.bytes(c, end + 1, 1, 0);
            Cost.bytes(c, k + 1, 1, 1);
            c.returnValue(d);
        }));
        out.add(new Fn("int strcmp(const char* s1, const char* s2)", 40, c -> {
            Cost.call(c);
            c.returnValue(compare(c, ptr(c, 0), ptr(c, 1), Long.MAX_VALUE, false));
        }));
        out.add(new Fn("int strncmp(const char* s1, const char* s2, unsigned int n)", 48, c -> {
            Cost.call(c);
            c.returnValue(compare(c, ptr(c, 0), ptr(c, 1), c.arg(2) & 0xFFFF_FFFFL, false));
        }));
        out.add(new Fn("int strcasecmp(const char* s1, const char* s2)", 60, c -> {
            Cost.call(c);
            c.returnValue(compare(c, ptr(c, 0), ptr(c, 1), Long.MAX_VALUE, true));
        }));
        out.add(new Fn("int strncasecmp(const char* s1, const char* s2, unsigned int n)", 72, c -> {
            Cost.call(c);
            c.returnValue(compare(c, ptr(c, 0), ptr(c, 1), c.arg(2) & 0xFFFF_FFFFL, true));
        }));
        out.add(new Fn("char* strchr(const char* s, int c)", 32, c -> {
            Cost.call(c);
            long s = ptr(c, 0);
            int ch = (int) c.arg(1) & 0xFF;
            Memory m = c.memory();
            long k = 0;
            long found = 0;
            while (true) {
                int b = m.read8(s + k);
                if (b == ch) {
                    found = s + k;
                    break;
                }
                if (b == 0)
                    break;
                k++;
            }
            Cost.bytes(c, k + 1, 1, 0);
            c.returnValue(found);
        }));
        out.add(new Fn("char* strrchr(const char* s, int c)", 40, c -> {
            Cost.call(c);
            long s = ptr(c, 0);
            int ch = (int) c.arg(1) & 0xFF;
            Memory m = c.memory();
            long k = 0;
            long found = 0;
            while (true) {
                int b = m.read8(s + k);
                if (b == ch)
                    found = s + k;
                if (b == 0)
                    break;
                k++;
            }
            Cost.bytes(c, k + 1, 1, 0);
            c.returnValue(found);
        }));
        out.add(new Fn("char* strstr(const char* haystack, const char* needle)", 80, LibcBuiltins::strstr));
        out.add(new Fn("char* strtok(char* str, const char* delim)", 140, LibcBuiltins::strtok));

        out.add(new Fn("int atoi(const char* nptr)", 360, c -> c.returnValue((int) strtol(c, ptr(c, 0), 0, 10, true))));
        out.add(new Fn("long atol(const char* nptr)", 360, c -> c.returnValue((int) strtol(c, ptr(c, 0), 0, 10, true))));
        out.add(new Fn("long strtol(const char* nptr, char** endptr, int base)", 340,
                c -> c.returnValue((int) strtol(c, ptr(c, 0), ptr(c, 1), (int) c.arg(2), true))));
        out.add(new Fn("unsigned long strtoul(const char* nptr, char** endptr, int base)", 320,
                c -> c.returnValue(strtol(c, ptr(c, 0), ptr(c, 1), (int) c.arg(2), false) & 0xFFFF_FFFFL)));
        out.add(new Fn("double atof(const char* nptr)", 6200, c -> c.returnDouble(strtod(c, ptr(c, 0), 0))));
        out.add(new Fn("double strtod(const char* nptr, char** endptr)", 6200,
                c -> c.returnDouble(strtod(c, ptr(c, 0), ptr(c, 1)))));
        out.add(new Fn("float strtof(const char* nptr, char** endptr)", 6200,
                c -> c.returnFloat((float) strtod(c, ptr(c, 0), ptr(c, 1)))));

        out.add(new Fn("char* itoa(int value, char* str, int base)", 120,
                c -> toAscii(c, (int) c.arg(0), ptr(c, 1), (int) c.arg(2), true)));
        out.add(new Fn("char* ltoa(long value, char* str, int base)", 120,
                c -> toAscii(c, (int) c.arg(0), ptr(c, 1), (int) c.arg(2), true)));
        out.add(new Fn("char* utoa(unsigned int value, char* str, int base)", 100,
                c -> toAscii(c, c.arg(0) & 0xFFFF_FFFFL, ptr(c, 1), (int) c.arg(2), false)));
        out.add(new Fn("char* ultoa(unsigned long value, char* str, int base)", 100,
                c -> toAscii(c, c.arg(0) & 0xFFFF_FFFFL, ptr(c, 1), (int) c.arg(2), false)));
        out.add(new Fn("char* dtostrf(double val, signed char width, unsigned char prec, char* sout)", 3400,
                LibcBuiltins::dtostrf));

        out.add(new Fn("int sprintf(char* str, const char* format, ...)", 2900, c -> {
            long dest = ptr(c, 0);
            String fmt = c.memory().readCString(ptr(c, 1), MAX_STRING);
            // Unbounded, but a write cannot get past the end of SRAM without faulting there:
            // keep one byte more than fits, so a text that is too long faults where it would.
            int cap = dest >= Stm32f103cb.SRAM_BASE && dest < SRAM_END ? (int) (SRAM_END - dest) + 1 : 1;
            Printf.Result r = Printf.format(c, fmt, 2, cap);
            // a text that does not fit faults once it reaches the end, not after it is all out
            Printf.charge(c, fmt.length(), r.length() < cap ? r
                    : new Printf.Result(r.bytes(), cap, r.conversions(), r.digits()));
            write(c.memory(), dest, r.bytes(), r.bytes().length);
            c.returnValue(r.returned());
        }));
        out.add(new Fn("int snprintf(char* str, unsigned int size, const char* format, ...)", 2950, c -> {
            long dest = ptr(c, 0);
            long size = c.arg(1) & 0xFFFF_FFFFL;
            String fmt = c.memory().readCString(ptr(c, 2), MAX_STRING);
            int cap = (int) Math.min(Math.max(0, size - 1), Stm32f103cb.SRAM_SIZE + 1L);
            Printf.Result r = Printf.format(c, fmt, 3, cap);
            Printf.charge(c, fmt.length(), r);
            if (size > 0)
                write(c.memory(), dest, r.bytes(), r.bytes().length);
            c.returnValue(r.returned());
        }));

        out.add(new Fn("void abort(void)", 60, c -> {
            Cost.call(c);
            throw new HardFault(HardFault.Kind.ABORT, -1, "abort() called");
        }));
        out.add(new Fn("void exit(int status)", 120, LibcBuiltins::exit));
        // part of qsort's code (see Prelude), so no flash of its own
        out.add(new Fn("void __qsort_swap(char* a, char* b, unsigned int n, int swaptype)", 0, LibcBuiltins::qsortSwap));
    }

    /**
     * newlib qsort's swap macros, inlined there: swaptype 0 swaps one long in place;
     * 1 is swapcode(long) over {@code n / 4} words, 2 swapcode(char) over {@code n}
     * bytes (an unaligned base or an element size that is not a multiple of 4). Each
     * element is LDR, LDR, STR, STR, SUBS and the branch back; at least one element is
     * swapped, as the do/while does.
     */
    private static void qsortSwap(Invocation c) {
        long a = ptr(c, 0);
        long b = ptr(c, 1);
        long n = c.arg(2) & 0xFFFF_FFFFL;
        int swaptype = (int) c.arg(3);
        Memory m = c.memory();
        c.charge(CycleClass.ALU, 2);
        c.charge(CycleClass.BRANCH, 1);
        if (swaptype == 0) {
            int t = m.read32(a);
            m.write32(a, m.read32(b));
            m.write32(b, t);
            c.charge(CycleClass.LOAD, 4);
            c.charge(CycleClass.STORE, 2);
            return;
        }
        int width = swaptype <= 1 ? 4 : 1;
        long count = Math.max(1, n / width);
        for (long k = 0; k < count; k++) {
            long x = a + k * width;
            long y = b + k * width;
            if (width == 4) {
                int t = m.read32(x);
                m.write32(x, m.read32(y));
                m.write32(y, t);
            } else {
                int t = m.read8(x);
                m.write8(x, m.read8(y));
                m.write8(y, t);
            }
        }
        c.charge(CycleClass.LOAD, 4 * count);
        c.charge(CycleClass.STORE, 2 * count);
        c.charge(CycleClass.ALU, count);
        c.charge(CycleClass.BRANCH, 3 * count);
    }

    /**
     * exit(): newlib runs the atexit handlers and static destructors (a sketch has none
     * registered), then {@code _exit}, which STM32duino's syscalls.c makes
     * {@code for (;;) ;}. Unlike abort() that is not a fault: the CPU keeps running
     * that loop with interrupts on, so ISRs still run and outputs keep whatever they
     * were last given. The loop is spent as WAIT, like delay()'s busy wait.
     */
    private static void exit(Invocation c) {
        Cost.call(c);
        Cost.call(c); // __call_exitprocs
        Cost.mix(c, 12, 8, 2, 6, 0);
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
    }

    static long ptr(Invocation c, int index) {
        return c.arg(index) & 0xFFFF_FFFFL;
    }

    static long strlen(Memory m, long s) {
        long n = 0;
        while (m.read8(s + n) != 0)
            n++;
        return n;
    }

    /** Copies a C string, NUL included, at most {@code max} characters; returns the characters copied. */
    private static long copy(Memory m, long dest, long src, long max) {
        long k = 0;
        while (k < max) {
            int b = m.read8(src + k);
            m.write8(dest + k, b);
            if (b == 0)
                return k;
            k++;
        }
        return k;
    }

    /** Writes {@code count} bytes and a NUL. */
    static void write(Memory m, long dest, byte[] bytes, int count) {
        for (int k = 0; k < count; k++)
            m.write8(dest + k, bytes[k] & 0xFF);
        m.write8(dest + count, 0);
    }

    private static int compare(Invocation c, long a, long b, long n, boolean folded) {
        Memory m = c.memory();
        long k = 0;
        int result = 0;
        for (; k < n; k++) {
            int x = m.read8(a + k);
            int y = m.read8(b + k);
            if (folded) {
                x = CharBuiltins.toLower(x);
                y = CharBuiltins.toLower(y);
            }
            if (x != y) {
                result = x - y;
                break;
            }
            if (x == 0)
                break;
        }
        Cost.bytes(c, Math.min(k + 1, n), folded ? 4 : 2, 0);
        return result;
    }

    /**
     * newlib-nano's strstr, which is built for size and so is the naive search: at
     * each haystack position compare the needle until it ends (found) or a byte
     * differs, then move on one. Its time is the number of byte comparisons, up to
     * O(n·m), and that is what it is charged.
     *
     * The host must not do that much work, though: one call on a 10 KB haystack and a
     * 5 KB needle is 5·10^7 comparisons. So the comparisons are counted, not made: the
     * extended Z-algorithm gives, for every position, the length of the needle prefix
     * that matches there in O(n + m) total, and the naive loop at that position makes
     * exactly that many comparisons plus the one that ends it. The haystack is read in
     * order and no further than the naive loop would read it, so an unterminated
     * haystack faults (or not) where it would on the chip.
     */
    private static void strstr(Invocation c) {
        Cost.call(c);
        Memory m = c.memory();
        long h = ptr(c, 0);
        byte[] needle = m.readCString(ptr(c, 1), MAX_STRING).getBytes(StandardCharsets.ISO_8859_1);
        int len = needle.length;
        int[] z = zArray(needle);
        Haystack hay = new Haystack(m, h);
        long compares = 0;
        long positions = 0;
        long found = 0;
        // [l, r): the rightmost window known to match the needle's prefix
        int l = 0;
        int r = 0;
        for (int p = 0; ; p++) {
            int first = hay.at(p);
            positions++;
            if (first == 0) {
                // newlib: an empty haystack matches only an empty needle
                if (p == 0 && len == 0)
                    found = h;
                break;
            }
            int match;
            if (p < r && z[p - l] < r - p) {
                match = z[p - l];
            } else {
                match = Math.max(0, r - p);
                while (match < len && hay.at(p + match) == (needle[match] & 0xFF))
                    match++;
                l = p;
                r = p + match;
            }
            compares += match + 1;
            if (match == len) {
                found = h + p;
                break;
            }
        }
        // per comparison: LDRB needle[i], CBZ, LDRB haystack[i], CMP/BNE, ADDS, B back;
        // per position: LDRB *haystack, CBZ, ADDS, B back
        Cost.bytes(c, compares, 2, 0);
        Cost.bytes(c, positions, 1, 0);
        c.returnValue(found);
    }

    /** z[k] = the length of the longest common prefix of {@code s} and {@code s[k..]}; z[0] = length. */
    private static int[] zArray(byte[] s) {
        int n = s.length;
        int[] z = new int[Math.max(1, n)];
        z[0] = n;
        int l = 0;
        int r = 0;
        for (int k = 1; k < n; k++) {
            int v = k < r ? Math.min(r - k, z[k - l]) : 0;
            while (k + v < n && s[v] == s[k + v])
                v++;
            z[k] = v;
            if (k + v > r) {
                l = k;
                r = k + v;
            }
        }
        return z;
    }

    /** A C string read from the bus lazily, in order, each byte once. */
    private static final class Haystack {

        private final Memory memory;
        private final long base;
        private byte[] bytes = new byte[64];
        private int read;

        Haystack(Memory memory, long base) {
            this.memory = memory;
            this.base = base;
        }

        int at(int index) {
            while (read <= index) {
                if (read == bytes.length)
                    bytes = Arrays.copyOf(bytes, bytes.length * 2);
                bytes[read] = (byte) memory.read8(base + read);
                read++;
            }
            return bytes[index] & 0xFF;
        }
    }

    /** newlib's strtok_r over the core's saved pointer. */
    private static void strtok(Invocation c) {
        Cost.call(c);
        CoreState core = CoreState.of(c);
        Memory m = c.memory();
        long s = ptr(c, 0);
        String delim = m.readCString(ptr(c, 1), MAX_STRING);
        if (s == 0) {
            s = core.strtokNext;
            if (s == 0) {
                c.returnValue(0);
                return;
            }
        }
        long scanned = 0;
        int ch;
        do {
            ch = m.read8(s++);
            scanned++;
        } while (ch != 0 && delim.indexOf((char) ch) >= 0);
        if (ch == 0) {
            core.strtokNext = 0;
            Cost.bytes(c, scanned * (delim.length() + 1), 1, 0);
            c.returnValue(0);
            return;
        }
        long token = s - 1;
        while (true) {
            ch = m.read8(s++);
            scanned++;
            if (ch == 0) {
                core.strtokNext = 0;
                break;
            }
            if (delim.indexOf((char) ch) >= 0) {
                m.write8(s - 1, 0);
                core.strtokNext = s;
                break;
            }
        }
        Cost.bytes(c, scanned * (delim.length() + 1), 1, 0);
        c.noteSideEffect();
        c.returnValue(token);
    }

    /**
     * newlib's _strtol_r / _strtoul_r: leading space, a sign, a 0x prefix in base 0 or
     * 16, base 0 choosing 8 or 10; on overflow LONG_MAX/LONG_MIN (ULONG_MAX) and the
     * end pointer still past every digit. strtoul negates its result in unsigned
     * arithmetic, as C requires.
     */
    private static long strtol(Invocation c, long nptr, long endptr, int base, boolean signed) {
        Memory m = c.memory();
        Cost.call(c);
        Cost.mix(c, 12, 4, 0, 6, 0);
        long s = nptr;
        int ch;
        do {
            ch = m.read8(s++);
        } while (CharBuiltins.isSpace(ch));
        boolean neg = false;
        if (ch == '-') {
            neg = true;
            ch = m.read8(s++);
        } else if (ch == '+') {
            ch = m.read8(s++);
        }
        if (base < 0 || base == 1 || base > 36) {
            if (endptr != 0)
                m.write32(endptr, (int) nptr);
            return 0;
        }
        if ((base == 0 || base == 16) && ch == '0') {
            int x = m.read8(s);
            if (x == 'x' || x == 'X') {
                ch = m.read8(s + 1);
                s += 2;
                base = 16;
            }
        }
        if (base == 0)
            base = ch == '0' ? 8 : 10;
        long limit = signed ? (neg ? 0x8000_0000L : 0x7FFF_FFFFL) : 0xFFFF_FFFFL;
        long acc = 0;
        int any = 0;
        long digits = 0;
        for (;; ch = m.read8(s++)) {
            int d;
            if (ch >= '0' && ch <= '9')
                d = ch - '0';
            else if (ch >= 'A' && ch <= 'Z')
                d = ch - 'A' + 10;
            else if (ch >= 'a' && ch <= 'z')
                d = ch - 'a' + 10;
            else
                break;
            if (d >= base)
                break;
            digits++;
            if (any < 0)
                continue;
            acc = acc * base + d;
            if (acc > limit)
                any = -1;
            else
                any = 1;
        }
        c.charge(CycleClass.MUL, digits);
        Cost.bytes(c, digits + 1, 1, 0);
        c.charge(CycleClass.ALU, 6 * digits);
        long result;
        if (any < 0)
            result = signed ? (neg ? Integer.MIN_VALUE : Integer.MAX_VALUE) : 0xFFFF_FFFFL;
        else
            result = neg ? -acc : acc;
        if (endptr != 0)
            m.write32(endptr, (int) (any != 0 ? s - 1 : nptr));
        return result;
    }

    /**
     * newlib's strtod: space, sign, then decimal digits with an optional point and
     * exponent, a hexadecimal significand with an optional binary exponent, or
     * inf/infinity/nan. Overflow gives ±HUGE_VAL (infinity).
     */
    private static double strtod(Invocation c, long nptr, long endptr) {
        Memory m = c.memory();
        String text = m.readCString(nptr, 512);
        int i = 0;
        int n = text.length();
        while (i < n && CharBuiltins.isSpace(text.charAt(i)))
            i++;
        int start = i;
        if (i < n && (text.charAt(i) == '+' || text.charAt(i) == '-'))
            i++;
        String rest = text.substring(i).toLowerCase();
        double value;
        int end;
        if (rest.startsWith("inf")) {
            end = i + (rest.startsWith("infinity") ? 8 : 3);
            value = Double.POSITIVE_INFINITY;
        } else if (rest.startsWith("nan")) {
            end = i + 3;
            value = Double.NaN;
        } else if (rest.startsWith("0x") && rest.length() > 2 && isHexStart(rest, 2)) {
            int j = 2;
            while (j < rest.length() && Character.digit(rest.charAt(j), 16) >= 0) j++;
            if (j < rest.length() && rest.charAt(j) == '.') {
                j++;
                while (j < rest.length() && Character.digit(rest.charAt(j), 16) >= 0) j++;
            }
            String mantissa = rest.substring(0, j);
            String exponent = "p0";
            if (j < rest.length() && rest.charAt(j) == 'p') {
                int k = j + 1;
                if (k < rest.length() && (rest.charAt(k) == '+' || rest.charAt(k) == '-')) k++;
                int digitsFrom = k;
                while (k < rest.length() && Character.isDigit(rest.charAt(k))) k++;
                if (k > digitsFrom) {
                    exponent = rest.substring(j, k);
                    j = k;
                }
            }
            end = i + j;
            value = Double.parseDouble(mantissa + exponent);
        } else {
            int j = 0;
            int mantissaDigits = 0;
            while (j < rest.length() && Character.isDigit(rest.charAt(j))) { j++; mantissaDigits++; }
            if (j < rest.length() && rest.charAt(j) == '.') {
                j++;
                while (j < rest.length() && Character.isDigit(rest.charAt(j))) { j++; mantissaDigits++; }
            }
            if (mantissaDigits == 0) {
                if (endptr != 0)
                    m.write32(endptr, (int) nptr);
                Cost.call(c);
                Cost.softFloat(c, 120);
                return 0;
            }
            int mantissaEnd = j;
            if (j < rest.length() && rest.charAt(j) == 'e') {
                int k = j + 1;
                if (k < rest.length() && (rest.charAt(k) == '+' || rest.charAt(k) == '-')) k++;
                int digitsFrom = k;
                while (k < rest.length() && Character.isDigit(rest.charAt(k))) k++;
                if (k > digitsFrom)
                    j = k;
            }
            String number = rest.substring(0, j);
            if (mantissaEnd == 1 && number.charAt(0) == '.')
                number = "0" + number;
            value = Double.parseDouble(number.endsWith(".") ? number + "0" : number);
            end = i + j;
        }
        if (start < n && text.charAt(start) == '-')
            value = -value;
        if (endptr != 0)
            m.write32(endptr, (int) (nptr + end));
        // gdtoa: a multiprecision path for every conversion; about 600 cycles plus 60 per character
        Cost.call(c);
        Cost.softFloat(c, 600 + 60 * end);
        Cost.bytes(c, end, 1, 0);
        return value;
    }

    private static boolean isHexStart(String s, int at) {
        char ch = s.charAt(at);
        return Character.digit(ch, 16) >= 0
                || (ch == '.' && at + 1 < s.length() && Character.digit(s.charAt(at + 1), 16) >= 0);
    }

    /**
     * itoa/ltoa (signed, a minus sign only in base 10) and utoa/ultoa. A base outside
     * 2..36 returns NULL; newlib's itoa also clears the string first.
     */
    private static void toAscii(Invocation c, long value, long str, int base, boolean signed) {
        Cost.call(c);
        Memory m = c.memory();
        if (base < 2 || base > 36) {
            if (str != 0)
                m.write8(str, 0);
            c.returnValue(0);
            return;
        }
        StringBuilder sb = new StringBuilder();
        long v;
        if (signed && base == 10 && value < 0) {
            sb.append('-');
            v = -value & 0xFFFF_FFFFL;
        } else {
            v = value & 0xFFFF_FFFFL;
        }
        String digits = Long.toString(v, base);
        sb.append(digits);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        c.charge(CycleClass.DIV, 8L * digits.length());
        c.charge(CycleClass.MUL, digits.length());
        Cost.bytes(c, bytes.length * 2L, 1, 1); // built reversed, then swapped in place
        write(m, str, bytes, bytes.length);
        c.returnValue(str);
    }

    /**
     * STM32duino's dtostrf (avr/dtostrf.c), which avoids printf's missing float support:
     * add half a unit in the last place, split into an unsigned long integer part and a
     * fraction scaled to {@code prec} digits, print both with {@code %ld}, then pad to
     * {@code |width|} on the left (or, for a negative width, the right).
     *
     * The integer part is converted by __aeabi_d2uiz (which saturates) and then printed
     * with %ld, so values of 2^31 and up come out negative, as on the board.
     */
    private static void dtostrf(Invocation c) {
        double val = c.argDouble(0);
        int width = (byte) c.arg(1);
        int prec = (int) (c.arg(2) & 0xFF);
        long sout = ptr(c, 3);
        Cost.call(c);
        boolean negative = false;
        if (val < 0.0) {
            negative = true;
            val = -val;
        }
        double rounding = 0.5;
        for (int i = 0; i < prec; i++)
            rounding /= 10.0;
        val += rounding;
        long intPart = toUnsigned32(val);
        double remainder = val - (double) intPart;
        String text;
        if (prec > 0) {
            double decade = 1.0;
            for (int i = 0; i < prec; i++)
                decade *= 10.0;
            remainder *= decade;
            int decPart = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, remainder));
            String frac = Integer.toString(decPart);
            if (decPart >= 0)
                while (frac.length() < prec)
                    frac = "0" + frac;
            text = (negative ? "-" : "") + (int) intPart + "." + frac;
        } else {
            text = (negative ? "-" : "") + (int) intPart;
        }
        int w = width < 0 ? -width : width;
        boolean leftAdjust = width < 0;
        if (text.length() < w) {
            String pad = " ".repeat(w - text.length());
            text = leftAdjust ? text + pad : pad + text;
        }
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        // dcmpgt, (prec) ddiv, dadd, d2uiz, ui2d, dsub, (prec) dmul, dmul, d2iz, then sprintf
        Cost.softFloat(c, 25 + 220 * prec + 100 + 60 + 60 + 100 + 90 * prec + 90 + 60);
        Printf.charge(c, 10, new Printf.Result(bytes, bytes.length, 2, bytes.length));
        write(c.memory(), sout, bytes, bytes.length);
        c.returnValue(sout);
    }

    /** __aeabi_d2uiz: truncates, saturating at 0 and 0xFFFFFFFF; NaN gives 0. */
    private static long toUnsigned32(double v) {
        if (Double.isNaN(v) || v <= 0)
            return 0;
        if (v >= 4294967295.0)
            return 0xFFFF_FFFFL;
        return (long) v;
    }

    private LibcBuiltins() {}
}
