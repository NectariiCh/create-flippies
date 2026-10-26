package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.M68kE2e.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.asm.AsmImage;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.cpu.CpuModel;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.machine.MachineState;
import com.nectariich.create_flippies.content.computer.machine.StandardCpus;

import org.junit.jupiter.api.Test;

/**
 * Demand paging on the 68040 machine (spec 12.2.5): a small kernel maps itself and the devices
 * through ITT0 and DTT0 (supervisor only), builds user page tables, turns paging on and drops to
 * a user program whose data and stack pages do not exist yet. Its access error handler reads
 * the frame's SSW and FA (rounding up a misaligned fault, as Linux's access_error040 does), asks
 * PTESTR whether the page is resident, maps a fresh zeroed frame, PFLUSHes and returns with RTE,
 * which restarts the faulted instruction. TRAP #1 prints a dot and flushes the whole TLB; TRAP #0
 * prints the user's checksum, the fault count and a user long read with MOVES, then powers off.
 * The same guest runs saved and loaded after every tick in lockstep with an uninterrupted twin.
 */
class M68kMmuDemandPagingE2eTest {

    private static final CpuModel MODEL = CpuModel.CPU_68040;
    /** Pages the user program sweeps each round (1024 longs each) and the number of rounds. */
    private static final int PAGES = 64;
    private static final int ROUNDS = 40;

    private static final String PROGRAM = """
            ROOT        equ $80000
            TPOOL       equ $81000
            FPOOL       equ $90000
            UDATA       equ $40000000
            USTACK      equ $50000000
            PAGES       equ %d
            ROUNDS      equ %d

            _start: move.l  #access_error,2*4
                    move.l  #sys_exit,32*4
                    move.l  #sys_dot,33*4
                    move.l  #TPOOL,tnext
                    move.l  #FPOOL,fnext
                    lea     ROOT,a0                 ; empty root table
                    move.w  #128-1,d0
            1:      clr.l   (a0)+
                    dbra    d0,1b
                    move.l  #$00FFA000,d0           ; supervisor only, every address to itself
                    movec   d0,itt0
                    movec   d0,dtt0
                    move.l  #ROOT,d0
                    movec   d0,urp
                    movec   d0,srp
                    lea     ucode,a0                ; user code: two read-only pages, mapped to themselves
                    move.l  a0,d1
                    moveq   #5,d2
                    bsr     map
                    add.l   #$1000,a0
                    add.l   #$1000,d1
                    bsr     map
                    pflusha
                    move.l  #$8000,d0               ; paging on, 4 KiB pages
                    movec   d0,tc
                    move.l  #USTACK,a0
                    move.l  a0,usp
                    clr.w   -(sp)                   ; format 0 frame: user mode at ucode
                    pea     ucode
                    clr.w   -(sp)
                    rte

            ; map: the page descriptor of logical A0 becomes D1 | D2, building tables as needed
            map:    movem.l d0-d3/a1,-(sp)
                    move.l  a0,d0                   ; root index * 4 = bits 31..25
                    rol.l   #7,d0
                    and.l   #$7F,d0
                    lsl.l   #2,d0
                    lea     ROOT,a1
                    add.l   d0,a1
                    move.l  (a1),d3
                    btst    #1,d3
                    bne.s   1f
                    move.l  #512,d0
                    bsr     talloc
                    move.l  d0,d3
                    or.l    #2,d3
                    move.l  d3,(a1)
            1:      and.l   #$FFFFFE00,d3           ; pointer index * 4 = bits 24..18
                    move.l  d3,a1
                    move.l  a0,d0
                    swap    d0
                    and.l   #$1FC,d0
                    add.l   d0,a1
                    move.l  (a1),d3
                    btst    #1,d3
                    bne.s   2f
                    move.l  #256,d0
                    bsr     talloc
                    move.l  d0,d3
                    or.l    #2,d3
                    move.l  d3,(a1)
            2:      and.l   #$FFFFFF00,d3           ; page index * 4 = bits 17..12
                    move.l  d3,a1
                    move.l  a0,d0
                    lsr.l   #8,d0
                    lsr.l   #2,d0
                    and.l   #$FC,d0
                    add.l   d0,a1
                    move.l  d1,d0
                    and.l   #$FFFFF000,d0
                    or.l    d2,d0
                    move.l  d0,(a1)
                    movem.l (sp)+,d0-d3/a1
                    rts

            ; talloc: D0 = size (a power of two) -> D0 = a zeroed table aligned to its size
            talloc: movem.l d1/a1,-(sp)
                    move.l  tnext,d1
                    add.l   d0,d1
                    subq.l  #1,d1
                    neg.l   d0
                    and.l   d0,d1
                    neg.l   d0
                    move.l  d1,a1
                    add.l   d0,d1
                    move.l  d1,tnext
                    move.l  a1,d1
                    lsr.l   #2,d0
                    subq.l  #1,d0
            1:      clr.l   (a1)+
                    dbra    d0,1b
                    move.l  d1,d0
                    movem.l (sp)+,d1/a1
                    rts

            ; access error: map a zeroed frame at the faulting page, flush it, restart
            access_error:
                    movem.l d0-d2/a0-a1,-(sp)
                    move.w  20+$0C(sp),d2           ; SSW
                    btst    #10,d2                  ; ATC fault?
                    beq     bad_fault
                    move.l  20+$14(sp),d0           ; FA
                    btst    #11,d2                  ; MA: the fault is on the next page
                    beq.s   1f
                    addq.l  #7,d0
                    and.l   #-8,d0
            1:      move.l  d0,a0
                    move.w  d2,d0
                    and.w   #7,d0                   ; TM is the function code to test with
                    movec   d0,dfc
                    ptestr  (a0)
                    movec   mmusr,d0
                    btst    #0,d0                   ; resident: a protection fault, not ours
                    bne     bad_fault
                    move.l  fnext,d1
                    add.l   #$1000,fnext
                    move.l  d1,a1
                    move.w  #1024-1,d0
            2:      clr.l   (a1)+
                    dbra    d0,2b
                    moveq   #1,d2
                    bsr     map
                    pflush  (a0)
                    addq.l  #1,faults
                    movem.l (sp)+,d0-d2/a0-a1
                    rte

            bad_fault:
                    lea     msg_bad(pc),a0
                    bsr     puts
                    move.l  20+$14(sp),d0
                    bsr     puthex
                    moveq   #1,d0
                    bra     fail

            sys_dot:
                    movem.l d0/a0,-(sp)
                    moveq   #'.',d0
                    bsr     putc
                    pflusha
                    movem.l (sp)+,d0/a0
                    rte

            sys_exit:
                    move.l  d0,d7
                    lea     msg_sum(pc),a0
                    bsr     puts
                    move.l  d7,d0
                    bsr     puthex
                    lea     msg_faults(pc),a0
                    bsr     puts
                    move.l  faults,d0
                    bsr     puthex
                    lea     msg_u0(pc),a0
                    bsr     puts
                    moveq   #1,d0                   ; MOVES with SFC = user data
                    movec   d0,sfc
                    lea     UDATA,a0
                    moves.l (a0),d0
                    bsr     puthex
                    moveq   #10,d0
                    bsr     putc
                    bra     exit

            msg_bad:    dc.b    "BAD FAULT ",0
            msg_sum:    dc.b    " sum=",0
            msg_faults: dc.b    " faults=",0
            msg_u0:     dc.b    " u0=",0

            ; ---- the user program: no page of its data or stack exists when it starts ----
                    align   4096
            ucode:  moveq   #0,d7
            round:  lea     UDATA,a0
                    move.w  #PAGES*1024-1,d2
                    move.l  d7,d1
            1:      add.l   d1,(a0)+
                    addq.l  #1,d1
                    dbra    d2,1b
                    trap    #1
                    addq.l  #1,d7
                    cmp.l   #ROUNDS,d7
                    bne.s   round
                    clr.l   UDATA+PAGES*4096                ; page PAGES
                    move.l  #$11223344,UDATA+PAGES*4096+4094 ; misaligned into the missing page PAGES+1
                    bsr.s   usum                            ; the first push faults the stack page in
                    trap    #0
            usum:   lea     UDATA,a0
                    move.w  #PAGES*1024-1,d2
                    moveq   #0,d0
            1:      add.l   (a0)+,d0
                    dbra    d2,1b
                    add.l   UDATA+PAGES*4096+4094,d0
                    rts

                    .bss
            tnext:  ds.l    1
            fnext:  ds.l    1
            faults: ds.l    1
            """.formatted(PAGES, ROUNDS);

