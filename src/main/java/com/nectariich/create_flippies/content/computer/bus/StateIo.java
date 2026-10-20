package com.nectariich.create_flippies.content.computer.bus;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Helpers for the saved-state format shared by CPUs, devices and the machine (spec 1.5): format
 * version checks, bounded counts, enums by name, nullable strings, and large byte arrays (RAM,
 * VRAM) stored as a map of non-zero 4 KiB pages followed by those pages deflated.
 */
public final class StateIo {

    private StateIo() {
    }

    /** Page size for {@link #writePages}; all-zero pages are not stored. */
    public static final int PAGE = 4096;

    private static final byte[] ZERO_PAGE = new byte[PAGE];

    /** Reads a component's format version; StateFormatException unless it is 1..{@code current}. */
    public static int readVersion(DataInput in, String what, int current) throws IOException {
        int v = in.readInt();
        if (v < 1 || v > current)
            throw new StateFormatException(what + " state version " + v + " is not supported (this build reads 1 to " + current + ")");
        return v;
    }

    /** Reads an int in {@code [0, max]}; StateFormatException otherwise. */
    public static int readCount(DataInput in, int max, String what) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max)
            throw new StateFormatException(what + " " + n + " is outside 0 to " + max);
        return n;
    }

    /** Throws StateFormatException with {@code message} unless {@code ok}. */
    public static void check(boolean ok, String message) throws StateFormatException {
        if (!ok)
            throw new StateFormatException(message);
    }

    public static void writeEnum(DataOutput out, Enum<?> value) throws IOException {
        out.writeUTF(value.name());
    }

    public static <E extends Enum<E>> E readEnum(DataInput in, Class<E> type) throws IOException {
        String name = in.readUTF();
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new StateFormatException("unknown " + type.getSimpleName() + " " + name);
        }
    }

    /** A string or null; strings longer than the UTF limit are not expected (halt reasons, names). */
    public static void writeNullableUtf(DataOutput out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null)
            out.writeUTF(s);
    }

    public static String readNullableUtf(DataInput in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    /** {@code count} bytes of {@code ring} from {@code head}, wrapping, written oldest first. */
    public static void writeRing(DataOutput out, byte[] ring, int head, int count) throws IOException {
        out.writeInt(count);
        int first = Math.min(count, ring.length - head);
        out.write(ring, head, first);
        out.write(ring, 0, count - first);
    }

    /** Reads what {@link #writeRing} wrote into {@code ring} from index 0; returns the count. */
    public static int readRing(DataInput in, byte[] ring, String what) throws IOException {
        int count = readCount(in, ring.length, what + " length");
        in.readFully(ring, 0, count);
        return count;
    }

    /**
     * Writes {@code data}: its length, a bitmap with one bit per 4 KiB page that holds a non-zero
     * byte, then those pages in order, deflated at the fastest level. An all-zero RAM costs a few
     * bytes per MiB; a full one costs what deflate makes of it.
     */
    public static void writePages(DataOutput out, byte[] data) throws IOException {
        int pages = (data.length + PAGE - 1) / PAGE;
        long[] map = new long[(pages + 63) >>> 6];
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream z = new DeflaterOutputStream(packed, deflater, 64 * 1024)) {
            for (int p = 0; p < pages; p++) {
                int from = p * PAGE;
                int len = Math.min(PAGE, data.length - from);
                if (Arrays.mismatch(data, from, from + len, ZERO_PAGE, 0, len) >= 0) {
                    map[p >>> 6] |= 1L << (p & 63);
                    z.write(data, from, len);
                }
            }
        } finally {
            deflater.end();
        }
        out.writeInt(data.length);
        for (long w : map)
            out.writeLong(w);
        out.writeInt(packed.size());
        out.write(packed.toByteArray());
    }

    /**
     * Reads what {@link #writePages} wrote into {@code data}, which must have the saved length
     * (StateFormatException naming {@code what} otherwise). Pages not in the map become zero.
     */
    public static void readPages(DataInput in, byte[] data, String what) throws IOException {
        int length = in.readInt();
        if (length != data.length)
            throw new StateFormatException(what + " is " + data.length + " bytes here but " + length + " bytes in the saved state");
        int pages = (length + PAGE - 1) / PAGE;
        long[] map = new long[(pages + 63) >>> 6];
        for (int i = 0; i < map.length; i++)
            map[i] = in.readLong();
        if ((pages & 63) != 0 && (map[map.length - 1] >>> (pages & 63)) != 0)
            throw new StateFormatException(what + " page map marks pages past the end");
        // deflate never grows data by more than a few bytes per 16 KiB block
        int packedLength = readCount(in, length + length / 64 + 1024, what + " compressed length");
        byte[] packed = new byte[packedLength];
        in.readFully(packed);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            for (int p = 0; p < pages; p++) {
                int from = p * PAGE;
                int len = Math.min(PAGE, length - from);
                if ((map[p >>> 6] & (1L << (p & 63))) == 0) {
                    Arrays.fill(data, from, from + len, (byte) 0);
                    continue;
                }
                int got = 0;
                while (got < len) {
                    int n = inflater.inflate(data, from + got, len - got);
                    if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()))
                        throw new StateFormatException(what + " compressed pages end early");
                    got += n;
                }
            }
            if (inflater.inflate(new byte[1]) != 0 || !inflater.finished() || inflater.getRemaining() != 0)
                throw new StateFormatException(what + " compressed pages have trailing data");
        } catch (DataFormatException e) {
            throw new StateFormatException(what + " compressed pages are corrupt", e);
        } finally {
            inflater.end();
        }
    }
}
