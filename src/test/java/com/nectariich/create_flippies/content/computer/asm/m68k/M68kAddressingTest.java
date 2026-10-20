package com.nectariich.create_flippies.content.computer.asm.m68k;

import org.junit.jupiter.api.Test;

import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check;
import static com.nectariich.create_flippies.content.computer.asm.m68k.M68kTesting.check000;

/**
 * Every effective address mode through {@code move.w <ea>,d0} (0x3000 | mode << 3 | reg) and as a
 * destination, including the 68040 scaled index, full extension words and memory indirect forms.
 * Extension words hand-assembled from the PRM's brief and full format layouts.
 */
class M68kAddressingTest {

    @Test
    void registerAndIndirectModes() {
        check000("move.w d3,d0", 0x3003);
        check000("move.w a3,d0", 0x300B);
        check000("move.w (a3),d0", 0x3013);
        check000("move.w (a3)+,d0", 0x301B);
        check000("move.w -(a3),d0", 0x3023);
        check000("move.w (sp),d0", 0x3017);
        check000("move.w (SP)+,D0", 0x301F);
    }

    @Test
    void displacementBothSyntaxes() {
        check000("move.w (8,a1),d0", 0x3029, 0x0008);
        check000("move.w 8(a1),d0", 0x3029, 0x0008);
        check000("move.w -2(a1),d0", 0x3029, 0xFFFE);
        check000("move.w 0(a1),d0", 0x3029, 0x0000);
        check000("move.w (0,a1),d0", 0x3029, 0x0000);
        check000("move.w (32767,a1),d0", 0x3029, 0x7FFF);
        check000("off equ 6\n move.w off(a2),d0", 0x302A, 0x0006);
        check000("move.w (2+4)(a2),d0", 0x302A, 0x0006);
    }

    @Test
    void thirtyTwoBitNegativeDisplacementIsSigned() {
        // GNU as reads a 32-bit displacement as signed: $FFFFFFFE is -2 (d16 and brief index)
        check000("move.w $FFFFFFFE(a1),d0", 0x3029, 0xFFFE);
        check000("move.w $FFFF8000(a1),d0", 0x3029, 0x8000);
        check000("move.w $FFFFFFFF(a0,d7.w),d0", 0x3030, 0x70FF);
        check("move.w $FFFFFFFE(a1),d0", 0x3029, 0xFFFE);
        check("move.w $FFFFFF80(a0,d7.w),d0", 0x3030, 0x7080);
    }

    @Test
    void briefIndex() {
        check000("move.w 2(a1,d2.w),d0", 0x3031, 0x2002);
        check000("move.w 2(a1,d2),d0", 0x3031, 0x2002);
        check000("move.w (2,a1,a2.l),d0", 0x3031, 0xA802);
        check000("move.w (a1,d2.l),d0", 0x3031, 0x2800);
        check000("move.w -128(a0,d7.w),d0", 0x3030, 0x7080);
        check000("move.w (127,a0,sp.l),d0", 0x3030, 0xF87F);
        check000("move.w (a0,d0.w*1),d0", 0x3030, 0x0000);
        check("move.w (-1,a0,d0.l*4),d0", 0x3030, 0x0CFF);
        check("move.w (a0,d1.w*8),d0", 0x3030, 0x1600);
    }

    @Test
    void fullFormatOn68040() {
        check("move.w (1000,a0,d1.l*2),d0", 0x3030, 0x1B20, 0x03E8);
        check("move.w (100000,a0,d1),d0", 0x3030, 0x1130, 0x0001, 0x86A0);
        check("move.w (40000,a0),d0", 0x3030, 0x0170, 0x0000, 0x9C40);
        check("move.w (d0.l*4),d0", 0x3030, 0x0D90);
        check("move.w (za0,d1),d0", 0x3030, 0x1190);
        check("move.w (8,za3,d1.w),d0", 0x3033, 0x11A0, 0x0008);
        check("move.w (4.l,a0,d1.w),d0", 0x3030, 0x1130, 0x0000, 0x0004);
        check("move.w (4.w,a0,zd1),d0", 0x3030, 0x0160, 0x0004);
    }

