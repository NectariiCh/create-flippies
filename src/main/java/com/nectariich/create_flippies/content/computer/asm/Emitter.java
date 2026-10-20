package com.nectariich.create_flippies.content.computer.asm;

import java.nio.ByteOrder;

/**
 * Writes one statement's bytes into the image in the backend's byte order. In bss there is no
 * array: the emitter only counts and remembers whether anything nonzero was written.
 */
public final class Emitter {

    private final ByteOrder order;
    private byte[] buf;
    private int pos;
    private int count;
    private boolean nonZero;

    Emitter(ByteOrder order) {
        this.order = order;
    }

    /** Starts a statement at {@code pos} of {@code buf} (null for bss). */
    void start(byte[] buf, int pos) {
        this.buf = buf;
        this.pos = pos;
        this.count = 0;
        this.nonZero = false;
    }

    int count() {
        return count;
    }

    boolean nonZero() {
        return nonZero;
    }

    public ByteOrder order() {
        return order;
    }

    public void u8(int v) {
        if ((v & 0xFF) != 0)
            nonZero = true;
        if (buf != null) {
            if (pos >= buf.length)
                throw new IllegalStateException("emitter overran its section");
            buf[pos++] = (byte) v;
        }
        count++;
    }

    /** The low {@code size} bytes of {@code v} in byte order. */
    public void value(long v, int size) {
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < size; i++)
                u8((int) (v >>> (8 * i)));
        } else {
            for (int i = size - 1; i >= 0; i--)
                u8((int) (v >>> (8 * i)));
        }
    }

    public void u16(int v) {
        value(v, 2);
    }

    public void u32(int v) {
        value(v, 4);
    }

    public void u64(long v) {
        value(v, 8);
    }

    /** Bytes of a decoded string (one char per byte). */
    public void bytes(String latin1) {
        for (int i = 0; i < latin1.length(); i++)
            u8(latin1.charAt(i));
    }

    public void bytes(byte[] b) {
        for (byte x : b)
            u8(x);
    }

    public void fill(long n, int b) {
        if (n <= 0)
            return;
        if ((b & 0xFF) != 0)
            nonZero = true;
        if (buf != null) {
            if (pos + n > buf.length)
                throw new IllegalStateException("emitter overran its section");
            java.util.Arrays.fill(buf, pos, (int) (pos + n), (byte) b);
            pos += (int) n;
        }
        count += (int) n;
    }
}
