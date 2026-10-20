package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * The display output (spec 3.7). Not a VGA chip: no CRTC, sequencer, planes or timing. The guest
 * draws into VRAM, a plain byte array on the CPU's fast path, and the host copies the visible
 * image across once per game tick. This device owns VRAM and its dirty page bitmap, holds the
 * mode, palette, cursor and display start, and turns dirty pages into per-row generations at
 * each frame end so the game can send only what changed.
 *
 * A machine has three displays (spec 11.1.3) sharing one VRAM array, each owning a 512 KiB slice
 * of it and the 128 dirty page bits of that slice. START, the wrap rule and the copies are
 * relative to the display's own slice.
 *
 * Modes: 0 text 80x25 (character and attribute bytes, pitch 160), 1 320x200 at 8 bpp, 2 640x480
 * at 4 bpp (high nibble is the left pixel), 3 640x480 at 8 bpp.
 */
public final class VgaDisplay implements BusDevice {

    /** Register offsets (spec 3.7). */
    public static final int MODE = 0x00, CTRL = 0x04, STATUS = 0x08, CURSOR = 0x0C, FRAME = 0x10,
            WIDTH = 0x14, HEIGHT = 0x18, BPP = 0x1C, PITCH = 0x20, START = 0x24, BANK = 0x28,
            PAL_INDEX = 0x2C, PAL_DATA = 0x30, PALETTE = 0x400;
    public static final int SIZE = 0x800;

    /** VRAM bytes of one display (spec 3.7) and its 4 KiB pages. */
    public static final int VRAM_SIZE = 512 * 1024;
    public static final int PAGES = VRAM_SIZE / 4096;

    public static final int MODE_TEXT = 0, MODE_320X200X8 = 1, MODE_640X480X4 = 2, MODE_640X480X8 = 3;
    /** CTRL bits; reset value 0xD (display, cursor, blink). */
    public static final int CTRL_DISPLAY = 1, CTRL_VBLANK_IRQ = 2, CTRL_CURSOR = 4, CTRL_BLINK = 8,
            CTRL_RESET = 0xD;
    public static final int STATUS_VBLANK = 1;
    /** Most rows any mode has. */
    public static final int MAX_ROWS = 480;

    // per mode: width, height, bits per pixel, pitch
    private static final int[][] GEOMETRY = {
            { 80, 25, 16, 160 },
            { 320, 200, 8, 320 },
            { 640, 480, 4, 320 },
            { 640, 480, 8, 640 },
    };

