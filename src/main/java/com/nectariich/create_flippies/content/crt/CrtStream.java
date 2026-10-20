package com.nectariich.create_flippies.content.crt;

import java.lang.ref.WeakReference;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One CRT's picture on the server, and what each player watching it has been sent
 * (spec 11.2.14). Pure apart from player ids, so the diffing is tested against a
 * real {@code VgaDisplay}.
 *
 * Every tick the stream {@link #capture captures} its source: one generation compare
 * when nothing changed, else one copy of the visible bytes. The core marks rows dirty
 * by 4 KiB page, so a one-byte store marks every row sharing its page; the stream
 * compares just those rows with what it held and stamps only the rows whose bytes
 * really changed with a new capture sequence number. A header change (cursor, CTRL)
 * is stamped too, with no rows.
 *
 * The epoch moves when the source is a different display (the PC was switched off and
 * on, or unloaded and loaded), when its generation went backwards, when its layout
 * (mode, size, pitch, depth) changed, and after a tick without a signal; a player on
 * an older epoch gets a full frame. A player on the current epoch gets the rows
 * stamped after the sequence number it last received, the palette if it changed, and
 * the header.
 */
public final class CrtStream {

    /** A display as the stream reads it: the core's dirty rows and generation API (spec 3.7). */
    public interface Source {
        /** The display object itself, compared by identity to see a restart. */
        Object identity();

        long generation();

        int mode();

        int width();

        int height();

        int pitch();

        int bitsPerPixel();

        int ctrl();

        /** Bits 7..0 column, 15..8 row. */
        int cursor();

        int palette(int index);

        int copyVisibleInto(byte[] dst);

        BitSet dirtyRowsSince(long generation);
    }

    private static final class Viewer {
        int epoch = -1;
        long seq;
        int paletteVersion;
        long lastTick = Long.MIN_VALUE / 2;
    }

    private WeakReference<Object> identity = new WeakReference<>(null);
    private boolean live;
    private int epoch;
    /** The source's generation at the last capture. */
    private long generation;
    /** Bumped by every capture that changed anything a client sees. */
    private long seq;
    private long[] rowSeq = new long[0];
    private long headerSeq;
    private CrtFrameCodec.Header header;
    private byte[] frame = new byte[0];
    private byte[] next = new byte[0];
    private final int[] palette = new int[256];
    private int paletteVersion;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    /** Encoded updates of this tick, shared by players in the same state. */
    private final Map<String, byte[]> encoded = new HashMap<>();

    /** No source now (no power, no cord, PC off or unloaded): the next picture is a new epoch. */
    public void clear() {
        if (live) {
            live = false;
            epoch++;
        }
        viewers.clear();
        encoded.clear();
    }

    public boolean live() {
        return live;
    }

    public int epoch() {
        return epoch;
    }

    public CrtFrameCodec.Header header() {
        return header;
    }

    /** Reads the source for this tick. */
    public void capture(Source source) {
        encoded.clear();
        long gen = source.generation();
        boolean restarted = !live || identity.get() != source.identity() || gen < generation;
        boolean relaid = header == null || header.mode() != source.mode() || header.width() != source.width()
                || header.height() != source.height() || header.pitch() != source.pitch()
                || header.bitsPerPixel() != source.bitsPerPixel();
        boolean fresh = restarted || relaid;
        if (live && fresh)
            epoch++;
        int pitch = source.pitch(), height = source.height();
        if (fresh) {
            seq++;
            frame = new byte[pitch * height];
            next = new byte[pitch * height];
            rowSeq = new long[height];
            java.util.Arrays.fill(rowSeq, seq);
            source.copyVisibleInto(frame);
        } else if (gen != generation) {
            source.copyVisibleInto(next);
            BitSet dirty = source.dirtyRowsSince(generation);
            boolean any = false;
            for (int r = dirty.nextSetBit(0); r >= 0 && r < height; r = dirty.nextSetBit(r + 1)) {
                if (java.util.Arrays.equals(frame, r * pitch, (r + 1) * pitch, next, r * pitch, (r + 1) * pitch))
                    continue;
                if (!any) {
                    seq++;
                    any = true;
                }
                rowSeq[r] = seq;
            }
            byte[] t = frame;
            frame = next;
            next = t;
        }
        if (fresh || gen != generation) {
            boolean paletteChanged = false;
            for (int i = 0; i < 256; i++) {
                int c = source.palette(i);
                if (c != palette[i]) {
                    palette[i] = c;
                    paletteChanged = true;
                }
            }
            if (paletteChanged)
                paletteVersion++;
            generation = gen;
        }
        identity = new WeakReference<>(source.identity());
        int cursor = source.cursor();
        CrtFrameCodec.Header h = new CrtFrameCodec.Header(epoch, source.mode(), source.width(), height, pitch,
                source.bitsPerPixel(), cursor & 0xFF, (cursor >>> 8) & 0xFF, source.ctrl() & 0xFF);
        if (!fresh && !h.equals(header))
            headerSeq = ++seq;
        header = h;
        live = true;
    }

    /** Forgets the players no longer watching, so one coming back starts with a full frame. */
    public void retainViewers(Set<UUID> watching) {
        viewers.keySet().retainAll(watching);
    }

    /**
     * The update {@code player} should get now, or null: not due yet ({@code
     * intervalTicks} since its last), or nothing changed for it. A returned update
     * counts as sent. Call after {@link #capture} in the same tick.
     */
    public byte[] updateFor(UUID player, long now, int intervalTicks) {
        if (!live)
            return null;
        Viewer v = viewers.computeIfAbsent(player, id -> new Viewer());
        if (!CrtRateLimit.due(v.lastTick, now, intervalTicks))
            return null;
        boolean full = v.epoch != epoch;
        boolean withPalette = full || v.paletteVersion != paletteVersion;
        if (!full && v.seq == seq && !withPalette)
            return null;
        String key = full ? "full" : v.seq + (withPalette ? "p" : "");
        byte[] payload = encoded.get(key);
        if (payload == null) {
            BitSet rows = new BitSet(header.height());
            for (int r = 0; r < header.height(); r++)
                if (full || rowSeq[r] > v.seq)
                    rows.set(r);
            if (rows.isEmpty() && !withPalette && headerSeq <= v.seq) {
                v.seq = seq;
                return null;
            }
            payload = CrtFrameCodec.encode(header, full, withPalette ? palette : null, frame, rows);
            encoded.put(key, payload);
        }
        v.epoch = epoch;
        v.seq = seq;
        v.paletteVersion = paletteVersion;
        v.lastTick = now;
        return payload;
    }
}
