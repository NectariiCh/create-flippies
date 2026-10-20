package com.nectariich.create_flippies.content.computer.asm.m68k;

import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check000;

/**
 * Integer instructions encode to hand-assembled words. Each expected word was worked out by hand
 * from the operation word layouts in the programmer's reference manual (section 4 and the
 * section 8 opcode map), not from the assembler's tables.
 */
class M68kIntegerEncodingTest {

    @Test
    void moveRegisterAndMemoryForms() {
        check000("move.l d0,d1", 0x2200);
        check000("move.w d1,d0", 0x3001);
        check000("move.b (a0),d1", 0x1210);
        check000("move.l (a0)+,-(a1)", 0x2318);
        check000("move.w -(a2),(a3)+", 0x36E2);
        check000("move.l 8(a0),d0", 0x2028, 0x0008);
        check000("move.w d0,(-4,a1)", 0x3340, 0xFFFC);
        check000("move.l a0,d0", 0x2008);
        check000("move.w a1,d0", 0x3009);
        check000("move d1,d0", 0x3001);
        check000("MOVE.W D1,D0", 0x3001);
        check000("move.l d0,-(sp)", 0x2F00);
    }

    @Test
    void moveImmediatesAndMoveqSelection() {
        check000("move.b #$12,d0", 0x103C, 0x0012);
        check000("move.w #$1234,d0", 0x303C, 0x1234);
        check000("move.l #$12345678,(a0)", 0x20BC, 0x1234, 0x5678);
        check000("move.l #$12345678,d0", 0x203C, 0x1234, 0x5678);
        check000("move.l #5,d0", 0x7005);
        check000("move.l #-1,d3", 0x76FF);
        check000("move.l #$FFFFFFFF,d0", 0x70FF);
        check000("move.l #127,d0", 0x707F);
        check000("move.l #-128,d0", 0x7080);
        check000("move.l #128,d0", 0x203C, 0x0000, 0x0080);
        check000("move.l #200,d0", 0x203C, 0x0000, 0x00C8);
        check000("move.w #5,d0", 0x303C, 0x0005);
        check000("moveq #127,d7", 0x7E7F);
        check000("moveq #-128,d0", 0x7080);
        check000("moveq #0,d1", 0x7200);
    }

    @Test
    void forwardReferencedImmediateStaysLong() {
        check000("move.l #n,d0\nn equ 5", 0x203C, 0x0000, 0x0005);
        check000("n equ 5\nmove.l #n,d0", 0x7005);
        check000("add.l #n,d0\nn equ 2", 0x0680, 0x0000, 0x0002);
    }

    @Test
    void moveaAndSpecialRegisters() {
        check000("movea.l a0,a1", 0x2248);
        check000("move.l d0,a1", 0x2240);
        check000("movea.w #-1,a0", 0x307C, 0xFFFF);
        check000("move.w #1,a0", 0x307C, 0x0001);
        check000("move.w sr,d0", 0x40C0);
        check000("move d0,sr", 0x46C0);
        check000("move #$2700,sr", 0x46FC, 0x2700);
        check000("move d1,ccr", 0x44C1);
        check000("move #0,ccr", 0x44FC, 0x0000);
        check000("move.l a0,usp", 0x4E60);
        check000("move.l usp,a3", 0x4E6B);
        check000("move usp,a3", 0x4E6B);
        check("move ccr,d0", 0x42C0);
        check("move.w ccr,(a0)", 0x42D0);
    }

    @Test
    void movepAndMovem() {
        check000("movep.w 2(a0),d1", 0x0308, 0x0002);
        check000("movep.l d0,0(a1)", 0x01C9, 0x0000);
        check000("movep.w d2,(a3)", 0x058B, 0x0000);
        check000("movem.l d0-d7/a0-a6,-(sp)", 0x48E7, 0xFFFE);
        check000("movem.l (sp)+,d0-d7/a0-a6", 0x4CDF, 0x7FFF);
        check000("movem.w d0/d2,(a0)", 0x4890, 0x0005);
        check000("movem.l (a0),d0-d1/a5", 0x4CD0, 0x2003);
        check000("movem.l d3,-(a7)", 0x48E7, 0x1000);
        check000("movem.l a6,-(a7)", 0x48E7, 0x0002);
        check000("movem (a1)+,d0", 0x4C99, 0x0001);
        check000("movem.l #$0300,-(sp)", 0x48E7, 0x0300);
        check000("movem.l 8(a0),a0-a1", 0x4CE8, 0x0300, 0x0008);
        check000("movem.l d0-a7,(a0)", 0x48D0, 0xFFFF);
    }