    private static final int[] CGA = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };
    private static final int[] CUBE_LEVELS = { 0, 95, 135, 175, 215, 255 };
    private static final int STATE_VERSION = 1;

    private final IrqSink irq;
    private final byte[] vram;
    /** Where this display's slice starts in {@link #vram}. */
    private final int base;
    private final long[] dirtyPages;
    /** The first of this display's two words in {@link #dirtyPages}. */
    private final int dirtyWord;
    private final int[] palette = new int[256];
    private final long[] rowGeneration = new long[MAX_ROWS];

    private int mode;
    private int ctrl = CTRL_RESET;
    private int status;
    private int cursor;
    private int frame;
    private int start;
    private int bank;
    private int palIndex;
    private int palLatch;
    private boolean fullDirty = true;
    private long generation;
    private boolean irqLevel;

    /** A display with its own 512 KiB of VRAM and its own dirty page bits, for tests. */
    public VgaDisplay(IrqSink irq) {
        this(irq, new byte[VRAM_SIZE], 0, new long[PAGES / 64]);
    }

    /**
     * A display whose framebuffer is the 512 KiB slice of {@code vram} at {@code offset} (a
     * multiple of 512 KiB); {@code dirtyPages} are the bus's page bits for the whole array, of
     * which the display reads and clears only its own 128 (spec 11.1.3).
     */
    public VgaDisplay(IrqSink irq, byte[] vram, int offset, long[] dirtyPages) {
        this.irq = Objects.requireNonNull(irq, "irq");
        this.vram = Objects.requireNonNull(vram, "vram");
        this.dirtyPages = Objects.requireNonNull(dirtyPages, "dirtyPages");
        if (offset < 0 || offset % VRAM_SIZE != 0 || (long) offset + VRAM_SIZE > vram.length)
            throw new IllegalArgumentException("VGA slice at " + offset + " does not fit " + vram.length
                    + " bytes of VRAM in 512 KiB steps");
        this.base = offset;
        this.dirtyWord = offset >>> 18;
        if (dirtyWord + 2 > dirtyPages.length)
            throw new IllegalArgumentException("dirty page bitmap of " + dirtyPages.length
                    + " words does not cover the slice at " + offset);
        resetPalette();
    }

    /** The VRAM array the display's slice lives in, mapped by the platform on the CPU's fast path. */
    public byte[] vram() {
        return vram;
    }

    /** Where the display's 512 KiB slice starts in {@link #vram()}. */
    public int vramOffset() {
        return base;
    }

    /** One bit per 4 KiB page of the whole VRAM array, set by every VRAM write; passed to {@code SystemBus.setVram}. */
    public long[] dirtyPages() {
        return dirtyPages;
    }

    /** The reset palette: the 16 CGA colours, a 6x6x6 cube, a 24-step grey ramp. */
    public static int defaultPaletteEntry(int i) {
        if (i < 16)
            return CGA[i];
        if (i < 232) {
            int c = i - 16;
            return CUBE_LEVELS[c / 36] << 16 | CUBE_LEVELS[c / 6 % 6] << 8 | CUBE_LEVELS[c % 6];
        }
        int g = 8 + 10 * (i - 232);
        return g << 16 | g << 8 | g;
    }

    private void resetPalette() {
        for (int i = 0; i < 256; i++)
            palette[i] = defaultPaletteEntry(i);
    }

    // ---- geometry ----

    public int mode() {
        return mode;
    }

    public int width() {
        return GEOMETRY[mode][0];
    }

    public int height() {
        return GEOMETRY[mode][1];
    }

    public int bitsPerPixel() {
        return GEOMETRY[mode][2];
    }

    public int pitch() {
        return GEOMETRY[mode][3];
    }

    /** Bytes of the visible image: PITCH * HEIGHT. */
    public int visibleBytes() {
        return pitch() * height();
    }

    public int start() {
        return start;
    }

    /** The 64 KiB bank for CPUs with a 64 KiB VRAM window; unused on RV32I. */
    public int bank() {
        return bank;
    }

    public int ctrl() {
        return ctrl;
    }

    public int frame() {
        return frame;
    }

    public int palette(int index) {
        return palette[index];
    }

    // ---- registers ----

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        int reg = offset & ~3;
        int v;
        if (reg >= PALETTE && reg < PALETTE + 1024) {
            v = palette[(reg - PALETTE) >>> 2];
        } else {
            switch (reg) {
                case MODE -> v = mode;
                case CTRL -> v = ctrl;
                case STATUS -> v = status;
                case CURSOR -> v = cursor;
                case FRAME -> v = frame;
                case WIDTH -> v = width();
                case HEIGHT -> v = height();
                case BPP -> v = bitsPerPixel();
                case PITCH -> v = pitch();
                case START -> v = start;
                case BANK -> v = bank;
                case PAL_INDEX -> v = palIndex;
                case PAL_DATA -> {
                    v = palette[palIndex];
                    if (Regs.touchesByte3(offset, width))
                        palIndex = (palIndex + 1) & 0xFF;
                }
                default -> v = 0;
            }
        }
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        int reg = offset & ~3;
        if (reg >= PALETTE && reg < PALETTE + 1024) {
            int i = (reg - PALETTE) >>> 2;
            palette[i] = Regs.merge(palette[i], offset, width, value) & 0xFF_FFFF;
            fullDirty = true;
            return;
        }
        switch (reg) {
            case MODE -> {
                int m = Regs.merge(mode, offset, width, value);
                if (m >= 0 && m < GEOMETRY.length) {
                    mode = m;
                    fullDirty = true;
                }
            }
            case CTRL -> {
                ctrl = Regs.merge(ctrl, offset, width, value) & 0xF;
                fullDirty = true;
                updateIrq();
            }
            case STATUS -> {
                if ((Regs.written(offset, width, value) & STATUS_VBLANK) != 0) {
                    status &= ~STATUS_VBLANK;
                    updateIrq();
                }
            }
            case CURSOR -> {
                cursor = Regs.merge(cursor, offset, width, value) & 0xFFFF;
                fullDirty = true;
            }
            case START -> {
                start = Regs.merge(start, offset, width, value) & (VRAM_SIZE - 1);
                fullDirty = true;
            }
            case BANK -> bank = Regs.merge(bank, offset, width, value) & 7;
            case PAL_INDEX -> palIndex = Regs.merge(palIndex, offset, width, value) & 0xFF;
            case PAL_DATA -> {
                palLatch = Regs.merge(palLatch, offset, width, value);
                if (Regs.touchesByte3(offset, width)) {
                    palette[palIndex] = palLatch & 0xFF_FFFF;
                    palIndex = (palIndex + 1) & 0xFF;
                    fullDirty = true;
                }
            }
            default -> { }
        }
    }

    /** Registers and palette to reset values; VRAM keeps its contents. */
    @Override
    public void reset() {
        mode = MODE_TEXT;
        ctrl = CTRL_RESET;
        status = 0;
        cursor = 0;
        frame = 0;
        start = 0;
        bank = 0;
        palIndex = 0;
        palLatch = 0;
        resetPalette();
        fullDirty = true;
        updateIrq();
    }

    private void updateIrq() {
        boolean level = (status & STATUS_VBLANK) != 0 && (ctrl & CTRL_VBLANK_IRQ) != 0;
        if (level != irqLevel) {
            irqLevel = level;
            irq.set(level);
        }
    }

    // ---- saved state (spec 1.5) ----

    /**
     * The display's VRAM slice (non-zero pages, deflated), its dirty page bits, palette, per-row
     * generations, every register with the PAL_DATA latch, the frame and generation counters and
     * the line level. VRAM and the bitmap are filled in place: the bus holds the same arrays.
     */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        StateIo.writePages(out, Arrays.copyOfRange(vram, base, base + VRAM_SIZE));
        out.writeLong(dirtyPages[dirtyWord]);
        out.writeLong(dirtyPages[dirtyWord + 1]);
        for (int c : palette)
            out.writeInt(c);
        for (long g : rowGeneration)
            out.writeLong(g);
        out.writeInt(mode);
        out.writeInt(ctrl);
        out.writeInt(status);
        out.writeInt(cursor);
        out.writeInt(frame);
        out.writeInt(start);
        out.writeInt(bank);
        out.writeInt(palIndex);
        out.writeInt(palLatch);
        out.writeBoolean(fullDirty);
        out.writeLong(generation);
        out.writeBoolean(irqLevel);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "VGA", STATE_VERSION);
        byte[] slice = new byte[VRAM_SIZE];
        StateIo.readPages(in, slice, "VRAM");
        System.arraycopy(slice, 0, vram, base, VRAM_SIZE);
        dirtyPages[dirtyWord] = in.readLong();
        dirtyPages[dirtyWord + 1] = in.readLong();
        for (int i = 0; i < palette.length; i++)
            palette[i] = in.readInt() & 0xFF_FFFF;
        for (int i = 0; i < rowGeneration.length; i++)
            rowGeneration[i] = in.readLong();
        int m = in.readInt();
        StateIo.check(m >= 0 && m < GEOMETRY.length, "VGA mode " + m + " does not exist");
        mode = m;
        ctrl = in.readInt() & 0xF;
        status = in.readInt() & STATUS_VBLANK;
        cursor = in.readInt() & 0xFFFF;
        frame = in.readInt();
        start = in.readInt() & (VRAM_SIZE - 1);
        bank = in.readInt() & 7;
        palIndex = in.readInt() & 0xFF;
        palLatch = in.readInt();
        fullDirty = in.readBoolean();
        generation = in.readLong();
        irqLevel = in.readBoolean();
    }

    // ---- frames ----

    /**
     * The end of a frame, called by the machine at the end of every tick: FRAME + 1, VBLANK set,
     * and if anything changed a new generation stamped on every changed visible row.
     */
    public void endFrame() {
        frame++;
        status |= STATUS_VBLANK;
        updateIrq();
        boolean anyPage = (dirtyPages[dirtyWord] | dirtyPages[dirtyWord + 1]) != 0;
        if (!anyPage && !fullDirty)
            return;
        generation++;
        int rows = height();
        int pitch = pitch();
        for (int r = 0; r < rows; r++)
            if (fullDirty || rowTouchesDirtyPage(r, pitch))
                rowGeneration[r] = generation;
        dirtyPages[dirtyWord] = 0;
        dirtyPages[dirtyWord + 1] = 0;
        fullDirty = false;
    }

    private boolean rowTouchesDirtyPage(int row, int pitch) {
        int first = (start + row * pitch) & (VRAM_SIZE - 1);
        int last = (first + pitch - 1) & (VRAM_SIZE - 1);
        int p0 = first >>> 12, p1 = last >>> 12;
        if (p1 < p0)
            return pageRangeDirty(p0, PAGES - 1) || pageRangeDirty(0, p1);
        return pageRangeDirty(p0, p1);
    }

    private boolean pageRangeDirty(int from, int to) {
        for (int p = from; p <= to; p++)
            if ((dirtyPages[dirtyWord + (p >>> 6)] & (1L << (p & 63))) != 0)
                return true;
        return false;
    }

    /** Bumped at every frame end that saw a change; the game sends a frame only when it moved. */
    public long generation() {
        return generation;
    }

    /** Visible rows (text rows in mode 0) changed after {@code since}; every row after a mode, START, palette, CTRL or CURSOR change. */
    public BitSet dirtyRowsSince(long since) {
        int rows = height();
        BitSet set = new BitSet(rows);
        for (int r = 0; r < rows; r++)
            if (rowGeneration[r] > since)
                set.set(r);
        return set;
    }

    /** Copies the visible bytes (PITCH * HEIGHT from START, wrapping at the end of the display's VRAM) into {@code dst}; returns the count. */
    public int copyVisibleInto(byte[] dst) {
        int n = visibleBytes();
        if (dst.length < n)
            throw new IllegalArgumentException("buffer of " + dst.length + " bytes is smaller than the visible " + n);
        int first = Math.min(n, VRAM_SIZE - start);
        System.arraycopy(vram, base + start, dst, 0, first);
        if (first < n)
            System.arraycopy(vram, base, dst, first, n - first);
        return n;
    }

    public VgaFrame snapshot() {
        byte[] pixels = new byte[visibleBytes()];
        copyVisibleInto(pixels);
        return new VgaFrame(mode, width(), height(), pitch(), bitsPerPixel(), cursor & 0xFF,
                (cursor >>> 8) & 0xFF, (ctrl & CTRL_CURSOR) != 0, (ctrl & CTRL_BLINK) != 0,
                (ctrl & CTRL_DISPLAY) != 0, pixels, palette, generation);
    }
}
