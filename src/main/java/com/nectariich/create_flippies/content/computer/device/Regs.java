package com.nectariich.create_flippies.content.computer.device;

/**
 * The narrow-access rules for 32-bit device registers (spec 1.2). A narrower access at offset
 * {@code o} reads or writes bits {@code 8 * (o & 3)} upward of the register at {@code o & ~3}.
 * Read side effects that capture state fire on the access touching byte 0; write side effects
 * fire on the access touching byte 3 (byte 0 for registers whose value is one byte). With 8086
 * port I/O splitting a 32-bit access into two 16-bit halves, low half first, this gives the same
 * result as one full access.
 */
final class Regs {

    private Regs() {
    }

    /** The part of 32-bit register value {@code reg} that an access of {@code width} at {@code offset} reads. */
    static int read(int reg, int offset, int width) {
        int v = reg >>> ((offset & 3) * 8);
        return width == 4 ? v : v & ((1 << (8 * width)) - 1);
    }

    /** {@code reg} with the bytes an access of {@code width} at {@code offset} writes replaced by {@code value}. */
    static int merge(int reg, int offset, int width, int value) {
        int shift = (offset & 3) * 8;
        int mask = width == 4 ? -1 : ((1 << (8 * width)) - 1) << shift;
        return (reg & ~mask) | ((value << shift) & mask);
    }

    /** The register bits a write sets, the rest 0; for write-1-to-clear registers. */
    static int written(int offset, int width, int value) {
        return merge(0, offset, width, value);
    }

    static boolean touchesByte0(int offset) {
        return (offset & 3) == 0;
    }

    static boolean touchesByte3(int offset, int width) {
        return (offset & 3) + width == 4;
    }
}
