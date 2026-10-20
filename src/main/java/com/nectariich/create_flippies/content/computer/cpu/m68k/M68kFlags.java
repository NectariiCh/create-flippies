package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * Condition-code helpers shared by every instruction of the core (spec 10.2.1, 10.3.4): the
 * condition test table, BCD arithmetic with the fixed rule for its undefined flags, and the
 * shift and rotate unit. All pure functions over plain ints.
 */
final class M68kFlags {

    private M68kFlags() {
    }

    static final int X = M68kSr.X;
    static final int N = M68kSr.N;
    static final int Z = M68kSr.Z;
    static final int V = M68kSr.V;
    static final int C = M68kSr.C;

    /**
     * Condition tests (spec 10.2.1): bit {@code ccr & 15} of {@code COND[cc]} is set when
     * condition {@code cc} holds for those N, Z, V, C bits.
     */
    private static final int[] COND = new int[16];

    static {
        for (int cc = 0; cc < 16; cc++) {
            int bits = 0;
            for (int f = 0; f < 16; f++) {
                boolean n = (f & N) != 0, z = (f & Z) != 0, v = (f & V) != 0, c = (f & C) != 0;
                boolean t;
                switch (cc) {
                    case 0: t = true; break;
                    case 1: t = false; break;
                    case 2: t = !c && !z; break;
                    case 3: t = c || z; break;
                    case 4: t = !c; break;
                    case 5: t = c; break;
                    case 6: t = !z; break;
                    case 7: t = z; break;
                    case 8: t = !v; break;
                    case 9: t = v; break;
                    case 10: t = !n; break;
                    case 11: t = n; break;
                    case 12: t = n == v; break;
                    case 13: t = n != v; break;
                    case 14: t = n == v && !z; break;
                    default: t = z || n != v; break;
                }
                if (t)
                    bits |= 1 << f;
            }
            COND[cc] = bits;
        }
    }

    /** Whether condition {@code cc} (0..15) holds for {@code ccr}. */
    static boolean test(int cc, int ccr) {
        return ((COND[cc] >>> (ccr & 15)) & 1) != 0;
    }

    // ---- BCD (spec 10.3.4); results pack the byte in bits 7..0, C in bit 8 and V in bit 9 ----

    static final int BCD_C = 0x100;
    static final int BCD_V = 0x200;

    /**
     * ABCD: {@code d + s + x} in BCD, by the hardware-verified model of flamewing's
     * 68k-bcd-verifier (bcd-emul.cc). A digit is corrected by 6 when its binary add carried or
     * when the uncorrected binary sum shows a decimal carry (low digit above 9, whole byte at least
     * 0x9A). C is the binary carry or a carry out of the correction. V (undefined on the chip) is
     * fixed as: bit 7 of the uncorrected binary sum is 0 and bit 7 of the corrected result is 1.
     */
    static int abcd(int s, int d, int x) {
        int bin = (s + d + x) & 0xFF;
        int bc = ((s & d) | (~bin & s) | (~bin & d)) & 0x88;
        int dc = (((bin + 0x66) ^ bin) & 0x110) >> 1;
        int corf = (bc | dc) - ((bc | dc) >> 2);
        int res = (bin + corf) & 0xFF;
        int c = ((bc | (bin & ~res)) & 0x80) != 0 ? BCD_C : 0;
        int v = (bin & 0x80) == 0 && (res & 0x80) != 0 ? BCD_V : 0;
        return res | c | v;
    }

    /**
     * SBCD and NBCD (s from 0): {@code d - s - x} in BCD, by the same hardware-verified model. A
     * digit is corrected by 6 only when its binary subtract borrowed. C is the binary borrow or a
     * borrow out of the correction. V (undefined on the chip) is fixed as: bit 7 of the
     * uncorrected binary difference is 1 and bit 7 of the corrected result is 0.
     */
    static int sbcd(int s, int d, int x) {
        int bin = (d - s - x) & 0xFF;
        int bc = ((~d & s) | (bin & ~d) | (bin & s)) & 0x88;
        int corf = bc - (bc >> 2);
        int res = (bin - corf) & 0xFF;
        int c = ((bc | (~bin & res)) & 0x80) != 0 ? BCD_C : 0;
        int v = (bin & 0x80) != 0 && (res & 0x80) == 0 ? BCD_V : 0;
        return res | c | v;
    }

