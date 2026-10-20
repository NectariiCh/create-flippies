package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.VgaDisplay.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The VGA framebuffer device (spec 3.7), with VRAM mapped on the bus as the RV32I machine does. */
class VgaDisplayTest {

    private DeviceRig rig;
    private VgaDisplay vga;
    private final DeviceRig.Line line = new DeviceRig.Line();

    @BeforeEach
    void setUp() {
        rig = new DeviceRig();
        vga = new VgaDisplay(line);
        rig.map(vga, 0x1000);
        rig.bus.setVram(vga.vram(), DeviceRig.VRAM_BASE, vga.dirtyPages());
    }

    private void vramWrite(int offset, int value) {
        rig.bus.write8(DeviceRig.VRAM_BASE + offset, value);
    }

    @Test
    void modeGeometry() {
        int[][] expected = { { 80, 25, 16, 160 }, { 320, 200, 8, 320 }, { 640, 480, 4, 320 }, { 640, 480, 8, 640 } };
        for (int m = 0; m < 4; m++) {
            rig.w32(MODE, m);
            assertEquals(m, rig.r32(MODE));
            assertEquals(expected[m][0], rig.r32(WIDTH), "mode " + m);
            assertEquals(expected[m][1], rig.r32(HEIGHT), "mode " + m);
            assertEquals(expected[m][2], rig.r32(BPP), "mode " + m);
            assertEquals(expected[m][3], rig.r32(PITCH), "mode " + m);
        }
        rig.w32(MODE, 7);
        assertEquals(3, rig.r32(MODE), "other values are ignored");
        rig.w32(MODE, -1);
        assertEquals(3, rig.r32(MODE));
    }

    @Test
    void resetValues() {
        assertEquals(0, rig.r32(MODE));
        assertEquals(0xD, rig.r32(CTRL), "display, cursor and blink on");
        assertEquals(0, rig.r32(STATUS));
        assertEquals(0, rig.r32(START));
        assertEquals(0, rig.r32(FRAME));
        VgaFrame f = vga.snapshot();
        assertTrue(f.displayEnabled());
        assertTrue(f.cursorVisible());
        assertTrue(f.blink());
    }

