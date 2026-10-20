package com.nectariich.create_flippies.content.computer.cpu.rv32;

/**
 * The CSR numbers the RV32I core implements (spec 4.4), with their names for debuggers and
 * messages. Numbers are from the RISC-V privileged spec, table "Currently allocated RISC-V
 * machine-level CSR addresses". The hardwired-zero performance counter ranges are given by their
 * first and last numbers.
 */
public final class Rv32Csr {

    private Rv32Csr() {
    }

    // ---- machine information, read-only ----

    public static final int MVENDORID = 0xF11;
    public static final int MARCHID = 0xF12;
    public static final int MIMPID = 0xF13;
    public static final int MHARTID = 0xF14;
    public static final int MCONFIGPTR = 0xF15;

    // ---- machine trap setup and handling ----

    public static final int MSTATUS = 0x300;
    public static final int MISA = 0x301;
    public static final int MIE = 0x304;
    public static final int MTVEC = 0x305;
    public static final int MSTATUSH = 0x310;
    public static final int MCOUNTINHIBIT = 0x320;
    public static final int MSCRATCH = 0x340;
    public static final int MEPC = 0x341;
    public static final int MCAUSE = 0x342;
    public static final int MTVAL = 0x343;
    public static final int MIP = 0x344;

    // ---- machine counters ----

    public static final int MCYCLE = 0xB00;
    public static final int MINSTRET = 0xB02;
    public static final int MCYCLEH = 0xB80;
    public static final int MINSTRETH = 0xB82;
    /** mhpmcounter3..31; read 0, writes ignored. */
    public static final int MHPMCOUNTER3 = 0xB03;
    public static final int MHPMCOUNTER31 = 0xB1F;
    /** mhpmcounter3h..31h; read 0, writes ignored. */
    public static final int MHPMCOUNTER3H = 0xB83;
    public static final int MHPMCOUNTER31H = 0xB9F;
    /** mhpmevent3..31; read 0, writes ignored. */
    public static final int MHPMEVENT3 = 0x323;
    public static final int MHPMEVENT31 = 0x33F;

    // ---- user-level read-only shadows (Zicntr) ----

    public static final int CYCLE = 0xC00;
    public static final int TIME = 0xC01;
    public static final int INSTRET = 0xC02;
    public static final int CYCLEH = 0xC80;
    public static final int TIMEH = 0xC81;
    public static final int INSTRETH = 0xC82;
    /** hpmcounter3..31; read-only 0. */
    public static final int HPMCOUNTER3 = 0xC03;
    public static final int HPMCOUNTER31 = 0xC1F;
    /** hpmcounter3h..31h; read-only 0. */
    public static final int HPMCOUNTER3H = 0xC83;
    public static final int HPMCOUNTER31H = 0xC9F;

    /** Whether {@code csr} is read-only by its number: address bits 11..10 are 0b11 (privileged spec 2.1). */
    public static boolean readOnly(int csr) {
        return (csr >>> 10 & 3) == 3;
    }

    /** True for the hardwired-zero performance counter and event numbers. */
    public static boolean hardwiredZero(int csr) {
        return in(csr, MHPMCOUNTER3, MHPMCOUNTER31) || in(csr, MHPMCOUNTER3H, MHPMCOUNTER31H)
                || in(csr, MHPMEVENT3, MHPMEVENT31) || in(csr, HPMCOUNTER3, HPMCOUNTER31)
                || in(csr, HPMCOUNTER3H, HPMCOUNTER31H);
    }

    /** The CSR's assembler name, or null if this core does not implement it. */
    public static String name(int csr) {
        switch (csr) {
            case MVENDORID: return "mvendorid";
            case MARCHID: return "marchid";
            case MIMPID: return "mimpid";
            case MHARTID: return "mhartid";
            case MCONFIGPTR: return "mconfigptr";
            case MSTATUS: return "mstatus";
            case MISA: return "misa";
            case MIE: return "mie";
            case MTVEC: return "mtvec";
            case MSTATUSH: return "mstatush";
            case MCOUNTINHIBIT: return "mcountinhibit";
            case MSCRATCH: return "mscratch";
            case MEPC: return "mepc";
            case MCAUSE: return "mcause";
            case MTVAL: return "mtval";
            case MIP: return "mip";
            case MCYCLE: return "mcycle";
            case MINSTRET: return "minstret";
            case MCYCLEH: return "mcycleh";
            case MINSTRETH: return "minstreth";
            case CYCLE: return "cycle";
            case TIME: return "time";
            case INSTRET: return "instret";
            case CYCLEH: return "cycleh";
            case TIMEH: return "timeh";
            case INSTRETH: return "instreth";
            default:
                break;
        }
        if (in(csr, MHPMCOUNTER3, MHPMCOUNTER31))
            return "mhpmcounter" + (csr - MHPMCOUNTER3 + 3);
        if (in(csr, MHPMCOUNTER3H, MHPMCOUNTER31H))
            return "mhpmcounter" + (csr - MHPMCOUNTER3H + 3) + "h";
        if (in(csr, MHPMEVENT3, MHPMEVENT31))
            return "mhpmevent" + (csr - MHPMEVENT3 + 3);
        if (in(csr, HPMCOUNTER3, HPMCOUNTER31))
            return "hpmcounter" + (csr - HPMCOUNTER3 + 3);
        if (in(csr, HPMCOUNTER3H, HPMCOUNTER31H))
            return "hpmcounter" + (csr - HPMCOUNTER3H + 3) + "h";
        return null;
    }

    private static boolean in(int csr, int first, int last) {
        return csr >= first && csr <= last;
    }
}
