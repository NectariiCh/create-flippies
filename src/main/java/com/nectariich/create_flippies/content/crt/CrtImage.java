package com.nectariich.create_flippies.content.crt;

import java.util.BitSet;

import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

/**
 * What a client knows of one CRT's picture (spec 11.2.14): the source's header,
 * palette and visible bytes, kept up to date by {@link CrtFrameCodec} updates. Pure,
 * so applying updates is tested without a client; the texture is made from it by
 * {@link CrtRaster}.
 *
 * A delta is applied only on top of a full frame of the same epoch and layout; any
 * other delta is dropped, and the server's next full frame (sent on first view and
 * on every mode change or restart) puts things right.
 */
public final class CrtImage {

    public static final int MODE_TEXT = VgaDisplay.MODE_TEXT;

    private CrtFrameCodec.Header header;
    private final int[] palette = new int[256];
    private byte[] bytes = new byte[0];

    public CrtImage() {
        for (int i = 0; i < 256; i++)
            palette[i] = VgaDisplay.defaultPaletteEntry(i);
    }

    /** The current header, or null before the first full frame. */
    public CrtFrameCodec.Header header() {
        return header;
    }

    public int paletteEntry(int index) {
        return palette[index & 0xFF];
    }

    /** One visible byte. */
    public int byteAt(int index) {
        return bytes[index] & 0xFF;
    }

    /** The visible bytes, for tests. */
    byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Applies an update. Returns the source rows it changed (every row for a full
     * frame or a palette change), or null if it was dropped.
     */
    public BitSet apply(CrtFrameCodec.Update update) {
        CrtFrameCodec.Header h = update.header();
        if (update.full()) {
            bytes = new byte[h.pitch() * h.height()];
        } else if (!h.sameLayout(header)) {
            return null;
        }
        // The cursor or CTRL moved: every row is drawn again (25 in text mode, where it matters)
        boolean redraw = !update.full() && (h.cursorColumn() != header.cursorColumn()
                || h.cursorRow() != header.cursorRow() || h.ctrl() != header.ctrl());
        header = h;
        int pitch = h.pitch();
        int at = 0;
        BitSet rows = update.rows();
        for (int r = rows.nextSetBit(0); r >= 0; r = rows.nextSetBit(r + 1)) {
            System.arraycopy(update.data(), at, bytes, r * pitch, pitch);
            at += pitch;
        }
        BitSet changed = (BitSet) rows.clone();
        if (update.palette() != null) {
            System.arraycopy(update.palette(), 0, palette, 0, 256);
            changed.set(0, h.height());
        }
        if (update.full() || redraw)
            changed.set(0, h.height());
        return changed;
    }
}
