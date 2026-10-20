package com.nectariich.create_flippies.content.computer.asm.m68k;

/**
 * Which chip the 68k assembler emits code for (spec 10.7.5). The 68000 target rejects every
 * 68040-only instruction, addressing mode and size; the {@code .cpu} directive switches the
 * target within a source.
 */
public enum M68kTarget {
    M68000,
    M68040
}
