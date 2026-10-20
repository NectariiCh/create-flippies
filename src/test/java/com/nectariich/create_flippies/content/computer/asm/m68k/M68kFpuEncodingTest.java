package com.nectariich.create_flippies.content.computer.asm.m68k;

import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check;

/**
 * FPU instructions encode to hand-assembled words (PRM section 5 and its errata). The command
 * word of a general instruction is {@code 0 R/M 0 src dst opmode}; FMOVE out is
 * {@code 011 format src k}; control moves are {@code 10 dr select}; FMOVEM is
 * {@code 11 dr mode 000 list}.
 */
class M68kFpuEncodingTest {

    @Test
    void registerToRegister() {
        check("fmove.x fp0,fp1", 0xF200, 0x0080);
        check("fmove fp0,fp1", 0xF200, 0x0080);
        check("fadd.x fp1,fp2", 0xF200, 0x0522);
        check("fsin.x fp0,fp1", 0xF200, 0x008E);
        check("fneg fp0", 0xF200, 0x001A);
        check("fintrz fp2", 0xF200, 0x0903);
        check("fcmp.x fp0,fp1", 0xF200, 0x00B8);
        check("ftst.x fp0", 0xF200, 0x003A);
        check("fsmove.x fp0,fp1", 0xF200, 0x00C0);
        check("fdmove fp7,fp6", 0xF200, 0x1F44);
        check("fmod.x fp0,fp1", 0xF200, 0x00A1);
        check("fsincos.x fp0,fp1:fp2", 0xF200, 0x0131);
        check("FMUL.X FP3,FP4", 0xF200, 0x0E23);
    }

    @Test
    void memoryAndRegisterSources() {
        check("fmove.l d0,fp0", 0xF200, 0x4000);
        check("fmove.x (a0)+,fp3", 0xF218, 0x4980);
        check("fsqrt.d (a0),fp2", 0xF210, 0x5504);
        check("fabs.s d1,fp0", 0xF201, 0x4418);
        check("fdadd.d (a0),fp3", 0xF210, 0x55E6);
        check("ftst.l d0", 0xF200, 0x403A);
        check("fadd.b 2(a1),fp0", 0xF229, 0x5822, 0x0002);
        check("fsub.w d3,fp1", 0xF203, 0x50A8);
        check("fmove.p (a0),fp0", 0xF210, 0x4C00);
        check("fmove (a0),fp1", 0xF210, 0x4880);
        check("fsincos.d (a0),fp3:fp4", 0xF210, 0x5633);
    }

    @Test
    void immediates() {
        check("fmove.s #1.1,fp0", 0xF23C, 0x4400, 0x3F8C, 0xCCCD);
        check("fmove.d #0.1,fp0", 0xF23C, 0x5400, 0x3FB9, 0x9999, 0x9999, 0x999A);
        check("fmove.x #1.0,fp0", 0xF23C, 0x4800, 0x3FFF, 0x0000, 0x8000, 0x0000, 0x0000, 0x0000);
        check("fmove.x #-2.5,fp1", 0xF23C, 0x4880, 0xC000, 0x0000, 0xA000, 0x0000, 0x0000, 0x0000);
        check("fmove.w #3,fp0", 0xF23C, 0x5000, 0x0003);
        check("fmove.b #-1,fp0", 0xF23C, 0x5800, 0x00FF);
        check("fmove.l #100,fp1", 0xF23C, 0x4080, 0x0000, 0x0064);
        check("fsdiv.s #2.0,fp0", 0xF23C, 0x4460, 0x4000, 0x0000);
        check("fmove.s #2,fp0", 0xF23C, 0x4400, 0x4000, 0x0000);
        check("fmove.d #-1,fp0", 0xF23C, 0x5400, 0xBFF0, 0x0000, 0x0000, 0x0000);
        check("fmove.p #1.0,fp0", 0xF23C, 0x4C00, 0x0000, 0x0001, 0x0000, 0x0000, 0x0000, 0x0000);
        check("fmove.s #1e10,fp0", 0xF23C, 0x4400, 0x5015, 0x02F9);
    }

    @Test
    void moveOut() {
        check("fmove.l fp0,d0", 0xF200, 0x6000);
        check("fmove.s fp1,(a0)", 0xF210, 0x6480);
        check("fmove.d fp2,-(sp)", 0xF227, 0x7500);
        check("fmove.x fp3,8(a1)", 0xF229, 0x6980, 0x0008);
        check("fmove.b fp0,d7", 0xF207, 0x7800);
        check("fmove.p fp0,(a0){#3}", 0xF210, 0x6C03);
        check("fmove.p fp0,(a0){d1}", 0xF210, 0x7C10);
        check("fmove.p fp1,(a1){#-2}", 0xF211, 0x6CFE);
        check("fmove.p fp0,(a0)", 0xF210, 0x6C00);
    }

    @Test
    void controlRegisters() {
        check("fmove.l fpcr,d0", 0xF200, 0xB000);
        check("fmove.l d0,fpsr", 0xF200, 0x8800);
        check("fmove.l fpiar,a0", 0xF208, 0xA400);
        check("fmove fpcr,(a0)", 0xF210, 0xB000);
        check("fmove.l #0,fpcr", 0xF23C, 0x9000, 0x0000, 0x0000);
        check("fmovem.l fpcr/fpsr/fpiar,-(sp)", 0xF227, 0xBC00);
        check("fmovem.l (sp)+,fpcr/fpsr", 0xF21F, 0x9800);
        check("fmovem fpsr,d1", 0xF201, 0xA800);
    }

    @Test
    void fmovemDataRegisters() {
        check("fmovem.x fp2-fp7,-(sp)", 0xF227, 0xE0FC);
        check("fmovem.x (sp)+,fp2-fp7", 0xF21F, 0xD03F);
        check("fmovem.x fp0,(a0)", 0xF210, 0xF080);
        check("fmovem fp0/fp7,-(a1)", 0xF221, 0xE081);
        check("fmovem.x d1,-(sp)", 0xF227, 0xE810);
        check("fmovem.x (a0),d2", 0xF210, 0xD820);
        check("fmovem.x #$FC,-(sp)", 0xF227, 0xE0FC);
        check("fmovem.x 4(a0),fp0-fp1", 0xF228, 0xD0C0, 0x0004);
    }

    @Test
    void constantsAndSystem() {
        check("fmovecr #0,fp0", 0xF200, 0x5C00);
        check("fmovecr.x #$32,fp1", 0xF200, 0x5CB2);
        check("fnop", 0xF280, 0x0000);
        check("fsave -(sp)", 0xF327);
        check("frestore (sp)+", 0xF35F);
        check("fsave (a0)", 0xF310);
    }

    @Test
    void conditionals() {
        check("fbeq.w *", 0xF281, 0xFFFE);
        check("fbne.l *", 0xF2CE, 0xFFFF, 0xFFFE);
        check("fbngle *", 0xF298, 0xFFFE);
        check("fbf *+6", 0xF280, 0x0004);
        check("fbst *", 0xF29F, 0xFFFE);
        check("fseq d0", 0xF240, 0x0001);
        check("fsogt (a0)", 0xF250, 0x0002);
        check("fdbne d0,*", 0xF248, 0x000E, 0xFFFC);
        check("loop: fdbgt d3,loop", 0xF24B, 0x0012, 0xFFFC);
        check("ftrapeq", 0xF27C, 0x0001);
        check("ftrapgt.w #1", 0xF27A, 0x0012, 0x0001);
        check("ftrapor.l #2", 0xF27B, 0x0007, 0x0000, 0x0002);
    }
}