    /** What the user program sums: every long j got r + j in round r, plus the misaligned long. */
    private static String expected() {
        int sum = 0;
        for (int j = 0; j < PAGES * 1024; j++)
            for (int r = 0; r < ROUNDS; r++)
                sum += r + j;
        sum += 0x1122_3344;
        int u0 = ROUNDS * (ROUNDS - 1) / 2;
        return ".".repeat(ROUNDS) + String.format(" sum=%08X faults=%08X u0=%08X\n", sum, PAGES + 3, u0);
    }

    @Test
    void userProgramRunsOnPagesMappedOnDemand() {
        AsmImage program = assemble(MODEL, PROGRAM);
        Machine m = boot(MODEL, program, null);
        runUntilOff(m, 400);
        assertCleanExit(m);
        assertEquals(expected(), com1(m));
    }

    @Test
    void saveAndLoadEveryTickRunsInLockstep() throws StateFormatException {
        AsmImage program = assemble(MODEL, PROGRAM);
        Machine a = boot(MODEL, program, null);
        Machine b = boot(MODEL, program, null);
        StringBuilder outA = new StringBuilder(), outB = new StringBuilder();
        int t = 0;
        for (; t < 400 && a.state() == MachineState.RUNNING; t++) {
            a.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            b.runTick(Machine.TICK_NANOS, BIG_BUDGET);
            outA.append(com1(a));
            outB.append(com1(b));
            Machine c = new Machine(config(MODEL, null, false), StandardCpus.INSTANCE);
            c.loadState(b.saveState());
            b = c;
            assertArrayEquals(a.saveState(), b.saveState(), "state after tick " + t);
        }
        assertTrue(t > 5, "the run spans several saves: " + t + " ticks");
        assertCleanExit(a);
        assertCleanExit(b);
        outA.append(com1(a));
        outB.append(com1(b));
        assertEquals(expected(), outA.toString());
        assertEquals(outA.toString(), outB.toString());
    }
}
