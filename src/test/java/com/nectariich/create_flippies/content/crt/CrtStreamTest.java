package com.nectariich.create_flippies.content.crt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import org.junit.jupiter.api.Test;

/** The server's dirty-row diffing against a real display, and what a client rebuilds from it. */
class CrtStreamTest {

    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2);

    /** The same reads {@code PcDisplayView} makes. */
    private record View(VgaDisplay d) implements CrtStream.Source {
        public Object identity() {
            return d;
        }

        public long generation() {
            return d.generation();
        }

        public int mode() {
            return d.mode();
        }

        public int width() {
            return d.width();
        }

        public int height() {
            return d.height();
        }

        public int pitch() {
            return d.pitch();
        }

        public int bitsPerPixel() {
            return d.bitsPerPixel();
        }

        public int ctrl() {
            return d.ctrl();
        }

        public int cursor() {
            return d.read(VgaDisplay.CURSOR, 4);
        }

        public int palette(int index) {
            return d.palette(index);
        }

        public int copyVisibleInto(byte[] dst) {
            return d.copyVisibleInto(dst);
        }

        public BitSet dirtyRowsSince(long generation) {
            return d.dirtyRowsSince(generation);
        }
    }

    private static VgaDisplay graphics() {
        VgaDisplay d = new VgaDisplay(IrqSink.NONE);
        d.write(VgaDisplay.MODE, 4, VgaDisplay.MODE_640X480X8);
        d.endFrame();
        return d;
    }

    /** A guest store: the byte, and its page's dirty bit as the CPU sets it. */
    private static void poke(VgaDisplay d, int offset, int value) {
        d.vram()[d.vramOffset() + offset] = (byte) value;
        int page = offset >>> 12;
        d.dirtyPages()[(d.vramOffset() >>> 18) + (page >>> 6)] |= 1L << (page & 63);
    }

    private static CrtFrameCodec.Update decode(byte[] payload) {
        CrtFrameCodec.Update u = CrtFrameCodec.decode(payload);
        assertNotNull(u, "payload decodes");
        return u;
    }

    @Test
    void firstViewIsAFullFrameThenOnlyChangedRows() {
        VgaDisplay d = graphics();
        View v = new View(d);
        poke(d, 5 * 640 + 3, 42);
        d.endFrame();
        CrtStream stream = new CrtStream();
        stream.capture(v);

        CrtFrameCodec.Update first = decode(stream.updateFor(ALICE, 0, 1));
        assertTrue(first.full());
        assertNotNull(first.palette());
        assertEquals(480, first.rows().cardinality());

        CrtImage image = new CrtImage();
        assertEquals(480, image.apply(first).cardinality());
        assertArrayEquals(d.snapshot().pixels(), image.bytes());

        // Nothing changed: nothing to send
        stream.capture(v);
        assertNull(stream.updateFor(ALICE, 1, 1));

        // One row touched: one row goes, no palette
        poke(d, 300 * 640 + 7, 9);
        d.endFrame();
        stream.capture(v);
        CrtFrameCodec.Update delta = decode(stream.updateFor(ALICE, 2, 1));
        assertFalse(delta.full());
        assertNull(delta.palette());
        BitSet expected = new BitSet();
        expected.set(300);
        assertEquals(expected, delta.rows());
        assertEquals(640, delta.data().length);
        assertEquals(expected, image.apply(delta));
        assertArrayEquals(d.snapshot().pixels(), image.bytes());
    }

    @Test
    void aSlowPlayerGetsEveryRowItMissedInOneUpdate() {
        VgaDisplay d = graphics();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        CrtImage image = new CrtImage();
        image.apply(decode(stream.updateFor(ALICE, 0, 4)));

        poke(d, 10 * 640, 1);
        d.endFrame();
        stream.capture(v);
        // Inside the interval: held back, not lost
        assertNull(stream.updateFor(ALICE, 1, 4));
        poke(d, 20 * 640, 2);
        d.endFrame();
        stream.capture(v);
        assertNull(stream.updateFor(ALICE, 3, 4));

        CrtFrameCodec.Update u = decode(stream.updateFor(ALICE, 4, 4));
        assertEquals(2, u.rows().cardinality());
        assertTrue(u.rows().get(10) && u.rows().get(20));
        image.apply(u);
        assertArrayEquals(d.snapshot().pixels(), image.bytes());
    }

    @Test
    void paletteChangeSendsThePaletteAndEveryRow() {
        VgaDisplay d = graphics();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        CrtImage image = new CrtImage();
        image.apply(decode(stream.updateFor(ALICE, 0, 1)));

        d.write(VgaDisplay.PALETTE + 4 * 7, 4, 0x123456);
        d.endFrame();
        stream.capture(v);
        CrtFrameCodec.Update u = decode(stream.updateFor(ALICE, 1, 1));
        assertFalse(u.full());
        assertNotNull(u.palette());
        image.apply(u);
        assertEquals(0x123456, image.paletteEntry(7));
    }

    @Test
    void modeChangeAndRestartStartANewEpochWithAFullFrame() {
        VgaDisplay d = graphics();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        CrtImage image = new CrtImage();
        image.apply(decode(stream.updateFor(ALICE, 0, 1)));
        int epoch = stream.epoch();

        d.write(VgaDisplay.MODE, 4, VgaDisplay.MODE_TEXT);
        d.endFrame();
        stream.capture(v);
        assertEquals(epoch + 1, stream.epoch());
        CrtFrameCodec.Update text = decode(stream.updateFor(ALICE, 1, 1));
        assertTrue(text.full());
        assertEquals(25, text.rows().cardinality());
        assertEquals(4000, text.data().length);
        image.apply(text);
        assertArrayEquals(d.snapshot().pixels(), image.bytes());

        // The PC was switched off and on: a new display, even with a higher generation
        VgaDisplay fresh = new VgaDisplay(IrqSink.NONE);
        for (int i = 0; i < 10; i++) {
            fresh.write(VgaDisplay.CURSOR, 4, i);
            fresh.endFrame();
        }
        View w = new View(fresh);
        stream.capture(w);
        assertEquals(epoch + 2, stream.epoch());
        assertTrue(decode(stream.updateFor(ALICE, 2, 1)).full());

        // No signal, then the same display back: still a new epoch
        stream.clear();
        stream.capture(w);
        assertEquals(epoch + 3, stream.epoch());
        assertTrue(decode(stream.updateFor(ALICE, 3, 1)).full());
    }

    @Test
    void aDeltaFromAnotherEpochIsDroppedByTheClient() {
        VgaDisplay d = graphics();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        CrtImage image = new CrtImage();
        image.apply(decode(stream.updateFor(ALICE, 0, 1)));

        CrtFrameCodec.Header h = stream.header();
        CrtFrameCodec.Header other = new CrtFrameCodec.Header(h.epoch() + 1, h.mode(), h.width(), h.height(),
                h.pitch(), h.bitsPerPixel(), 0, 0, h.ctrl());
        BitSet rows = new BitSet();
        rows.set(0);
        byte[] payload = CrtFrameCodec.encode(other, false, null, new byte[640 * 480], rows);
        assertNull(image.apply(decode(payload)));
        // And before any full frame, any delta
        assertNull(new CrtImage().apply(decode(payload)));
    }

    @Test
    void playersInTheSameStateShareOneEncodingAndLeaversStartOver() {
        VgaDisplay d = graphics();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        byte[] a = stream.updateFor(ALICE, 0, 1);
        byte[] b = stream.updateFor(BOB, 0, 1);
        assertTrue(a == b, "the same full frame is encoded once per tick");

        stream.retainViewers(Set.of(ALICE));
        poke(d, 0, 5);
        d.endFrame();
        stream.capture(v);
        assertFalse(decode(stream.updateFor(ALICE, 1, 1)).full());
        assertTrue(decode(stream.updateFor(BOB, 1, 1)).full());
    }

    @Test
    void storesThatChangeNothingAndCursorMoves() {
        VgaDisplay d = new VgaDisplay(IrqSink.NONE);
        d.endFrame();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        stream.updateFor(ALICE, 0, 1);
        // The same byte stored again: the page is dirty, the picture is not
        poke(d, 0, 0);
        d.endFrame();
        stream.capture(v);
        assertNull(stream.updateFor(ALICE, 1, 1));
        // The cursor moves: an update with the header and no rows (the client redraws itself)
        d.write(VgaDisplay.CURSOR, 4, 0x0205);
        d.endFrame();
        stream.capture(v);
        CrtFrameCodec.Update u = decode(stream.updateFor(ALICE, 2, 1));
        assertTrue(u.rows().isEmpty());
        assertEquals(5, u.header().cursorColumn());
        assertEquals(2, u.header().cursorRow());
    }

    @Test
    void writesOutsideThePictureSendNothing() {
        VgaDisplay d = graphics();
        View v = new View(d);
        CrtStream stream = new CrtStream();
        stream.capture(v);
        stream.updateFor(ALICE, 0, 1);
        // Past the 300 KiB picture: a new generation but no visible row
        poke(d, 400 * 1024, 1);
        d.endFrame();
        stream.capture(v);
        assertNull(stream.updateFor(ALICE, 1, 1));
    }
}
