package com.nectariich.create_flippies.content.computer.device;

import static com.nectariich.create_flippies.content.computer.device.VgaDisplay.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nectariich.create_flippies.content.computer.bus.IrqSink;

import java.io.IOException;
import java.util.BitSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Three displays over one 1.5 MiB VRAM array, each on its own 512 KiB slice (spec 11.1.3). */
class VgaSharedVramTest {

    private byte[] vram;
    private long[] dirty;
    private VgaDisplay[] vga;
    private DeviceRig.Line[] lines;

    @BeforeEach
    void setUp() {
        vram = new byte[3 * VRAM_SIZE];
        dirty = new long[6];
        vga = new VgaDisplay[3];
        lines = new DeviceRig.Line[3];
        for (int n = 0; n < 3; n++) {
            lines[n] = new DeviceRig.Line();
            vga[n] = new VgaDisplay(lines[n], vram, n * VRAM_SIZE, dirty);
            vga[n].reset();
            vga[n].endFrame();
        }
    }

    /** A guest store into the array, as the CPU's fast path does it. */
    private void store(int index, byte value) {
        vram[index] = value;
        dirty[index >>> 18] |= 1L << ((index >>> 12) & 63);
    }

    @Test
    void slicesMustFitTheArray() {
        assertThrows(IllegalArgumentException.class, () -> new VgaDisplay(IrqSink.NONE, vram, 1024, dirty));
        assertThrows(IllegalArgumentException.class, () -> new VgaDisplay(IrqSink.NONE, vram, 3 * VRAM_SIZE, dirty));
        assertThrows(IllegalArgumentException.class, () -> new VgaDisplay(IrqSink.NONE, vram, 2 * VRAM_SIZE, new long[4]));
        assertEquals(2 * VRAM_SIZE, vga[2].vramOffset());
    }

    @Test
    void registersAreIndependent() {
        vga[1].write(MODE, 4, MODE_640X480X8);
        vga[2].write(CURSOR, 4, 0x0102);
        vga[0].write(PALETTE + 4, 4, 0x123456);
        assertEquals(MODE_TEXT, vga[0].mode());
        assertEquals(MODE_640X480X8, vga[1].read(MODE, 4));
        assertEquals(0, vga[1].read(CURSOR, 4));
        assertEquals(0x0102, vga[2].read(CURSOR, 4));
        assertEquals(0x123456, vga[0].palette(1));
        assertEquals(defaultPaletteEntry(1), vga[2].palette(1));
    }

    @Test
    void dirtyRowsOfOneDoNotTouchAnother() {
        long[] gen = new long[3];
        for (int n = 0; n < 3; n++)
            gen[n] = vga[n].generation();
        store(VRAM_SIZE + 160 * 3, (byte) 'X');     // display 1, text row 3
        for (VgaDisplay v : vga)
            v.endFrame();
        assertEquals(gen[0], vga[0].generation(), "display 0 saw no change");
        assertEquals(gen[2], vga[2].generation(), "display 2 saw no change");
        BitSet rows = vga[1].dirtyRowsSince(gen[1]);
        assertTrue(rows.get(3));
        assertEquals(0, dirty[2] | dirty[3], "display 1 cleared its own page bits");
        assertEquals('X', vga[1].snapshot().pixelByte(160 * 3));
        assertEquals(0, vga[0].snapshot().pixelByte(160 * 3));
        // another display's page bits survive a display's frame end until its own
        store(2 * VRAM_SIZE, (byte) 1);
        vga[0].endFrame();
        assertTrue(dirty[4] != 0);
        vga[2].endFrame();
        assertEquals(0, dirty[4]);
    }

    @Test
    void startWrapsInsideTheSlice() {
        VgaDisplay v = vga[1];
        v.write(START, 4, VRAM_SIZE - 160);
        vram[2 * VRAM_SIZE - 160] = 'A';     // the last row of display 1's slice
        vram[VRAM_SIZE] = 'B';               // display 1's first byte
        vram[2 * VRAM_SIZE] = 'C';           // display 2's first byte, never shown by display 1
        byte[] buf = new byte[4000];
        v.copyVisibleInto(buf);
        assertEquals('A', buf[0]);
        assertEquals('B', buf[160], "the frame wraps to the slice's start");
        v.write(START, 4, VRAM_SIZE + 4);
        assertEquals(4, v.start(), "START wraps inside 512 KiB");
    }

    @Test
    void vblankOnEachDisplaysOwnLine() {
        for (int n = 0; n < 3; n++)
            vga[n].write(STATUS, 4, STATUS_VBLANK);
        vga[2].write(CTRL, 4, CTRL_RESET | CTRL_VBLANK_IRQ);
        for (VgaDisplay v : vga)
            v.endFrame();
        assertFalse(lines[0].level);
        assertFalse(lines[1].level);
        assertTrue(lines[2].level);
    }

    @Test
    void savedStateHoldsOnlyTheSlice() throws IOException {
        store(VRAM_SIZE + 10, (byte) 7);
        store(10, (byte) 9);
        byte[] one = DeviceStateTest.save(vga[1]);
        byte[] other = new byte[3 * VRAM_SIZE];
        long[] otherDirty = new long[6];
        VgaDisplay b = new VgaDisplay(IrqSink.NONE, other, VRAM_SIZE, otherDirty);
        DeviceStateTest.load(b, one);
        assertEquals(7, other[VRAM_SIZE + 10]);
        assertEquals(0, other[10], "display 0's bytes are not in display 1's section");
        assertEquals(dirty[2], otherDirty[2]);
        assertEquals(0, otherDirty[0]);
        assertArrayEquals(one, DeviceStateTest.save(b));
    }
}