    @Test
    void memoryIndirect() {
        check("move.w ([4,a0],d1.w,8),d0", 0x3030, 0x1126, 0x0004, 0x0008);
        check("move.w ([4,a0,d1.w],8),d0", 0x3030, 0x1122, 0x0004, 0x0008);
        check("move.w ([a0]),d0", 0x3030, 0x0151);
        check("move.w ([$10]),d0", 0x3030, 0x01E1, 0x0010);
        check("move.w ([a0],d2.l*4),d0", 0x3030, 0x2D15);
        check("move.w ([a0,d2.l*4]),d0", 0x3030, 0x2D11);
        check("move.w ([$12345678],100000),d0", 0x3030, 0x01F3, 0x1234, 0x5678, 0x0001, 0x86A0);
        check("clr.l ([a1],8)", 0x42B1, 0x0152, 0x0008);
    }

    @Test
    void absoluteSizeSelection() {
        check000("move.w $1234,d0", 0x3038, 0x1234);
        check000("move.w $12345678,d0", 0x3039, 0x1234, 0x5678);
        check000("move.w d0,$7FFF", 0x31C0, 0x7FFF);
        check000("move.w d0,$8000", 0x33C0, 0x0000, 0x8000);
        check000("move.w d0,$FFFF8000", 0x31C0, 0x8000);
        check000("move.w d0,$FFFF7FFF", 0x33C0, 0xFFFF, 0x7FFF);
        check000("move.w d0,$1000.l", 0x33C0, 0x0000, 0x1000);
        check000("move.w d0,($10).w", 0x31C0, 0x0010);
        check000("move.w d0,(-2).w", 0x31C0, 0xFFFE);
        check000("move.w $10.l,d0", 0x3039, 0x0000, 0x0010);
        check000("x equ $20\n move.w x,d0", 0x3038, 0x0020);
        // a label is not known as an address in the first pass: abs.L
        check000("lab: move.w lab,d0", 0x3039, 0x0000, 0x1000);
    }

    @Test
    void pcRelative() {
        check000("lab: move.w lab(pc),d0", 0x303A, 0xFFFE);
        check000("lab: move.w (lab,pc),d0", 0x303A, 0xFFFE);
        check000("move.w lab(pc),d0\nlab: nop", 0x303A, 0x0002, 0x4E71);
        check000("lab: move.w (lab,pc,d1.w),d0", 0x303B, 0x10FE);
        check000("lab: move.w lab(pc,a1.l),d0", 0x303B, 0x98FE);
        check000("move.w 8(pc),d0", 0x303A, 0x0008);
        check000("move.w (pc),d0", 0x303A, 0x0000);
        check("lab: move.w ([lab,pc]),d0", 0x303B, 0x0161, 0xFFFE);
        check("lab: move.w (lab,pc,d0.w*2),d0", 0x303B, 0x02FE);
        check("move.w (zpc,d0.w),d0", 0x303B, 0x0190);
    }

    @Test
    void immediateSizes() {
        check000("move.b #-1,d0", 0x103C, 0x00FF);
        check000("move.b #255,d0", 0x103C, 0x00FF);
        check000("move.w #-32768,d0", 0x303C, 0x8000);
        check000("move.w #65535,d0", 0x303C, 0xFFFF);
        check000("lab: move.l #lab,a0", 0x207C, 0x0000, 0x1000);
        check000("move.w #'A',d0", 0x303C, 0x0041);
        check000("move.b #%1010,d0", 0x103C, 0x000A);
        check000("move.w #$10*2+1,d0", 0x303C, 0x0021);
    }

    @Test
    void destinationModes() {
        check000("clr.w (a0)", 0x4250);
        check000("clr.w (a0)+", 0x4258);
        check000("clr.w -(a0)", 0x4260);
        check000("clr.w 2(a0)", 0x4268, 0x0002);
        check000("clr.w 2(a0,d0.w)", 0x4270, 0x0002);
        check000("clr.w $100", 0x4278, 0x0100);
        check000("clr.w $100000", 0x4279, 0x0010, 0x0000);
        check000("move.l (a0),(a1,d1.w)", 0x2390, 0x1000);
        check000("move.l 4(a0),8(a1)", 0x2368, 0x0004, 0x0008);
        check000("move.w #1,$1234", 0x31FC, 0x0001, 0x1234);
    }
}
