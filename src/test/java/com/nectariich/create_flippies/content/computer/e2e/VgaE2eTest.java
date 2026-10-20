package com.nectariich.create_flippies.content.computer.e2e;

import static com.nectariich.create_flippies.content.computer.e2e.E2e.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;
import com.nectariich.create_flippies.content.computer.device.VgaFrame;
import com.nectariich.create_flippies.content.computer.machine.Machine;

import java.util.BitSet;

import org.junit.jupiter.api.Test;

/** The VGA framebuffer end to end (spec 3.7, 8.4): the guest draws, the host copies the image across. */
class VgaE2eTest {

    @Test
    void textModeCellsAndCursor() {
        Machine m = boot("""
                _start:
                    li   t0, VRAM
                    li   t1, 'H'
                    sb   t1, 0(t0)
                    li   t1, 0x1F
                    sb   t1, 1(t0)
                    li   t1, 'I'
                    sb   t1, 2(t0)
                    li   t1, 0x1F
                    sb   t1, 3(t0)
                    li   t0, VGA
                    li   t1, 2                  # column 2, row 0
                    sw   t1, 0x0C(t0)
                1:  wfi
                    j    1b
                """);
        long before = m.vga().generation();
        runTicks(m, 1);
        VgaFrame f = m.vga().snapshot();
        assertEquals(before + 1, f.generation());
        assertEquals(VgaDisplay.MODE_TEXT, f.mode());
        assertEquals(80, f.width());
        assertEquals(25, f.height());
        assertEquals('H', f.pixelByte(0));
        assertEquals(0x1F, f.pixelByte(1));
        assertEquals('I', f.pixelByte(2));
        assertEquals(0x1F, f.pixelByte(3));
        assertEquals(2, f.cursorColumn());
        assertEquals(0, f.cursorRow());

        // nothing drawn in the next tick: the generation stays, so the game sends nothing
        runTicks(m, 1);
        assertEquals(before + 1, m.vga().generation());
    }

    @Test
    void graphicsModeDiagonalAndPalette() {
        // mode 1 (320x200, 8 bpp), palette entry 1 = red through the mapped palette, entry 2 =
        // green through PAL_INDEX/PAL_DATA, then a diagonal of colour 1 and one pixel of colour 2
        Machine m = boot("""
                _start:
                    li   s0, VGA
                    li   t0, 1
                    sw   t0, 0x00(s0)           # MODE 1
                    li   t0, 0xFF0000
                    sw   t0, 0x404(s0)          # PALETTE[1]
                    li   t0, 2
                    sw   t0, 0x2C(s0)           # PAL_INDEX
                    li   t0, 0x00FF00
                    sw   t0, 0x30(s0)           # PAL_DATA
                    li   t0, VRAM
                    li   t1, 0                  # i
                    li   t2, 200
                    li   t3, 1
                1:  sb   t3, 0(t0)              # pixel (i, i)
                    addi t0, t0, 321
                    addi t1, t1, 1
                    blt  t1, t2, 1b
                    li   t0, VRAM + 319
                    li   t3, 2
                    sb   t3, 0(t0)              # pixel (319, 0)
                2:  wfi
                    j    2b
                """);
        long before = m.vga().generation();
        runTicks(m, 1);

        VgaFrame f = m.vga().snapshot();
        assertEquals(VgaDisplay.MODE_320X200X8, f.mode());
        assertEquals(320, f.width());
        assertEquals(200, f.height());
        assertEquals(320, f.pitch());
        assertEquals(0xFF0000, f.paletteEntry(1));
        assertEquals(0x00FF00, f.paletteEntry(2));
        assertTrue(f.generation() > before);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int expected = x == y ? 1 : (x == 319 && y == 0 ? 2 : 0);
                assertEquals(expected, f.pixelByte(y * 320 + x), "pixel " + x + "," + y);
            }

        // the host copy path: exactly the visible bytes, same content
        byte[] buf = new byte[320 * 200 + 16];
        assertEquals(320 * 200, m.vga().copyVisibleInto(buf));
        assertEquals(1, buf[199 * 320 + 199]);
        assertEquals(0, buf[320 * 200]);

        // every row was drawn on (and the mode changed), so all rows are dirty since before
        BitSet rows = m.vga().dirtyRowsSince(before);
        assertEquals(200, rows.cardinality());
    }

    @Test
    void onlyRedrawnRowsAreDirty() {
        // after the first frame, the guest touches one pixel per tick on row 150 only
        Machine m = boot("""
                _start:
                    li   s0, VGA
                    li   t0, 1
                    sw   t0, 0x00(s0)           # MODE 1
                    li   s1, VRAM + 150 * 320
                    li   s2, 0
                loop:
                    lw   t0, 0x10(s0)           # FRAME
                    beq  t0, s2, loop
                    mv   s2, t0
                    addi s3, s3, 1
                    sb   s3, 0(s1)
                    j    loop
                """);
        runTicks(m, 2);
        long gen = m.vga().generation();
        runTicks(m, 1);
        assertEquals(gen + 1, m.vga().generation());
        BitSet rows = m.vga().dirtyRowsSince(gen);
        // row 150 starts at byte 48000, page 11 (45056..49151) also holds rows 141..153
        assertTrue(rows.get(150));
        assertTrue(rows.cardinality() < 20, "only the rows on the written page: " + rows);
    }
}
