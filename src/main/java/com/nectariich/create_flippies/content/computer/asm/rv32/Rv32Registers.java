package com.nectariich.create_flippies.content.computer.asm.rv32;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** RV32I register names: {@code x0}..{@code x31} and the ILP32 ABI names ({@code fp} is {@code s0}). */
public final class Rv32Registers {

    /** ABI names by register number (RISC-V psABI, "Integer Register Convention"). */
    private static final String[] ABI = {
            "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
            "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
            "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
            "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
    };

    private static final Map<String, Integer> BY_NAME = new HashMap<>();

    static {
        for (int i = 0; i < 32; i++) {
            BY_NAME.put("x" + i, i);
            BY_NAME.put(ABI[i], i);
        }
        BY_NAME.put("fp", 8);
    }

    private Rv32Registers() {
    }

    /** The register number for a name (case-insensitive), or -1. */
    public static int number(String name) {
        Integer n = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return n == null ? -1 : n;
    }

    /** The ABI name of register {@code n}. */
    public static String abiName(int n) {
        return ABI[n];
    }
}
