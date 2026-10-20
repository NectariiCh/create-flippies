package com.nectariich.create_flippies.content.computer.cpu.m68k;

/**
 * Bit field arithmetic of the 68040's BFxxx instructions (spec 10.3.4). Bit fields count from the
 * most significant bit: offset 0 is bit 31 of a register or bit 7 of the base byte. Width is
 * 1..32. A register field wraps from bit 0 round to bit 31; a memory field starts at byte
 * {@code base + floor(offset / 8)} and spans at most 5 bytes.
 */
final class Bitfield {

    private Bitfield() {
    }

    /** The low {@code width} bits set (width 1..32). */
    static int mask(int width) {
        return width == 32 ? -1 : (1 << width) - 1;
    }

    /** The field of {@code data} at {@code offset} (taken mod 32) and {@code width}, right-aligned. */
    static int extract(int data, int offset, int width) {
        int rot = Integer.rotateLeft(data, offset & 31);
        return width == 32 ? rot : rot >>> (32 - width);
    }

    /** {@code data} with its field at {@code offset} (mod 32) and {@code width} replaced by the low bits of {@code field}. */
    static int insert(int data, int offset, int width, int field) {
        int rot = Integer.rotateLeft(data, offset & 31);
        int hi = width == 32 ? -1 : -1 << (32 - width);
        int placed = width == 32 ? field : field << (32 - width);
        return Integer.rotateRight((rot & ~hi) | (placed & hi), offset & 31);
    }

    /** Bytes a memory field touches: {@code (bitOffset + width + 7) / 8} for bit offset 0..7 in its first byte. */
    static int spanBytes(int bitOffset, int width) {
        return (bitOffset + width + 7) >>> 3;
    }

    /** Sign-extends a right-aligned field of {@code width} bits. */
    static int signExtend(int field, int width) {
        return width == 32 ? field : (field << (32 - width)) >> (32 - width);
    }

    /** Index of the first set bit counting from the field's most significant bit, or {@code width} if none. */
    static int firstOne(int field, int width) {
        int f = field & mask(width);
        return f == 0 ? width : Integer.numberOfLeadingZeros(f) - (32 - width);
    }

    /** N and Z of a field: N = its most significant bit, Z = all zero; V and C are 0. */
    static int flags(int field, int width) {
        int f = field & mask(width);
        return (((f >>> (width - 1)) & 1) != 0 ? M68kSr.N : 0) | (f == 0 ? M68kSr.Z : 0);
    }
}