    @Test
    void leaPea() {
        check000("lea 8(a0),a1", 0x43E8, 0x0008);
        check000("lea (a0),a0", 0x41D0);
        check000("lea $1234,a2", 0x45F8, 0x1234);
        check000("pea (a1)", 0x4851);
        check000("pea $1234", 0x4878, 0x1234);
        check000("lab: lea lab(pc),a0", 0x41FA, 0xFFFE);
    }

    @Test
    void addSubAndTheirQuickImmediateAndAddressForms() {
        check000("add.l d1,d0", 0xD081);
        check000("add.w (a0),d1", 0xD250);
        check000("add.b d0,(a1)", 0xD111);
        check000("add.l d0,-(a1)", 0xD1A1);
        check000("add.w #10,d0", 0x0640, 0x000A);
        check000("add.l #1000,d1", 0x0681, 0x0000, 0x03E8);
        check000("add.l #8,a0", 0x5088);
        check000("add.l #9,a0", 0xD1FC, 0x0000, 0x0009);
        check000("add.w #1,d0", 0x5240);
        check000("sub.w #1,d0", 0x5340);
        check000("sub.l #8,(a0)", 0x5190);
        check000("sub.w #0,d0", 0x0440, 0x0000);
        check000("subq.l #4,sp", 0x598F);
        check000("addq.b #8,(a0)", 0x5010);
        check000("addq #1,d0", 0x5240);
        check000("adda.w d0,a1", 0xD2C0);
        check000("suba.l a0,a0", 0x91C8);
        check000("adda.l #1,a0", 0xD1FC, 0x0000, 0x0001);
        check000("add.l d0,a1", 0xD3C0);
        check000("sub.w (a0),a2", 0x94D0);
        check000("addi.b #1,d0", 0x0600, 0x0001);
        check000("subi.l #5,(a0)", 0x0490, 0x0000, 0x0005);
        check000("addx.l d0,d1", 0xD380);
        check000("subx.w -(a0),-(a1)", 0x9348);
        check000("addx.b -(a2),-(a3)", 0xD70A);
    }

    @Test
    void immediateLogicAndStatusRegisterForms() {
        check000("cmpi.w #3,d0", 0x0C40, 0x0003);
        check000("andi.w #$FF,d0", 0x0240, 0x00FF);
        check000("ori.b #$80,d1", 0x0001, 0x0080);
        check000("eori.l #-1,d2", 0x0A82, 0xFFFF, 0xFFFF);
        check000("ori #$0700,sr", 0x007C, 0x0700);
        check000("andi #$F8FF,sr", 0x027C, 0xF8FF);
        check000("andi #$FE,ccr", 0x023C, 0x00FE);
        check000("eori.b #1,ccr", 0x0A3C, 0x0001);
        check000("or.w #$0700,sr", 0x007C, 0x0700);
        check000("and.b #$0F,d0", 0x0200, 0x000F);
        check000("eor.l #1,d0", 0x0A80, 0x0000, 0x0001);
        check000("ori.w #1,$1234", 0x0078, 0x0001, 0x1234);
    }

    @Test
    void bcdAndCompare() {
        check000("abcd d0,d1", 0xC300);
        check000("abcd -(a0),-(a1)", 0xC308);
        check000("sbcd -(a1),-(a2)", 0x8509);
        check000("nbcd (a0)", 0x4810);
        check000("cmp.l d0,d1", 0xB280);
        check000("cmp.w #5,d0", 0x0C40, 0x0005);
        check000("cmp.l a0,a1", 0xB3C8);
        check000("cmpa.w d0,a0", 0xB0C0);
        check000("cmpm.b (a0)+,(a1)+", 0xB308);
        check000("cmp.w (a0)+,(a1)+", 0xB348);
        check000("cmp.b (a0),d7", 0xBE10);
    }

    @Test
    void logic() {
        check000("and.w d0,d1", 0xC240);
        check000("and.l d1,(a0)", 0xC390);
        check000("or.b (a1),d2", 0x8411);
        check000("or.l d3,4(a2)", 0x87AA, 0x0004);
        check000("eor.w d1,d0", 0xB340);
        check000("eor.b d2,(a0)+", 0xB518);
    }

