package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

class CrtFrameCodecTest {

    private static final CrtFrameCodec.Header VGA8 = new CrtFrameCodec.Header(3, 3, 640, 480, 640, 8, 1, 2, 0xD);
    private static final CrtFrameCodec.Header TEXT = new CrtFrameCodec.Header(0, 0, 80, 25, 160, 16, 79, 24, 0xD);

    private static byte[] noise(int length, long seed) {
        byte[] b = new byte[length];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void fullFrameRoundTrip() {
        byte[] frame = noise(640 * 480, 1);
        int[] palette = new int[256];
        for (int i = 0; i < 256; i++)
            palette[i] = i * 0x010203 & 0xFFFFFF;
        BitSet all = new BitSet();
        all.set(0, 480);
        CrtFrameCodec.Update u = CrtFrameCodec.decode(CrtFrameCodec.encode(VGA8, true, palette, frame, all));
        assertEquals(VGA8, u.header());
        assertTrue(u.full());
        assertArrayEquals(palette, u.palette());
        assertEquals(all, u.rows());
        assertArrayEquals(frame, u.data());
    }

    @Test
    void deltaCarriesOnlyItsRowsInRuns() {
        byte[] frame = noise(4000, 2);
        BitSet rows = new BitSet();
        rows.set(0);
        rows.set(5, 8);
        rows.set(24);
        byte[] payload = CrtFrameCodec.encode(TEXT, false, null, frame, rows);
        CrtFrameCodec.Update u = CrtFrameCodec.decode(payload);
        assertFalse(u.full());
        assertNull(u.palette());
        assertEquals(rows, u.rows());
        byte[] expected = new byte[5 * 160];
        System.arraycopy(frame, 0, expected, 0, 160);
        System.arraycopy(frame, 5 * 160, expected, 160, 3 * 160);
        System.arraycopy(frame, 24 * 160, expected, 4 * 160, 160);
        assertArrayEquals(expected, u.data());
    }

    @Test
    void quietPicturesDeflateSmall() {
        // A mostly blank 640x480 screen is far under its 300 KiB
        byte[] frame = new byte[640 * 480];
        Arrays.fill(frame, 0, 640 * 20, (byte) 7);
        BitSet all = new BitSet();
        all.set(0, 480);
        assertTrue(CrtFrameCodec.encode(VGA8, true, null, frame, all).length < 2000);
    }

    @Test
    void clientRejectsMalformedPayloads() {
        BitSet rows = new BitSet();
        rows.set(3);
        byte[] good = CrtFrameCodec.encode(TEXT, false, null, new byte[4000], rows);
        assertNull(CrtFrameCodec.decode(Arrays.copyOf(good, good.length - 1)), "truncated");
        byte[] extra = Arrays.copyOf(good, good.length + 1);
        assertNull(CrtFrameCodec.decode(extra), "trailing bytes");
        byte[] version = good.clone();
        version[0] = 9;
        assertNull(CrtFrameCodec.decode(version), "unknown version");
        assertNull(CrtFrameCodec.decode(new byte[0]));
    }

    @Test
    void encoderRefusesRowsPastTheHeight() {
        BitSet rows = new BitSet();
        rows.set(25);
        assertThrows(IllegalArgumentException.class,
                () -> CrtFrameCodec.encode(TEXT, false, null, new byte[4160], rows));
    }

    @Test
    void imageAppliesFullThenDelta() {
        byte[] frame = noise(4000, 3);
        BitSet all = new BitSet();
        all.set(0, 25);
        CrtImage image = new CrtImage();
        assertEquals(all, image.apply(CrtFrameCodec.decode(CrtFrameCodec.encode(TEXT, true, null, frame, all))));
        frame[160 * 2 + 4] = 99;
        BitSet one = new BitSet();
        one.set(2);
        assertEquals(one, image.apply(CrtFrameCodec.decode(CrtFrameCodec.encode(TEXT, false, null, frame, one))));
        assertArrayEquals(frame, image.bytes());
        // A cursor move with no rows: every row is drawn again
        CrtFrameCodec.Header moved = new CrtFrameCodec.Header(0, 0, 80, 25, 160, 16, 3, 2, 0xD);
        assertEquals(all, image.apply(CrtFrameCodec.decode(CrtFrameCodec.encode(moved, false, null, frame,
                new BitSet()))));
        assertEquals(moved, image.header());
    }
}