    // ---- shifts and rotates (spec 10.3.4) ----

    static final int AS = 0;
    static final int LS = 1;
    static final int ROX = 2;
    static final int RO = 3;

    /**
     * Shifts or rotates {@code value} at size {@code sz} (0 byte, 1 word, 2 long) by {@code n}
     * (0..63), {@code type} AS, LS, ROX or RO. Returns the result (zero-extended at the size) in
     * the low 32 bits and the whole new CCR in bits 36..32, from {@code ccr} as it was:
     * <ul>
     * <li>ASL: X = C = last bit out, V = 1 if the sign bit changed at any point; ASR: V = 0.</li>
     * <li>LSL, LSR: X = C = last bit out, V = 0.</li>
     * <li>ROL, ROR: C = last bit out, X unchanged.</li>
     * <li>ROXL, ROXR: rotate through X, X = C = last bit out.</li>
     * <li>Count 0: C = 0 (ROX: C = X), X unchanged, V = 0. NZ from the result always.</li>
     * </ul>
     */
    static long shift(int type, boolean left, int value, int n, int sz, int ccr) {
        final int bits = 8 << sz;
        final long mask = (1L << bits) - 1;
        final long u = value & mask;
        final long msb = 1L << (bits - 1);
        final int x = (ccr >> 4) & 1;
        long res = u;
        int c = 0;
        int v = 0;
        int newX = x;
        switch (type) {
            case AS:
                if (n == 0)
                    break;
                if (left) {
                    if (n < bits) {
                        res = (u << n) & mask;
                        c = (int) ((u >>> (bits - n)) & 1);
                        long top = ((1L << (n + 1)) - 1) << (bits - 1 - n);
                        long t = u & top;
                        v = t != 0 && t != top ? 1 : 0;
                    } else {
                        res = 0;
                        c = n == bits ? (int) (u & 1) : 0;
                        v = u != 0 ? 1 : 0;
                    }
                } else {
                    boolean neg = (u & msb) != 0;
                    if (n < bits) {
                        long sext = neg ? u | ~mask : u;
                        res = (sext >> n) & mask;
                        c = (int) ((u >>> (n - 1)) & 1);
                    } else {
                        res = neg ? mask : 0;
                        c = neg ? 1 : 0;
                    }
                }
                newX = c;
                break;
            case LS:
                if (n == 0)
                    break;
                if (n > bits) {
                    res = 0;
                    c = 0;
                } else if (left) {
                    res = (u << n) & mask;
                    c = (int) ((u >>> (bits - n)) & 1);
                } else {
                    res = u >>> n;
                    c = (int) ((u >>> (n - 1)) & 1);
                }
                newX = c;
                break;
            case ROX: {
                int k = n % (bits + 1);
                long m = (1L << (bits + 1)) - 1;
                long w = u | ((long) x << bits);
                if (k != 0)
                    w = left ? ((w << k) | (w >>> (bits + 1 - k))) & m : ((w >>> k) | (w << (bits + 1 - k))) & m;
                res = w & mask;
                c = (int) ((w >>> bits) & 1);
                newX = c;
                break;
            }
            default: {
                if (n == 0)
                    break;
                int k = n & (bits - 1);
                if (k != 0)
                    res = left ? ((u << k) | (u >>> (bits - k))) & mask : ((u >>> k) | (u << (bits - k))) & mask;
                c = left ? (int) (res & 1) : (int) ((res >>> (bits - 1)) & 1);
                break;
            }
        }
        int flags = (newX << 4) | ((res & msb) != 0 ? N : 0) | (res == 0 ? Z : 0) | (v << 1) | c;
        return ((long) flags << 32) | (res & 0xFFFF_FFFFL);
    }
}
