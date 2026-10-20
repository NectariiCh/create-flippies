package com.nectariich.create_flippies.content.computer.asm.m68k;

import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.ok;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.oneError;

/** Spec 10.7.5: what each target rejects, and with which message. */
class M68kTargetGatingTest {

    private static final String[] ONLY_040 = {
            "extb.l d0", "movec d0,vbr", "moves.l d0,(a0)", "rtd #4", "bkpt #1", "trapeq", "trapne.w #1",
            "cas.l d0,d1,(a0)", "cas2.l d0:d1,d2:d3,(a0):(a1)", "chk2.l (a0),d0", "cmp2.w (a0),d1",
            "bfextu d0{1:2},d1", "bftst (a0){0:8}", "pack d0,d1,#0", "unpk d0,d1,#0", "move16 (a0)+,(a1)+",
            "cinva bc", "cpushl dc,(a0)", "pflusha", "pflush (a0)", "ptestr (a0)", "divul.l d0,d1:d2",
            "muls.l d0,d1", "divu.l d0,d1", "chk.l d0,d1", "link.l a6,#0", "bra.l *", "tst.l a0",
            "tst.w 4(pc)", "tst.w #1", "cmpi.w #1,4(pc)", "move ccr,d0", "move.w (a0,d0.w*4),d0",
            "move.w ([a0]),d0", "move.w (d0),d0", "fadd fp0,fp1", "fmove.l fpcr,d0", "fmovem.x fp0,-(sp)",
            "fnop", "fsave -(sp)", "fbeq *", "fsin fp0", "fmovecr #0,fp0"};

    @Test
    void the68000RejectsEvery68040Form() {
        for (String src : ONLY_040) {
            oneError(M68kTarget.M68000, src, 1, "needs a 68040 (assembling for the 68000)");
            ok(M68kTarget.M68040, src);
        }
        oneError(M68kTarget.M68000, " bfextu d0{1:2},d1", 1, "'bfextu' needs a 68040 (assembling for the 68000)");
        oneError(M68kTarget.M68000, " move.w (a0,d0.w*4),d0", 1, "scaled index needs a 68040");
        oneError(M68kTarget.M68000, " move.w ([a0]),d0", 1, "memory indirect addressing needs a 68040");
    }

    @Test
    void the68000ReportsDisplacementsThatOnlyFitOnThe68040() {
        oneError(M68kTarget.M68000, " move.w (1000,a0,d0),d0", 1,
                "displacement 1000 does not fit in 8 bits (a 68040 could use a larger displacement)");
        oneError(M68kTarget.M68000, " move.w (40000,a0),d0", 1, "displacement 40000 does not fit in 16 bits");
        ok(M68kTarget.M68040, " move.w (1000,a0,d0),d0");
    }

    @Test
    void the68040RejectsWhatItDoesNotHave() {
        oneError(M68kTarget.M68040, " callm #1,(a0)", 1, "'callm' is not on the 68040");
        oneError(M68kTarget.M68040, " rtm d0", 1, "'rtm' is not on the 68040");
        oneError(M68kTarget.M68040, " pmove (a0),tc", 1, "'pmove' is not on the 68040");
        oneError(M68kTarget.M68040, " pflush #1,#2", 1, "with function code operands is not on the 68040");
        oneError(M68kTarget.M68040, " ptestr #1,(a0),#7", 1, "with function code operands is not on the 68040");
        oneError(M68kTarget.M68000, " callm #1,(a0)", 1, "'callm' is not on the 68040 (nor on the 68000)");
    }

    @Test
    void supportPackageInstructionsAreAcceptedOnThe68040() {
        for (String src : new String[] {"fsin fp0,fp1", "fetox.s #1.0,fp2", "fmovecr #$0B,fp0", "fintrz fp0",
                "fmod.x fp1,fp2", "frem.d (a0),fp3", "fscale.l d0,fp1", "fgetexp fp0", "fsincos.x fp0,fp1:fp2",
                "ftentox.x fp4", "fmove.p #1.5,fp0"})
            ok(M68kTarget.M68040, src);
    }
}