    @Test
    void defaultPalette() {
        int[] cga = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };
        for (int i = 0; i < 16; i++)
            assertEquals(cga[i], rig.r32(PALETTE + 4 * i), "CGA " + i);
        assertEquals(0x000000, rig.r32(PALETTE + 4 * 16), "cube origin");
        assertEquals(0x00005F, rig.r32(PALETTE + 4 * 17));
        assertEquals(0x5F87AF, rig.r32(PALETTE + 4 * (16 + 36 + 12 + 3)));
        assertEquals(0xFFFFFF, rig.r32(PALETTE + 4 * 231), "cube end");
        assertEquals(0x080808, rig.r32(PALETTE + 4 * 232), "grey ramp start");
        assertEquals(0xEEEEEE, rig.r32(PALETTE + 4 * 255), "grey ramp end");
    }

    @Test
    void generationMovesOnlyWhenSomethingChanged() {
        vga.endFrame();
        long g = vga.generation();
        assertEquals(1, g, "the first frame after reset is dirty");
        vga.endFrame();
        assertEquals(g, vga.generation(), "nothing changed");
        vramWrite(10, 'A');
        vga.endFrame();
        assertEquals(g + 1, vga.generation());
        rig.bus.read8(DeviceRig.VRAM_BASE + 10);
        vga.endFrame();
        assertEquals(g + 1, vga.generation(), "reads do not dirty");
        rig.w32(CURSOR, 0x0102);
        vga.endFrame();
        assertEquals(g + 2, vga.generation());
    }

    @Test
    void vblankAndFrameCounter() {
        vga.endFrame();
        assertEquals(1, rig.r32(FRAME));
        assertEquals(STATUS_VBLANK, rig.r32(STATUS));
        assertFalse(line.level, "VBLANK IRQ is off at reset");
        rig.w32(CTRL, CTRL_RESET | CTRL_VBLANK_IRQ);
        assertTrue(line.level);
        rig.w32(STATUS, STATUS_VBLANK);
        assertFalse(line.level);
        assertEquals(0, rig.r32(STATUS));
        vga.endFrame();
        assertTrue(line.level);
        assertEquals(2, rig.r32(FRAME));
    }

    @Test
    void startWrapsAtTheEndOfVram() {
        rig.w32(MODE, MODE_640X480X8);
        rig.w32(START, 0x7_0000);
        byte[] vram = vga.vram();
        vram[0x7_0000] = 1;
        vram[0x7_FFFF] = 2;
        vram[0] = 3;
        vram[307_200 - 0x1_0000 - 1] = 4;
        vram[307_200 - 0x1_0000] = 5;
        byte[] dst = new byte[307_200];
        assertEquals(307_200, vga.copyVisibleInto(dst));
        assertEquals(1, dst[0]);
        assertEquals(2, dst[0xFFFF]);
        assertEquals(3, dst[0x1_0000]);
        assertEquals(4, dst[307_199]);
        rig.w32(START, 0x8_0004);
        assertEquals(4, rig.r32(START), "START is a VRAM offset");
    }

    @Test
    void snapshotAndCopyHoldOnlyTheVisibleBytes() {
        byte[] vram = vga.vram();
        vram[0] = 'H';
        vram[1] = 0x1F;
        vram[3999] = 0x77;
        vram[4000] = 0x55;
        rig.w32(CURSOR, 2);
        VgaFrame f = vga.snapshot();
        assertEquals(4000, f.pixels().length);
        assertEquals('H', f.pixelByte(0));
        assertEquals(0x1F, f.pixelByte(1));
        assertEquals(0x77, f.pixelByte(3999));
        assertEquals(2, f.cursorColumn());
        assertEquals(0, f.cursorRow());
        assertEquals(256, f.palette().length);
        assertEquals(0x0000AA, f.paletteEntry(1));
        f.pixels()[0] = 0;
        assertEquals('H', f.pixelByte(0), "the frame is immutable");
        vram[0] = 'X';
        assertEquals('H', f.pixelByte(0), "and a copy");

        byte[] small = new byte[3999];
        assertThrows(IllegalArgumentException.class, () -> vga.copyVisibleInto(small));
        byte[] large = new byte[5000];
        large[4000] = 9;
        assertEquals(4000, vga.copyVisibleInto(large));
        assertEquals(9, large[4000], "nothing past the visible bytes is touched");
    }

    @Test
    void dirtyRowsAreExactlyTheRowsOverADirtiedPage() {
        rig.w32(MODE, MODE_320X200X8);
        vga.endFrame();
        long g0 = vga.generation();
        vramWrite(0x1000, 1);
        vga.endFrame();
        BitSet rows = vga.dirtyRowsSince(g0);
        BitSet expected = new BitSet();
        expected.set(12, 26);
        assertEquals(expected, rows, "page 1 is bytes 4096..8191: rows 12..25 of pitch 320");
        assertEquals(new BitSet(), vga.dirtyRowsSince(vga.generation()));

        long g1 = vga.generation();
        rig.w32(PALETTE + 4, 0xFF0000);
        vga.endFrame();
        assertEquals(200, vga.dirtyRowsSince(g1).cardinality(), "a palette change dirties every row");

        long g2 = vga.generation();
        rig.w32(START, 320);
        vga.endFrame();
        assertEquals(200, vga.dirtyRowsSince(g2).cardinality(), "so does START");

        long g3 = vga.generation();
        vramWrite(320 * 199 + 320 + 5, 1);
        vga.endFrame();
        BitSet last = vga.dirtyRowsSince(g3);
        assertTrue(last.get(199), "rows follow START");
        BitSet expectedLast = new BitSet();
        expectedLast.set(191, 200);
        assertEquals(expectedLast, last, "page 15 holds rows 191..199 at START 320");
    }

    @Test
    void textModeRowsAreTextRows() {
        vga.endFrame();
        long g = vga.generation();
        vramWrite(160 * 24, 'Z');
        vga.endFrame();
        BitSet rows = vga.dirtyRowsSince(g);
        assertEquals(25, rows.cardinality(), "all of 4000 bytes sit in page 0");
    }

    @Test
    void palIndexAndPalDataAutoIncrement() {
        rig.w32(PAL_INDEX, 5);
        rig.w32(PAL_DATA, 0x112233);
        rig.w32(PAL_DATA, 0xFF445566);
        assertEquals(7, rig.r32(PAL_INDEX));
        assertEquals(0x112233, vga.palette(5));
        assertEquals(0x445566, vga.palette(6), "bits 31..24 are not stored");
        rig.w32(PAL_INDEX, 5);
        assertEquals(0x112233, rig.r32(PAL_DATA));
        assertEquals(0x445566, rig.r32(PAL_DATA));
        assertEquals(7, rig.r32(PAL_INDEX));
        rig.w32(PAL_INDEX, 255);
        rig.r32(PAL_DATA);
        assertEquals(0, rig.r32(PAL_INDEX), "wraps at 256");
        rig.w32(PAL_INDEX, 0x1FF);
        assertEquals(0xFF, rig.r32(PAL_INDEX));
    }

    @Test
    void palDataAsTwoSixteenBitHalves() {
        rig.w32(PAL_INDEX, 10);
        rig.w16(PAL_DATA, 0x3344);
        assertEquals(10, rig.r32(PAL_INDEX), "nothing stored until byte 3");
        rig.w16(PAL_DATA + 2, 0x0012);
        assertEquals(0x123344, vga.palette(10));
        assertEquals(11, rig.r32(PAL_INDEX));
        rig.w32(PAL_INDEX, 10);
        assertEquals(0x3344, rig.r16(PAL_DATA));
        assertEquals(10, rig.r32(PAL_INDEX), "the read increment is on byte 3");
        assertEquals(0x0012, rig.r16(PAL_DATA + 2));
        assertEquals(11, rig.r32(PAL_INDEX));
    }

    @Test
    void bankAndDirectPalette() {
        rig.w32(BANK, 9);
        assertEquals(1, rig.r32(BANK), "values 0..7");
        rig.w32(PALETTE + 4 * 200, 0x12ABCDEF);
        assertEquals(0xABCDEF, rig.r32(PALETTE + 4 * 200));
        rig.w8(PALETTE + 4 * 200, 0x01);
        assertEquals(0xABCD01, vga.palette(200));
    }

    @Test
    void resetRestoresRegistersAndKeepsVram() {
        vga.vram()[7] = 42;
        rig.w32(MODE, 1);
        rig.w32(PALETTE, 0x123456);
        rig.w32(CTRL, 0);
        vga.endFrame();
        vga.reset();
        assertEquals(0, rig.r32(MODE));
        assertEquals(0xD, rig.r32(CTRL));
        assertEquals(0, rig.r32(PALETTE));
        assertEquals(0, rig.r32(FRAME));
        assertEquals(42, vga.vram()[7]);
    }

    @Test
    void graphicsSnapshot() {
        rig.w32(MODE, MODE_640X480X4);
        vramWrite(0, 0x1F);
        VgaFrame f = vga.snapshot();
        assertEquals(640, f.width());
        assertEquals(480, f.height());
        assertEquals(4, f.bitsPerPixel());
        assertEquals(320 * 480, f.pixels().length);
        assertArrayEquals(new byte[] { 0x1F, 0 }, java.util.Arrays.copyOf(f.pixels(), 2));
    }
}
