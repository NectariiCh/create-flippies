package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.DmaWindow;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The HDD controller (spec 3.8): a 512-byte-sector block device that moves data by DMA into RAM
 * or VRAM. A command is checked when written; a valid one goes BUSY and completes after a fixed
 * latency plus a per-sector time, at which point the data moves in one array copy and DONE is set.
 * The completion cycle is reported by {@link #nextEventCycle()} whatever the IRQ enable, because
 * the transfer changes memory the CPU reads directly.
 */
public final class HddController implements BusDevice {

    /** Register offsets (spec 3.8). */
    public static final int COMMAND = 0x00, STATUS = 0x04, LBA = 0x08, COUNT = 0x0C, DMA_ADDR = 0x10,
            CAPACITY = 0x14, IRQ_ENABLE = 0x18, ERROR_CODE = 0x1C, SECTOR_SIZE = 0x20;
    public static final int SIZE = 0x24;

    /** Commands. */
    public static final int CMD_READ = 1, CMD_WRITE = 2, CMD_FLUSH = 3, CMD_IDENTIFY = 4;
    /** STATUS bits; DONE and ERROR are write-1-to-clear. */
    public static final int STATUS_BUSY = 1, STATUS_DONE = 2, STATUS_ERROR = 4, STATUS_MEDIA = 8;
    /** ERROR_CODE values. */
    public static final int ERR_NONE = 0, ERR_NO_MEDIA = 1, ERR_LBA_RANGE = 2, ERR_DMA_RANGE = 3,
            ERR_BAD_COMMAND = 4, ERR_BUSY = 5, ERR_BAD_COUNT = 6, ERR_MEDIA = 7;

    /** Command latency and per-sector time, about 5 MB/s (spec 3.8). */
    public static final int HDD_COMMAND_US = 500;
    public static final int HDD_SECTOR_US = 100;
    public static final int MAX_COUNT = 256;

    /** 2 added the disk id (spec 11.1.6). */
    private static final int STATE_VERSION = 2;

    private final CycleClock clock;
    private final int clockHz;
    private final SystemBus bus;
    private final IrqSink irq;
    private DiskImage disk;

    private int lba;
    private int count;
    private int dmaAddr;
    private int irqEnable;
    private int errorCode;
    private boolean busy;
    private boolean done;
    private boolean error;
    private boolean irqLevel;

    // the command in flight, captured when it was accepted
    private int activeCommand;
    private long activeLba;
    private int activeCount;
    private int activeAddr;
    private long completionCycle = Long.MAX_VALUE;
    /** A command was accepted since the last {@link #takeActivity}; host-side, not saved. */
    private boolean activity;

    public HddController(CycleClock clock, int clockHz, SystemBus bus, IrqSink irq) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
        this.bus = Objects.requireNonNull(bus, "bus");
        this.irq = Objects.requireNonNull(irq, "irq");
    }

    /** Inserts a disk, or removes it with null. */
    public void setDisk(DiskImage disk) {
        this.disk = disk;
    }

    public DiskImage disk() {
        return disk;
    }

    public boolean mediaPresent() {
        return disk != null;
    }

    public boolean busy() {
        return busy;
    }

    /** True if a command ran since the last call (spec 11.1.8); the game uses it for power draw. */
    public boolean takeActivity() {
        boolean was = activity;
        activity = false;
        return was;
    }

    private int capacity() {
        return disk == null ? 0 : (int) Math.min(disk.sectorCount(), 0xFFFF_FFFFL);
    }

    private int status() {
        return (busy ? STATUS_BUSY : 0) | (done ? STATUS_DONE : 0) | (error ? STATUS_ERROR : 0)
                | (disk != null ? STATUS_MEDIA : 0);
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        advanceTo(clock.now());
        int v = switch (offset & ~3) {
            case STATUS -> status();
            case LBA -> lba;
            case COUNT -> count;
            case DMA_ADDR -> dmaAddr;
            case CAPACITY -> capacity();
            case IRQ_ENABLE -> irqEnable;
            case ERROR_CODE -> errorCode;
            case SECTOR_SIZE -> DiskImage.SECTOR_SIZE;
            default -> 0;
        };
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        advanceTo(clock.now());
        switch (offset & ~3) {
            case COMMAND -> {
                if (Regs.touchesByte0(offset))
                    command(value & 0xFF);
            }
            case STATUS -> {
                int bits = Regs.written(offset, width, value);
                if ((bits & STATUS_DONE) != 0)
                    done = false;
                if ((bits & STATUS_ERROR) != 0)
                    error = false;
            }
            case LBA -> lba = Regs.merge(lba, offset, width, value);
            case COUNT -> count = Regs.merge(count, offset, width, value);
            case DMA_ADDR -> dmaAddr = Regs.merge(dmaAddr, offset, width, value);
            case IRQ_ENABLE -> irqEnable = Regs.merge(irqEnable, offset, width, value) & 1;
            default -> { }
        }
        updateIrq();
    }

    private void command(int cmd) {
        if (busy) {
            fail(ERR_BUSY);
            return;
        }
        int err = check(cmd);
        if (err != ERR_NONE) {
            done = false;
            fail(err);
            return;
        }
        int sectors = cmd == CMD_READ || cmd == CMD_WRITE ? count : cmd == CMD_IDENTIFY ? 1 : 0;
        activeCommand = cmd;
        activeLba = Integer.toUnsignedLong(lba);
        activeCount = sectors;
        activeAddr = dmaAddr;
        busy = true;
        done = false;
        error = false;
        errorCode = ERR_NONE;
        activity = true;
        long micros = HDD_COMMAND_US + (long) sectors * HDD_SECTOR_US;
        completionCycle = clock.now() + CycleClock.microsToCycles(micros, clockHz);
        clock.requestStopAt(completionCycle);
    }

    private int check(int cmd) {
        if (cmd < CMD_READ || cmd > CMD_IDENTIFY)
            return ERR_BAD_COMMAND;
        if (disk == null)
            return ERR_NO_MEDIA;
        if (cmd == CMD_FLUSH)
            return ERR_NONE;
        if (cmd == CMD_IDENTIFY)
            return bus.dma(dmaAddr, DiskImage.SECTOR_SIZE) == null ? ERR_DMA_RANGE : ERR_NONE;
        if (count < 1 || count > MAX_COUNT)
            return ERR_BAD_COUNT;
        if (Integer.toUnsignedLong(lba) + count > disk.sectorCount())
            return ERR_LBA_RANGE;
        if (bus.dma(dmaAddr, count * DiskImage.SECTOR_SIZE) == null)
            return ERR_DMA_RANGE;
        return ERR_NONE;
    }

    private void fail(int code) {
        error = true;
        errorCode = code;
    }

    private void complete() {
        busy = false;
        completionCycle = Long.MAX_VALUE;
        int bytes = activeCount * DiskImage.SECTOR_SIZE;
        if (activeCommand != CMD_FLUSH) {
            DmaWindow w = bus.dma(activeAddr, bytes);
            if (w == null || disk == null) {
                fail(disk == null ? ERR_NO_MEDIA : ERR_DMA_RANGE);
                return;
            }
            // the host may have swapped in a smaller disk while the command was in flight
            if (activeCommand != CMD_IDENTIFY && activeLba + activeCount > disk.sectorCount()) {
                fail(ERR_LBA_RANGE);
                return;
            }
            switch (activeCommand) {
                case CMD_READ -> {
                    try {
                        disk.read(activeLba, activeCount, w.array(), w.offset());
                    } catch (UncheckedIOException e) {
                        // the image could not be read from storage (spec 11.1.6)
                        fail(ERR_MEDIA);
                        return;
                    }
                    w.onWrite().run();
                }
                case CMD_WRITE -> {
                    try {
                        disk.write(activeLba, activeCount, w.array(), w.offset());
                    } catch (UncheckedIOException e) {
                        fail(ERR_MEDIA);
                        return;
                    }
                }
                case CMD_IDENTIFY -> {
                    byte[] block = identifyBlock();
                    System.arraycopy(block, 0, w.array(), w.offset(), block.length);
                    w.onWrite().run();
                }
                default -> { }
            }
        }
        done = true;
    }

    /** "FLHD", capacity in sectors, sector size (both u32 little-endian), a 32-byte model string at 16. */
    private byte[] identifyBlock() {
        byte[] b = new byte[DiskImage.SECTOR_SIZE];
        b[0] = 'F';
        b[1] = 'L';
        b[2] = 'H';
        b[3] = 'D';
        putLe(b, 4, capacity());
        putLe(b, 8, DiskImage.SECTOR_SIZE);
        Arrays.fill(b, 16, 48, (byte) ' ');
        byte[] model = modelName().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(model, 0, b, 16, Math.min(32, model.length));
        return b;
    }

    /** For example "FLIPPIES HDD 16M". */
    public String modelName() {
        long bytes = disk == null ? 0 : disk.sectorCount() * DiskImage.SECTOR_SIZE;
        return bytes >= 1024 * 1024
                ? "FLIPPIES HDD " + bytes / (1024 * 1024) + "M"
                : "FLIPPIES HDD " + bytes / 1024 + "K";
    }

    private static void putLe(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
        b[at + 3] = (byte) (v >>> 24);
    }

    /** Registers cleared and any command in flight dropped; the disk stays inserted. */
    @Override
    public void reset() {
        lba = 0;
        count = 0;
        dmaAddr = 0;
        irqEnable = 0;
        errorCode = ERR_NONE;
        busy = false;
        done = false;
        error = false;
        activeCommand = 0;
        completionCycle = Long.MAX_VALUE;
        updateIrq();
    }

    @Override
    public void advanceTo(long cycle) {
        if (busy && cycle >= completionCycle)
            complete();
        updateIrq();
    }

    @Override
    public long nextEventCycle() {
        return busy ? completionCycle : Long.MAX_VALUE;
    }

    // ---- saved state (spec 1.5) ----

    /**
     * The attached disk's capacity (-1 for none) and id, every register and flag, and the command
     * in flight with its absolute completion cycle. The disk's contents are persisted on their
     * own; the capacity and id are stored only to check that the same disk is attached on load.
     */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeLong(disk == null ? -1 : disk.sectorCount());
        writeId(out, disk == null ? null : disk.id());
        out.writeInt(lba);
        out.writeInt(count);
        out.writeInt(dmaAddr);
        out.writeInt(irqEnable);
        out.writeInt(errorCode);
        out.writeBoolean(busy);
        out.writeBoolean(done);
        out.writeBoolean(error);
        out.writeBoolean(irqLevel);
        out.writeInt(activeCommand);
        out.writeLong(activeLba);
        out.writeInt(activeCount);
        out.writeInt(activeAddr);
        out.writeLong(completionCycle);
    }

    /**
     * StateFormatException if the attached disk is not the one saved (present or not, capacity,
     * id). A version 1 payload has no id and is checked by capacity only.
     */
    @Override
    public void loadState(DataInput in) throws IOException {
        int version = StateIo.readVersion(in, "HDD controller", STATE_VERSION);
        long saved = in.readLong();
        long here = disk == null ? -1 : disk.sectorCount();
        if (saved != here)
            throw new StateFormatException("the saved PC had " + describe(saved) + " but " + describe(here) + " is attached");
        if (version >= 2) {
            UUID savedId = readId(in);
            UUID hereId = disk == null ? null : disk.id();
            if (!Objects.equals(savedId, hereId))
                throw new StateFormatException("the saved PC had disk " + savedId + " but disk " + hereId + " is attached");
        }
        lba = in.readInt();
        count = in.readInt();
        dmaAddr = in.readInt();
        irqEnable = in.readInt() & 1;
        errorCode = in.readInt();
        busy = in.readBoolean();
        done = in.readBoolean();
        error = in.readBoolean();
        irqLevel = in.readBoolean();
        activeCommand = in.readInt();
        StateIo.check(activeCommand >= 0 && activeCommand <= CMD_IDENTIFY, "HDD command " + activeCommand + " does not exist");
        activeLba = in.readLong();
        activeCount = StateIo.readCount(in, MAX_COUNT, "HDD sector count in flight");
        activeAddr = in.readInt();
        completionCycle = in.readLong();
    }

    /** A nullable disk id: present flag, then the two halves. */
    static void writeId(DataOutput out, UUID id) throws IOException {
        out.writeBoolean(id != null);
        if (id != null) {
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
        }
    }

    static UUID readId(DataInput in) throws IOException {
        return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
    }

    private static String describe(long sectors) {
        return sectors < 0 ? "no disk" : "a disk of " + sectors + " sectors";
    }

    private void updateIrq() {
        boolean level = irqEnable != 0 && (done || error);
        if (level != irqLevel) {
            irqLevel = level;
            irq.set(level);
        }
    }
}
