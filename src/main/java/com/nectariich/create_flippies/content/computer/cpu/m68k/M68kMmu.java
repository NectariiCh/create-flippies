package com.nectariich.create_flippies.content.computer.cpu.m68k;

import com.nectariich.create_flippies.content.computer.bus.Bus;
import com.nectariich.create_flippies.content.computer.bus.BusFault;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * The 68040's paged memory management unit (spec 12.2): its eight registers, the transparent
 * translation registers, the three-level table search with U and M written back, PTEST and the
 * PFLUSH forms, and a software TLB that {@link M68kCpu} reads directly on its hot path.
 *
 * The TLB is not a model of the chip's ATCs: it is direct-mapped, {@link #TLB_SIZE} entries per
 * kind of access (instruction fetch, data read, data write) and privilege (user, supervisor),
 * keyed by the logical page. An entry holds the logical page (the tag) and the physical page. It
 * only ever holds translations that succeed: a fetch or read entry once the search has set U, a
 * write entry once the page is writable for that privilege and its M bit is set, so a hit never
 * needs to touch a descriptor. Transparent translation hits and the identity mapping with paging
 * off are cached the same way. Every MOVEC to TC, URP, SRP or a TT register, every PFLUSH form
 * and every reset empties it; writes to the tables in memory do not, as on the chip.
 *
 * The TLB is saved state ({@link #saveTlb}): a guest may change a descriptor without a PFLUSH
 * and keep using the old entry, as on the chip, and a restored machine must make the same
 * choice and the same U and M write-backs as one that never stopped.
 *
 * Descriptor reads and the U and M write-backs are physical accesses straight through the bus.
 * They cost no cycles.
 */
final class M68kMmu {

    /** Entries per kind and privilege; a power of two. */
    static final int TLB_SIZE = 1024;
    static final int TLB_MASK = TLB_SIZE - 1;
    /** Kinds of access, the middle part of a TLB slot. */
    static final int FETCH = 0, READ = 1, WRITE = 2;
    /** A tag no page matches: page tags have their low 12 bits clear. */
    static final int INVALID = 1;
    /** {@link #translate} result of a failed translation. */
    static final long FAULT = -1;

    /** TC bit 15 (E): paging enabled; bit 14 (P): 8 KiB pages. The other bits read 0 (UM040 3.1.2). */
    static final int TC_E = 0x8000, TC_P = 0x4000, TC_MASK = TC_E | TC_P;
    /** TT bits that exist; 12..10, 7, 4, 3, 1 and 0 read 0 (UM040 3.1.3). */
    static final int TT_MASK = 0xFFFF_E364;
    static final int TT_E = 0x8000, TT_W = 0x0004;

    /** MMUSR bits (UM040 3.1.4). */
    static final int MMUSR_B = 0x800, MMUSR_T = 0x002, MMUSR_R = 0x001, MMUSR_W = 0x004;
    /** Descriptor bits (UM040 3.2.2): U used, W write protect, M modified, S supervisor, G global. */
    static final int D_U = 0x008, D_W = 0x004, D_M = 0x010, D_S = 0x080, D_G = 0x400;
    /** Page descriptor bits PTEST copies into MMUSR: G, U1, U0, S, CM, M. */
    private static final int PD_STATUS = 0x7F0;

    private final Bus bus;

    /** Logical page tags, {@link #INVALID} when empty; slot = (privilege * 3 + kind) * TLB_SIZE + index. */
    final int[] tag = new int[6 * TLB_SIZE];
    /** Physical page bases. */
    final int[] phys = new int[6 * TLB_SIZE];
    /** The entry came from a page descriptor with G set (kept by PFLUSHN and PFLUSHAN). */
    private final boolean[] global = new boolean[6 * TLB_SIZE];

    private int tc;
    private int itt0;
    private int itt1;
    private int dtt0;
    private int dtt1;
    private int urp;
    private int srp;
    private int mmusr;
    private int pageShift = 12;

    // ---- the last table search ----

    /** Page descriptor after its U and M updates. */
    private int walkPd;
    /** W accumulated over every level. */
    private boolean walkWp;
    /** A descriptor access hit a bus error. */
    private boolean walkBus;

    M68kMmu(Bus bus) {
        this.bus = bus;
        flushAll(false);
    }

    /** Spec 10.3.8 and UM040 3.6.1: every register to 0 (paging and TT registers off), TLB empty. */
    void reset() {
        tc = itt0 = itt1 = dtt0 = dtt1 = urp = srp = mmusr = 0;
        pageShift = 12;
        flushAll(false);
    }

    /** Paging or a TT register is on: the core must translate every access. */
    boolean active() {
        return ((tc | itt0 | itt1 | dtt0 | dtt1) & TT_E) != 0;
    }

    /** 12 for 4 KiB pages, 13 for 8 KiB (TC.P). */
    int pageShift() {
        return pageShift;
    }

    /** First TLB slot of {@code kind} for user or supervisor accesses. */
    static int slot(boolean superv, int kind) {
        return ((superv ? 3 : 0) + kind) * TLB_SIZE;
    }

    // ---- registers ----

    /** TC, ITT0..DTT1, MMUSR, URP or SRP by MOVEC number. */
    int register(int code) {
        switch (code) {
            case M68kCpu.CR_TC: return tc;
            case M68kCpu.CR_ITT0: return itt0;
            case M68kCpu.CR_ITT1: return itt1;
            case M68kCpu.CR_DTT0: return dtt0;
            case M68kCpu.CR_DTT1: return dtt1;
            case M68kCpu.CR_MMUSR: return mmusr;
            case M68kCpu.CR_URP: return urp;
            case M68kCpu.CR_SRP: return srp;
            default: throw new IllegalArgumentException("not an MMU register: " + code);
        }
    }

    /**
     * Writes a register as MOVEC does. TC keeps E and P, the TT registers their defined bits; URP
     * and SRP keep every bit (the search ignores 8..0). Any write but MMUSR empties the TLB: the
     * chip does not, but a flush is always a correct superset (spec 12.2.2).
     */
    void setRegister(int code, int v) {
        switch (code) {
            case M68kCpu.CR_TC: tc = v & TC_MASK; break;
            case M68kCpu.CR_ITT0: itt0 = v & TT_MASK; break;
            case M68kCpu.CR_ITT1: itt1 = v & TT_MASK; break;
            case M68kCpu.CR_DTT0: dtt0 = v & TT_MASK; break;
            case M68kCpu.CR_DTT1: dtt1 = v & TT_MASK; break;
            case M68kCpu.CR_MMUSR: mmusr = v; return;
            case M68kCpu.CR_URP: urp = v; break;
            case M68kCpu.CR_SRP: srp = v; break;
            default: throw new IllegalArgumentException("not an MMU register: " + code);
        }
        pageShift = (tc & TC_P) != 0 ? 13 : 12;
        flushAll(false);
    }

    // ---- translation ----

    /**
     * Translates {@code la} for an access of {@code kind} with the privilege {@code superv} (FC2)
     * after a TLB miss, and fills the TLB. Order (spec 12.2.2): the TT registers of the kind, then
     * the identity mapping when TC.E is clear, then the table search. Returns the physical page
     * base, or {@link #FAULT} for an invalid descriptor, a bus error during the search, a user
     * access to a supervisor page, or a write to a write-protected page or TT block.
     */
    long translate(int la, int kind, boolean superv) {
        int pageMask = -1 << pageShift;
        int page = la & pageMask;
        int tt = ttMatch(la, kind == FETCH, superv);
        if (tt != 0 || (tc & TC_E) == 0) {
            boolean writable = (tt & TT_W) == 0;
            if (kind == WRITE && !writable)
                return FAULT;
            if (kind == FETCH) {
                fill(slot(superv, FETCH), page, page, false);
            } else {
                fill(slot(superv, READ), page, page, false);
                if (writable)
                    fill(slot(superv, WRITE), page, page, false);
            }
            return page & 0xFFFF_FFFFL;
        }
        boolean write = kind == WRITE;
        if (!walk(la, superv, write))
            return FAULT;
        int pd = walkPd;
        if (!superv && (pd & D_S) != 0)
            return FAULT;
        if (write && walkWp)
            return FAULT;
        int frame = pd & pageMask;
        boolean g = (pd & D_G) != 0;
        if (kind == FETCH)
            fill(slot(superv, FETCH), page, frame, g);
        else
            fill(slot(superv, READ), page, frame, g);
        if (kind != FETCH && !walkWp && (pd & D_M) != 0)
            fill(slot(superv, WRITE), page, frame, g);
        return frame & 0xFFFF_FFFFL;
    }

    /** The first matching TT register of the access's MMU (TT0 wins over TT1, UM040 3.4), or 0. */
    private int ttMatch(int la, boolean fetch, boolean superv) {
        int t0 = fetch ? itt0 : dtt0;
        if (ttMatches(t0, la, superv))
            return t0;
        int t1 = fetch ? itt1 : dtt1;
        return ttMatches(t1, la, superv) ? t1 : 0;
    }

    /** E set, the S field allows FC2 (0 user only, 1 supervisor only, 2 and 3 both) and the masked base equals A31..A24. */
    private static boolean ttMatches(int tt, int la, boolean superv) {
        if ((tt & TT_E) == 0)
            return false;
        int s = (tt >>> 13) & 3;
        if (s < 2 && (s == 1) != superv)
            return false;
        return (((la >>> 24) ^ (tt >>> 24)) & ~(tt >>> 16) & 0xFF) == 0;
    }

    /**
     * The table search of UM040 3.2 (figures 3-9 and 3-10) for {@code la}: root and pointer
     * descriptors from URP or SRP, then the page descriptor, through one indirect descriptor if
     * there is one. U is set in every resident descriptor on the way that has it clear, and M in
     * the page descriptor on a write that is neither write protected nor a supervisor violation
     * (UM040 table 3-1); both are written back to memory. Returns false on an invalid descriptor
     * or a bus error ({@link #walkBus}); otherwise the page descriptor is in {@link #walkPd} and
     * the accumulated W in {@link #walkWp}.
     */
    private boolean walk(int la, boolean superv, boolean write) {
        walkWp = false;
        walkBus = false;
        walkPd = 0;
        try {
            int a = ((superv ? srp : urp) & 0xFFFF_FE00) | ((la >>> 23) & 0x1FC);
            int d = tableDescriptor(a);
            if (d == INVALID)
                return false;
            a = (d & 0xFFFF_FE00) | ((la >>> 16) & 0x1FC);
            d = tableDescriptor(a);
            if (d == INVALID)
                return false;
            if (pageShift == 12)
                a = (d & 0xFFFF_FF00) | ((la >>> 10) & 0xFC);
            else
                a = (d & 0xFFFF_FF80) | ((la >>> 11) & 0x7C);
            d = bus.read32(a);
            if ((d & 3) == 2) {
                // indirect: bits 31..2 address the real page descriptor, which may not be indirect
                a = d & ~3;
                d = bus.read32(a);
                if ((d & 3) == 2)
                    return false;
            }
            if ((d & 3) == 0)
                return false;
            if ((d & D_W) != 0)
                walkWp = true;
            int updated = d | D_U;
            if (write && !walkWp && (superv || (d & D_S) == 0))
                updated |= D_M;
            if (updated != d)
                bus.write32(a, updated);
            walkPd = updated;
            return true;
        } catch (BusFault fault) {
            walkBus = true;
            return false;
        }
    }

    /** A root or pointer descriptor: {@link #INVALID} for UDT 0 or 1; otherwise U is set in memory and W accumulated. */
    private int tableDescriptor(int a) {
        int d = bus.read32(a);
        if ((d & 2) == 0)
            return INVALID;
        if ((d & D_U) == 0)
            bus.write32(a, d | D_U);
        if ((d & D_W) != 0)
            walkWp = true;
        return d;
    }

    private void fill(int base, int page, int frame, boolean g) {
        int i = base + ((page >>> pageShift) & TLB_MASK);
        tag[i] = page;
        phys[i] = frame;
        global[i] = g;
    }

    // ---- PTEST and PFLUSH (spec 12.2.4) ----

    /**
     * PTESTR or PTESTW of {@code la} with function code {@code fc} (the DFC): the instruction
     * MMU's TT registers for codes 2 and 6, the data MMU's otherwise; FC2 picks the table. A TT
     * hit gives MMUSR = T | R and nothing else (UM040 3.1.4). With paging off and no TT hit the
     * chip's result is undefined; here it is the address itself with R set. Otherwise the entry
     * is flushed, the tables are searched as for a read or a write (setting U, and M for PTESTW,
     * as a search does) and MMUSR reports the physical page, G, U1, U0, S, CM, M, the accumulated
     * W and R; B alone after a bus error, W alone (or 0) for an invalid descriptor. A successful
     * search loads the TLB as an access would.
     */
    void ptest(int la, int fc, boolean write) {
        boolean superv = (fc & 4) != 0;
        boolean fetch = (fc & 3) == 2;
        if (ttMatch(la, fetch, superv) != 0) {
            mmusr = MMUSR_T | MMUSR_R;
            return;
        }
        if ((tc & TC_E) == 0) {
            mmusr = (la & 0xFFFF_F000) | MMUSR_R;
            return;
        }
        flushPage(la, superv, false);
        if (!walk(la, superv, write)) {
            mmusr = walkBus ? MMUSR_B : walkWp ? MMUSR_W : 0;
            return;
        }
        int pd = walkPd;
        mmusr = (pd & (-1 << pageShift)) | (pd & PD_STATUS) | (walkWp ? MMUSR_W : 0) | MMUSR_R;
        int page = la & (-1 << pageShift);
        int frame = pd & (-1 << pageShift);
        boolean g = (pd & D_G) != 0;
        if (!superv && (pd & D_S) != 0)
            return;
        fill(slot(superv, fetch ? FETCH : READ), page, frame, g);
        if (!fetch && !walkWp && (pd & D_M) != 0)
            fill(slot(superv, WRITE), page, frame, g);
    }

    /** PFLUSH (An) and PFLUSHN (An): every kind's entry for the page in the FC2 table; N keeps global entries. */
    void flushPage(int la, boolean superv, boolean nonGlobalOnly) {
        int page = la & (-1 << pageShift);
        int index = (la >>> pageShift) & TLB_MASK;
        for (int kind = FETCH; kind <= WRITE; kind++) {
            int i = slot(superv, kind) + index;
            if (tag[i] == page && !(nonGlobalOnly && global[i]))
                tag[i] = INVALID;
        }
    }

    // ---- saved state ----

    /** The TLB's valid entries: a count, then each one's slot, logical page, physical page and G. */
    void saveTlb(DataOutput out) throws IOException {
        int count = 0;
        for (int t : tag)
            if (t != INVALID)
                count++;
        out.writeInt(count);
        for (int i = 0; i < tag.length; i++) {
            if (tag[i] == INVALID)
                continue;
            out.writeShort(i);
            out.writeInt(tag[i]);
            out.writeInt(phys[i]);
            out.writeBoolean(global[i]);
        }
    }

    /** Replaces the TLB with what {@link #saveTlb} wrote; call after the registers are loaded. */
    void loadTlb(DataInput in) throws IOException {
        flushAll(false);
        int count = in.readInt();
        StateIo.check(count >= 0 && count <= tag.length, "bad 68040 TLB entry count " + count);
        for (int k = 0; k < count; k++) {
            int i = in.readUnsignedShort();
            StateIo.check(i < tag.length, "bad 68040 TLB slot " + i);
            tag[i] = in.readInt();
            phys[i] = in.readInt();
            global[i] = in.readBoolean();
        }
    }

    /** PFLUSHA (everything) and PFLUSHAN (every entry that is not global). */
    void flushAll(boolean nonGlobalOnly) {
        if (!nonGlobalOnly) {
            Arrays.fill(tag, INVALID);
            return;
        }
        for (int i = 0; i < tag.length; i++)
            if (!global[i])
                tag[i] = INVALID;
    }
}
