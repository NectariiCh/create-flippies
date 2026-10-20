package com.nectariich.create_flippies.content.crt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.BitSet;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The bytes of one picture update sent to a client (spec 11.2.14). Pure, so the
 * round trip is tested without a network.
 *
 * Layout (big-endian, varints are 7 bits per byte, low group first):
 * <pre>
 *   byte    version (1)
 *   byte    flags: bit 0 full frame (the client starts over), bit 1 palette follows
 *   varint  epoch (changes when the source restarts or changes mode)
 *   byte    mode; short width, height, pitch; byte bits per pixel
 *   byte    cursor column, cursor row; byte CTRL (display, cursor, blink bits)
 *   [palette: 256 x 3 bytes R, G, B]
 *   varint  run count; per run: varint first row, varint row count
 *   varint  deflated length; the deflated rows, PITCH bytes each, in run order
 * </pre>
 * Rows are the display's rows: pixel rows in graphics modes, text rows (160 bytes)
 * in text mode. The raw VRAM bytes go over the wire, indexed through the palette,
 * so a 640x480 frame at 8 bpp is 300 KiB before deflate and text mode is 4000.
 */
public final class CrtFrameCodec {

    public static final int VERSION = 1;
    public static final int FLAG_FULL = 1, FLAG_PALETTE = 2;
    /** The most visible bytes any source may have: one display's VRAM. */
    public static final int MAX_FRAME_BYTES = 512 * 1024;
    public static final int MAX_WIDTH = 1024, MAX_HEIGHT = 1024, MAX_PITCH = 4096;

    /** What the picture is: the source's geometry, cursor and CTRL, and which run of the source it belongs to. */
    public record Header(int epoch, int mode, int width, int height, int pitch, int bitsPerPixel,
            int cursorColumn, int cursorRow, int ctrl) {

        /** Whether two headers describe the same layout of bytes. */
        public boolean sameLayout(Header other) {
            return other != null && epoch == other.epoch && mode == other.mode && width == other.width
                    && height == other.height && pitch == other.pitch && bitsPerPixel == other.bitsPerPixel;
        }

        boolean valid() {
            return mode >= 0 && mode <= 3 && width >= 1 && width <= MAX_WIDTH && height >= 1 && height <= MAX_HEIGHT
                    && pitch >= 1 && pitch <= MAX_PITCH && (long) pitch * height <= MAX_FRAME_BYTES
                    && (mode == 0 ? bitsPerPixel == 16 : bitsPerPixel == 4 || bitsPerPixel == 8)
                    && (long) width * bitsPerPixel <= (long) pitch * 8;
        }
    }

    /** One decoded update: the rows in {@code rows} carry {@code data}, PITCH bytes each, in ascending order. */
    public record Update(Header header, boolean full, int[] palette, BitSet rows, byte[] data) {}

    private CrtFrameCodec() {}

    /**
     * Encodes the rows {@code rows} of {@code frame} (the visible bytes, PITCH per
     * row). {@code palette} is sent when not null (256 entries of 0x00RRGGBB).
     */
    public static byte[] encode(Header header, boolean full, int[] palette, byte[] frame, BitSet rows) {
        if (!header.valid())
            throw new IllegalArgumentException("bad header " + header);
        if (rows.length() > header.height())
            throw new IllegalArgumentException("row " + (rows.length() - 1) + " past " + header.height());
        int pitch = header.pitch();
        byte[] raw = new byte[rows.cardinality() * pitch];
        int at = 0;
        for (int r = rows.nextSetBit(0); r >= 0; r = rows.nextSetBit(r + 1)) {
            System.arraycopy(frame, r * pitch, raw, at, pitch);
            at += pitch;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + raw.length / 4);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(VERSION);
            out.writeByte((full ? FLAG_FULL : 0) | (palette != null ? FLAG_PALETTE : 0));
            writeVarint(out, header.epoch());
            out.writeByte(header.mode());
            out.writeShort(header.width());
            out.writeShort(header.height());
            out.writeShort(header.pitch());
            out.writeByte(header.bitsPerPixel());
            out.writeByte(header.cursorColumn());
            out.writeByte(header.cursorRow());
            out.writeByte(header.ctrl());
            if (palette != null)
                for (int i = 0; i < 256; i++) {
                    out.writeByte(palette[i] >>> 16);
                    out.writeByte(palette[i] >>> 8);
                    out.writeByte(palette[i]);
                }
            int runs = 0;
            for (int r = rows.nextSetBit(0); r >= 0; r = rows.nextSetBit(rows.nextClearBit(r)))
                runs++;
            writeVarint(out, runs);
            for (int r = rows.nextSetBit(0); r >= 0; ) {
                int end = rows.nextClearBit(r);
                writeVarint(out, r);
                writeVarint(out, end - r);
                r = rows.nextSetBit(end);
            }
            byte[] packed = deflate(raw);
            writeVarint(out, packed.length);
            out.write(packed);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decodes an update, or null if it is not a well-formed one (a client never trusts the bytes). */
    public static Update decode(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readUnsignedByte() != VERSION)
                return null;
            int flags = in.readUnsignedByte();
            Header header = new Header(readVarint(in), in.readUnsignedByte(), in.readUnsignedShort(),
                    in.readUnsignedShort(), in.readUnsignedShort(), in.readUnsignedByte(), in.readUnsignedByte(),
                    in.readUnsignedByte(), in.readUnsignedByte());
            if (!header.valid())
                return null;
            int[] palette = null;
            if ((flags & FLAG_PALETTE) != 0) {
                palette = new int[256];
                for (int i = 0; i < 256; i++)
                    palette[i] = in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte();
            }
            int runs = readVarint(in);
            if (runs < 0 || runs > header.height())
                return null;
            BitSet rows = new BitSet(header.height());
            int last = -1;
            for (int i = 0; i < runs; i++) {
                int first = readVarint(in), count = readVarint(in);
                if (first <= last || count < 1 || (long) first + count > header.height())
                    return null;
                rows.set(first, first + count);
                last = first + count - 1;
            }
            int packedLength = readVarint(in);
            if (packedLength < 0 || packedLength > in.available())
                return null;
            byte[] packed = new byte[packedLength];
            in.readFully(packed);
            byte[] data = inflate(packed, rows.cardinality() * header.pitch());
            if (data == null || in.available() != 0)
                return null;
            return new Update(header, (flags & FLAG_FULL) != 0, palette, rows, data);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] chunk = new byte[16384];
            while (!deflater.finished())
                out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Exactly {@code length} bytes or null: never more, so a hostile payload cannot balloon. */
    private static byte[] inflate(byte[] packed, int length) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            byte[] out = new byte[length];
            int at = 0;
            while (at < length) {
                int n = inflater.inflate(out, at, length - at);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()))
                    return null;
                at += n;
            }
            if (!inflater.finished()) {
                // Nothing may follow the expected bytes
                byte[] probe = new byte[1];
                if (inflater.inflate(probe) != 0 || !inflater.finished())
                    return null;
            }
            return out;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    static void writeVarint(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarint(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return value;
        }
        throw new IOException("varint too long");
    }
}