    @Test
    void singleOperand() {
        check000("clr.l d0", 0x4280);
        check000("clr.b (a0)", 0x4210);
        check000("clr d2", 0x4242);
        check000("neg.w d1", 0x4441);
        check000("negx.l d2", 0x4082);
        check000("not.b d3", 0x4603);
        check000("tst.w d0", 0x4A40);
        check000("tst.l (a0)+", 0x4A98);
        check("tst.l a0", 0x4A88);
        check("tst.w 4(pc)", 0x4A7A, 0x0004);
        check("tst.l #1", 0x4ABC, 0x0000, 0x0001);
        check000("ext.w d0", 0x4880);
        check000("ext.l d1", 0x48C1);
        check("extb.l d2", 0x49C2);
        check000("swap d3", 0x4843);
        check000("tas (a0)", 0x4AD0);
        check000("seq d0", 0x57C0);
        check000("st (a0)", 0x50D0);
        check000("sf d1", 0x51C1);
        check000("shi d0", 0x52C0);
        check000("shs d0", 0x54C0);
        check000("slo d0", 0x55C0);
        check000("sle.b 2(a0)", 0x5FE8, 0x0002);
    }

    @Test
    void multiplyAndDivide() {
        check000("mulu.w d1,d0", 0xC0C1);
        check000("muls (a0),d2", 0xC5D0);
        check000("divu.w #10,d0", 0x80FC, 0x000A);
        check000("divs d1,d0", 0x81C1);
        check("muls.l d1,d0", 0x4C01, 0x0800);
        check("mulu.l d1,d0", 0x4C01, 0x0000);
        check("mulu.l d1,d2:d3", 0x4C01, 0x3402);
        check("muls.l (a0),d4:d5", 0x4C10, 0x5C04);
        check("divs.l d1,d0", 0x4C41, 0x0800);
        check("divu.l d1,d0", 0x4C41, 0x0000);
        check("divu.l d1,d3", 0x4C41, 0x3003);
        check("divul.l d1,d2:d3", 0x4C41, 0x3002);
        check("divsl.l d1,d2:d3", 0x4C41, 0x3802);
        check("divs.l d1,d2:d3", 0x4C41, 0x3C02);
        check("divu.l #7,d0", 0x4C7C, 0x0000, 0x0000, 0x0007);
    }

    @Test
    void shiftsAndRotates() {
        check000("lsl.w #2,d0", 0xE548);
        check000("asr.l d1,d0", 0xE2A0);
        check000("rol.b #8,d3", 0xE11B);
        check000("roxr.w #1,d2", 0xE252);
        check000("roxl.l d3,d4", 0xE7B4);
        check000("lsr.b #3,d5", 0xE60D);
        check000("ror.l #4,d6", 0xE89E);
        check000("asl (a0)", 0xE1D0);
        check000("lsr.w (a1)", 0xE2D1);
        check000("ror.w 4(a0)", 0xE6E8, 0x0004);
        check000("roxl (a2)+", 0xE5DA);
        check000("asl d0", 0xE340);
    }

    @Test
    void bitOperations() {
        check000("btst #3,d0", 0x0800, 0x0003);
        check000("btst d1,(a0)", 0x0310);
        check000("bset d1,(a0)", 0x03D0);
        check000("bclr #7,(a0)+", 0x0898, 0x0007);
        check000("bchg #31,d5", 0x0845, 0x001F);
        check000("btst d0,#$55", 0x013C, 0x0055);
        check000("bset.b #0,$1234", 0x08F8, 0x0000, 0x1234);
        check000("btst.l d2,d3", 0x0503);
        check000("lab: btst #1,lab(pc)", 0x083A, 0x0001, 0xFFFC);
    }

    @Test
    void branchesWithFixedSizes() {
        check000("bra *", 0x60FE);
        check000("beq.s *+4", 0x6702);
        check000("bne.w *", 0x6600, 0xFFFE);
        check000("bsr.w *+100", 0x6100, 0x0062);
        check000("bhs.s *+10", 0x6408);
        check000("blo.s *+10", 0x6508);
        check000("bgt.b *+10", 0x6E08);
        check("bra.l *", 0x60FF, 0xFFFF, 0xFFFE);
        check("bsr.l *+8", 0x61FF, 0x0000, 0x0006);
        check000("dbf d0,*", 0x51C8, 0xFFFE);
        check000("dbra d1,*", 0x51C9, 0xFFFE);
        check000("dbeq d2,*+4", 0x57CA, 0x0002);
        check000("dbt d3,*", 0x50CB, 0xFFFE);
        check000("loop: nop\n dbne d4,loop", 0x4E71, 0x56CC, 0xFFFC);
    }

