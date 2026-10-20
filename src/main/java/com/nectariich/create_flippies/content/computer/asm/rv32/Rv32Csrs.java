package com.nectariich.create_flippies.content.computer.asm.rv32;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CSR names the assembler accepts, with their numbers (spec section 4.4, from the RISC-V
 * privileged spec's CSR listing). The assembler keeps its own table so it does not depend on the
 * CPU package; the machine's CPU decides which of them exist.
 */
public final class Rv32Csrs {

    private static final Map<String, Integer> BY_NAME = new HashMap<>();

    static {
        put("mvendorid", 0xF11);
        put("marchid", 0xF12);
        put("mimpid", 0xF13);
        put("mhartid", 0xF14);
        put("mconfigptr", 0xF15);
        put("mstatus", 0x300);
        put("misa", 0x301);
        put("mie", 0x304);
        put("mtvec", 0x305);
        put("mstatush", 0x310);
        put("mcountinhibit", 0x320);
        put("mscratch", 0x340);
        put("mepc", 0x341);
        put("mcause", 0x342);
        put("mtval", 0x343);
        put("mip", 0x344);
        put("mcycle", 0xB00);
        put("minstret", 0xB02);
        put("mcycleh", 0xB80);
        put("minstreth", 0xB82);
        put("cycle", 0xC00);
        put("time", 0xC01);
        put("instret", 0xC02);
        put("cycleh", 0xC80);
        put("timeh", 0xC81);
        put("instreth", 0xC82);
        for (int n = 3; n <= 31; n++) {
            put("mhpmcounter" + n, 0xB00 + n);
            put("mhpmcounter" + n + "h", 0xB80 + n);
            put("mhpmevent" + n, 0x320 + n);
            put("hpmcounter" + n, 0xC00 + n);
            put("hpmcounter" + n + "h", 0xC80 + n);
        }
    }

    private static void put(String name, int number) {
        BY_NAME.put(name, number);
    }

    private Rv32Csrs() {
    }

    /** The CSR number for a name (case-insensitive), or -1. */
    public static int number(String name) {
        Integer n = BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return n == null ? -1 : n;
    }

    public static Map<String, Integer> all() {
        return Collections.unmodifiableMap(BY_NAME);
    }
}
