package com.nectariich.create_flippies.content.computer.bus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The saved-state helpers (spec 1.5): paged and deflated byte arrays, rings, versions. */
class StateIoTest {

    private static byte[] pages(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StateIo.writePages(new DataOutputStream(bytes), data);
        return bytes.toByteArray();
    }

    private static DataInputStream in(byte[] b) {
        return new DataInputStream(new ByteArrayInputStream(b));
    }

    @Test
    void zeroRamCostsOnlyTheHeaderAndPageMap() throws IOException {
        byte[] saved = pages(new byte[64 * 1024 * 1024]);
        // length + 16384 pages / 64 = 256 map words + compressed length + an empty deflate stream
        assertTrue(saved.length < 4 + 256 * 8 + 4 + 16, "64 MiB of zeros saved in " + saved.length + " bytes");
        byte[] back = new byte[64 * 1024 * 1024];
        back[12345] = 7;
        StateIo.readPages(in(saved), back, "RAM");
        assertEquals(0, back[12345], "pages not in the map are cleared");
    }

    @Test
    void sparseAndRandomContentRoundTrips() throws IOException {
        byte[] data = new byte[1024 * 1024];
        new Random(1).nextBytes(data);
        java.util.Arrays.fill(data, 4096, 200_000, (byte) 0);
        data[150_000] = 9;
        data[data.length - 1] = 1;
        byte[] back = new byte[data.length];
        StateIo.readPages(in(pages(data)), back, "RAM");
        assertArrayEquals(data, back);
    }

    @Test
    void aPartialLastPageRoundTrips() throws IOException {
        byte[] data = new byte[4096 + 100];
        data[4096 + 99] = 5;
        byte[] back = new byte[data.length];
        StateIo.readPages(in(pages(data)), back, "X");
        assertArrayEquals(data, back);
    }

    @Test
    void aDifferentSizeIsRejectedWithBothSizes() throws IOException {
        byte[] saved = pages(new byte[8192]);
        StateFormatException e = assertThrows(StateFormatException.class,
                () -> StateIo.readPages(in(saved), new byte[16384], "RAM"));
        assertTrue(e.getMessage().contains("16384") && e.getMessage().contains("8192"), e.getMessage());
    }

    @Test
    void corruptOrTruncatedPagesAreRejected() throws IOException {
        byte[] data = new byte[16384];
        new Random(2).nextBytes(data);
        byte[] saved = pages(data);
        byte[] corrupt = saved.clone();
        for (int i = saved.length - 40; i < saved.length - 20; i++)
            corrupt[i] ^= 0x5A;
        assertThrows(IOException.class, () -> StateIo.readPages(in(corrupt), new byte[16384], "RAM"));
        byte[] truncated = java.util.Arrays.copyOf(saved, saved.length - 10);
        assertThrows(IOException.class, () -> StateIo.readPages(in(truncated), new byte[16384], "RAM"));
    }

    @Test
    void ringsAreStoredOldestFirst() throws IOException {
        byte[] ring = { 3, 4, 0, 0, 1, 2 };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StateIo.writeRing(new DataOutputStream(bytes), ring, 4, 4);
        byte[] back = new byte[6];
        assertEquals(4, StateIo.readRing(in(bytes.toByteArray()), back, "ring"));
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 0, 0 }, back);
        byte[] tooLong = { 0, 0, 0, 9, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
        assertThrows(StateFormatException.class, () -> StateIo.readRing(in(tooLong), new byte[6], "ring"));
    }

    @Test
    void versionsOutsideOneToCurrentAreRejected() throws IOException {
        assertEquals(1, StateIo.readVersion(in(new byte[] { 0, 0, 0, 1 }), "x", 1));
        assertThrows(StateFormatException.class, () -> StateIo.readVersion(in(new byte[] { 0, 0, 0, 2 }), "x", 1));
        assertThrows(StateFormatException.class, () -> StateIo.readVersion(in(new byte[] { 0, 0, 0, 0 }), "x", 1));
    }
}