    @Test
    void jumpsAndFixedWords() {
        check000("jsr (a0)", 0x4E90);
        check000("jmp 8(a1)", 0x4EE9, 0x0008);
        check000("jmp $1234", 0x4EF8, 0x1234);
        check000("lab: jsr lab", 0x4EB9, 0x0000, 0x1000);
        check000("nop", 0x4E71);
        check000("rts", 0x4E75);
        check000("rte", 0x4E73);
        check000("rtr", 0x4E77);
        check000("reset", 0x4E70);
        check000("illegal", 0x4AFC);
        check000("trapv", 0x4E76);
        check000("stop #$2700", 0x4E72, 0x2700);
        check("rtd #8", 0x4E74, 0x0008);
        check000("trap #15", 0x4E4F);
        check000("trap #0", 0x4E40);
        check000("link a6,#-8", 0x4E56, 0xFFF8);
        check("link.l a5,#-100000", 0x480D, 0xFFFE, 0x7960);
        check000("unlk a6", 0x4E5E);
        check000("chk.w d1,d0", 0x4181);
        check000("chk (a0),d1", 0x4390);
        check("chk.l (a0),d2", 0x4510);
        check000("exg d0,d1", 0xC141);
        check000("exg a0,a1", 0xC149);
        check000("exg d2,a3", 0xC58B);
        check000("exg a3,d2", 0xC58B);
    }

    @Test
    void the68040Additions() {
        check("chk2.l (a0),d0", 0x04D0, 0x0800);
        check("cmp2.b (a1),a2", 0x00D1, 0xA000);
        check("cmp2.w $1234,d1", 0x02F8, 0x1000, 0x1234);
        check("movec vbr,d0", 0x4E7A, 0x0801);
        check("movec d0,vbr", 0x4E7B, 0x0801);
        check("movec a0,cacr", 0x4E7B, 0x8002);
        check("movec usp,a1", 0x4E7A, 0x9800);
        check("movec sfc,d1", 0x4E7A, 0x1000);
        check("movec.l d2,srp", 0x4E7B, 0x2807);
        check("moves.l d0,(a0)", 0x0E90, 0x0800);
        check("moves.b (a1),d2", 0x0E11, 0x2000);
        check("moves.w a3,4(a2)", 0x0E6A, 0xB800, 0x0004);
        check("cas.l d0,d1,(a0)", 0x0ED0, 0x0040);
        check("cas.w d2,d3,(a1)", 0x0CD1, 0x00C2);
        check("cas.b d0,d1,$10", 0x0AF8, 0x0040, 0x0010);
        check("cas2.l d0:d1,d2:d3,(a0):(a1)", 0x0EFC, 0x8080, 0x90C1);
        check("cas2.w d0:d1,d2:d3,(d4):(d5)", 0x0CFC, 0x4080, 0x50C1);
        check("pack -(a0),-(a1),#$3030", 0x8348, 0x3030);
        check("unpk d0,d1,#0", 0x8380, 0x0000);
        check("move16 (a0)+,(a1)+", 0xF620, 0x9000);
        check("move16 (a2)+,$1000", 0xF602, 0x0000, 0x1000);
        check("move16 $2000,(a3)", 0xF61B, 0x0000, 0x2000);
        check("move16 $2000,(a3)+", 0xF60B, 0x0000, 0x2000);
        check("move16 (a4),$3000", 0xF614, 0x0000, 0x3000);
        check("bkpt #3", 0x484B);
        check("trapeq", 0x57FC);
        check("trapne.w #1", 0x56FA, 0x0001);
        check("trapt.l #5", 0x50FB, 0x0000, 0x0005);
        check("trapf", 0x51FC);
        check("cinva bc", 0xF4D8);
        check("cpusha bc", 0xF4F8);
        check("cinvl dc,(a0)", 0xF448);
        check("cpushp ic,(a1)", 0xF4B1);
        check("cinvp nc,(a7)", 0xF417);
        check("pflusha", 0xF518);
        check("pflushan", 0xF510);
        check("pflush (a0)", 0xF508);
        check("pflushn (a1)", 0xF501);
        check("ptestr (a0)", 0xF568);
        check("ptestw (a1)", 0xF549);
    }

    @Test
    void bitfields() {
        check("bfextu d0{8:4},d1", 0xE9C0, 0x1204);
        check("bfins d1,(a0){d2:d3}", 0xEFD0, 0x18A3);
        check("bfset (a0){0:32}", 0xEED0, 0x0000);
        check("bftst d3{d1:5}", 0xE8C3, 0x0845);
        check("bfffo (a1){4:8},d2", 0xEDD1, 0x2108);
        check("bfexts 4(a0){0:16},d0", 0xEBE8, 0x0010, 0x0004);
        check("bfchg d0{31:1}", 0xEAC0, 0x07C1);
        check("bfclr (a0){1:2}", 0xECD0, 0x0042);
        check("bfextu (a0){#3:#5},d7", 0xE9D0, 0x70C5);
    }
}
