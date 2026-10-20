package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.DmaWindow;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The floppy controller and its built-in drive (spec 11.1.5): a simple LBA controller with DMA,
 * like the HDD (3.8), not an emulation of any real floppy chip. 1.44 MB media of 2880 sectors,
 * presented as 80 cylinders, 2 heads and 18 sectors per track.
 *
 * A valid READ, WRITE or SEEK goes BUSY and completes after the spin-up (when the motor is off),
 * the seek and, for transfers, the rotational latency and the sector times; the data moves at
 * completion in one copy. The motor keeps spinning {@link #FDD_MOTOR_HOLD_US} after the last
 * command and then stops by itself, caught up lazily. The host inserts and ejects media between
 * ticks; either sets MEDIA_CHANGED and ends a command in flight with error 9.
 */
public final class FloppyController implements BusDevice {

    /** Register offsets (spec 11.1.5). */
    public static final int COMMAND = 0x00, STATUS = 0x04, LBA = 0x08, COUNT = 0x0C, DMA_ADDR = 0x10,
            CAPACITY = 0x14, IRQ_ENABLE = 0x18, ERROR_CODE = 0x1C, SECTOR_SIZE = 0x20, GEOMETRY = 0x24;
    public static final int SIZE = 0x28;

    /** Commands. */
    public static final int CMD_READ = 1, CMD_WRITE = 2, CMD_SEEK = 3, CMD_MOTOR_OFF = 4;
    /** STATUS bits; DONE, ERROR and MEDIA_CHANGED are write-1-to-clear. */
    public static final int STATUS_BUSY = 1, STATUS_DONE = 2, STATUS_ERROR = 4, STATUS_MEDIA = 8,
            STATUS_WRITE_PROTECT = 16, STATUS_MEDIA_CHANGED = 32, STATUS_MOTOR = 64;
    /** IRQ_ENABLE bits. */
    public static final int IRQ_COMPLETION = 1, IRQ_MEDIA_CHANGE = 2;
    /** ERROR_CODE values: the HDD's 0..7, plus write protected and media changed. */
    public static final int ERR_NONE = 0, ERR_NO_MEDIA = 1, ERR_LBA_RANGE = 2, ERR_DMA_RANGE = 3,
            ERR_BAD_COMMAND = 4, ERR_BUSY = 5, ERR_BAD_COUNT = 6, ERR_MEDIA = 7, ERR_WRITE_PROTECTED = 8,
            ERR_MEDIA_CHANGED = 9;

    /** Sectors on a 1.44 MB disk, and its geometry. */
    public static final int SECTORS = 2880;
    public static final int CYLINDERS = 80, HEADS = 2, SECTORS_PER_TRACK = 18;
    public static final int SECTORS_PER_CYLINDER = HEADS * SECTORS_PER_TRACK;
    public static final int GEOMETRY_VALUE = CYLINDERS << 16 | HEADS << 8 | SECTORS_PER_TRACK;
    public static final int MAX_COUNT = 256;

    /** Timing (spec 11.1.5): spin-up, motor hold, step and settle, half a turn at 300 rpm, one sector. */
    public static final int FDD_SPINUP_US = 300_000;
    public static final int FDD_MOTOR_HOLD_US = 2_000_000;
    public static final int FDD_STEP_US = 3_000;
    public static final int FDD_SETTLE_US = 15_000;
    public static final int FDD_LATENCY_US = 100_000;
    public static final int FDD_SECTOR_US = 11_111;

    private static final int STATE_VERSION = 1;

    private final CycleClock clock;
    private final int clockHz;
    private final SystemBus bus;
    private final IrqSink irq;
    private DiskImage media;
    private boolean writeProtected;

    private int lba;
    private int count;
    private int dmaAddr;
    private int irqEnable;
    private int errorCode;
    private boolean busy;
    private boolean done;
    private boolean error;
    private boolean mediaChanged = true;
    private boolean irqLevel;

    private boolean motorOn;
    /** When the spinning motor stops by itself, unless a command comes first. */
    private long motorStopCycle;
    private int headCylinder;

    // the command in flight, captured when it was accepted
    private int activeCommand;
    private int activeLba;
    private int activeCount;
    private int activeAddr;
    private long completionCycle = Long.MAX_VALUE;
    /** The media was inserted or ejected while the command was in flight. */
    private boolean activeMediaChanged;
    /** A command was accepted since the last {@link #takeActivity}; host-side, not saved. */
    private boolean activity;

    public FloppyController(CycleClock clock, int clockHz, SystemBus bus, IrqSink irq) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
        this.bus = Objects.requireNonNull(bus, "bus");
        this.irq = Objects.requireNonNull(irq, "irq");
    }

    // ---- host side ----

    /**
     * Inserts a disk (replacing any inserted one) with its write-protect tab; IllegalArgumentException
     * unless it has 2880 sectors. Sets MEDIA_CHANGED; a command in flight ends with error 9.
     */
    public void insert(DiskImage media, boolean writeProtected) {
        Objects.requireNonNull(media, "media");
        if (media.sectorCount() != SECTORS)
            throw new IllegalArgumentException("a floppy disk has " + SECTORS + " sectors, not " + media.sectorCount());
        advanceTo(clock.now());
        this.media = media;
        this.writeProtected = writeProtected;
        changed();
    }

    /** Removes the disk, if any. Sets MEDIA_CHANGED; a command in flight ends with error 9. */
    public void eject() {
        advanceTo(clock.now());
        media = null;
        writeProtected = false;
        changed();
    }

    private void changed() {
        mediaChanged = true;
        if (busy)
            activeMediaChanged = true;
        updateIrq();
    }

    public DiskImage media() {
        return media;
    }

    public boolean mediaPresent() {
        return media != null;
    }

    public boolean writeProtected() {
        return writeProtected;
    }

    /** Whether the spindle turns now. */
    public boolean motorOn() {
        return spinning(clock.now());
    }

    /** True if a command ran since the last call; the game uses it for power draw. */
    public boolean takeActivity() {
        boolean was = activity;
        activity = false;
        return was;
    }

    public boolean busy() {
        return busy;
    }

    /** The cylinder the head is over. */
    public int headCylinder() {
        return headCylinder;
    }

    private boolean spinning(long cycle) {
        return motorOn && (busy || cycle < motorStopCycle);
    }

    // ---- registers ----

    private int status() {
        return (busy ? STATUS_BUSY : 0) | (done ? STATUS_DONE : 0) | (error ? STATUS_ERROR : 0)
                | (media != null ? STATUS_MEDIA : 0) | (media != null && writeProtected ? STATUS_WRITE_PROTECT : 0)
                | (mediaChanged ? STATUS_MEDIA_CHANGED : 0) | (motorOn ? STATUS_MOTOR : 0);
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
            case CAPACITY -> media == null ? 0 : SECTORS;
            case IRQ_ENABLE -> irqEnable;
            case ERROR_CODE -> errorCode;
            case SECTOR_SIZE -> DiskImage.SECTOR_SIZE;
            case GEOMETRY -> GEOMETRY_VALUE;
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
                if ((bits & STATUS_MEDIA_CHANGED) != 0)
                    mediaChanged = false;
            }
            case LBA -> lba = Regs.merge(lba, offset, width, value);
            case COUNT -> count = Regs.merge(count, offset, width, value);
            case DMA_ADDR -> dmaAddr = Regs.merge(dmaAddr, offset, width, value);
            case IRQ_ENABLE -> irqEnable = Regs.merge(irqEnable, offset, width, value) & 3;
            default -> { }
        }
        updateIrq();
    }

    private void command(int cmd) {
        if (busy) {
            fail(ERR_BUSY);
            return;
        }
        done = false;
        int err = check(cmd);
        if (err != ERR_NONE) {
            fail(err);
            return;
        }
        error = false;
        errorCode = ERR_NONE;
        activity = true;
        if (cmd == CMD_MOTOR_OFF) {
            motorOn = false;
            done = true;
            return;
        }
        long now = clock.now();
        long micros = motorOn ? 0 : FDD_SPINUP_US;
        int sectors = cmd == CMD_SEEK ? 0 : count;
        int target = lba / SECTORS_PER_CYLINDER;
        int moved = Math.abs(target - headCylinder);
        micros += (long) moved * FDD_STEP_US + (moved > 0 ? FDD_SETTLE_US : 0);
        if (cmd != CMD_SEEK)
            micros += FDD_LATENCY_US + (long) sectors * FDD_SECTOR_US;
        // a transfer leaves the head over the cylinder of its last sector
        headCylinder = cmd == CMD_SEEK ? target : (lba + sectors - 1) / SECTORS_PER_CYLINDER;
        motorOn = true;
        activeCommand = cmd;
        activeLba = lba;
        activeCount = sectors;
        activeAddr = dmaAddr;
        activeMediaChanged = false;
        busy = true;
        completionCycle = now + CycleClock.microsToCycles(micros, clockHz);
        clock.requestStopAt(completionCycle);
    }

    private int check(int cmd) {
        if (cmd < CMD_READ || cmd > CMD_MOTOR_OFF)
            return ERR_BAD_COMMAND;
        if (cmd == CMD_MOTOR_OFF)
            return ERR_NONE;
        if (media == null)
            return ERR_NO_MEDIA;
        if (cmd == CMD_SEEK)
            return Integer.toUnsignedLong(lba) >= SECTORS ? ERR_LBA_RANGE : ERR_NONE;
        if (count < 1 || count > MAX_COUNT)
            return ERR_BAD_COUNT;
        if (Integer.toUnsignedLong(lba) + count > SECTORS)
            return ERR_LBA_RANGE;
        if (bus.dma(dmaAddr, count * DiskImage.SECTOR_SIZE) == null)
            return ERR_DMA_RANGE;
        if (cmd == CMD_WRITE && writeProtected)
            return ERR_WRITE_PROTECTED;
        return ERR_NONE;
    }

    private void fail(int code) {
        error = true;
        errorCode = code;
    }

    private void complete() {
        busy = false;
        motorStopCycle = completionCycle + CycleClock.microsToCycles(FDD_MOTOR_HOLD_US, clockHz);
        completionCycle = Long.MAX_VALUE;
        if (activeMediaChanged) {
            activeMediaChanged = false;
            fail(ERR_MEDIA_CHANGED);
            return;
        }
        if (activeCommand == CMD_READ || activeCommand == CMD_WRITE) {
            DmaWindow w = bus.dma(activeAddr, activeCount * DiskImage.SECTOR_SIZE);
            if (media == null) {
                fail(ERR_NO_MEDIA);
                return;
            }
            if (w == null) {
                fail(ERR_DMA_RANGE);
                return;
            }
            try {
                if (activeCommand == CMD_READ) {
                    media.read(activeLba, activeCount, w.array(), w.offset());
                    w.onWrite().run();
                } else {
                    media.write(activeLba, activeCount, w.array(), w.offset());
                }
            } catch (UncheckedIOException e) {
                // the image could not be read from storage (spec 11.1.6)
                fail(ERR_MEDIA);
                return;
            }
        }
        done = true;
    }

    /**
     * Registers cleared, any command in flight dropped, motor off, head at cylinder 0 and
     * MEDIA_CHANGED set (as at power-on); the disk stays inserted.
     */
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
        mediaChanged = true;
        motorOn = false;
        motorStopCycle = 0;
        headCylinder = 0;
        activeCommand = 0;
        activeLba = 0;
        activeCount = 0;
        activeAddr = 0;
        activeMediaChanged = false;
        completionCycle = Long.MAX_VALUE;
        updateIrq();
    }

    @Override
    public void advanceTo(long cycle) {
        if (busy && cycle >= completionCycle)
            complete();
        if (motorOn && !busy && cycle >= motorStopCycle)
            motorOn = false;
        updateIrq();
    }

    /** The completion whether or not IRQ_ENABLE is set: the transfer changes memory the CPU reads. */
    @Override
    public long nextEventCycle() {
        return busy ? completionCycle : Long.MAX_VALUE;
    }

    private void updateIrq() {
        boolean level = (irqEnable & IRQ_COMPLETION) != 0 && (done || error)
                || (irqEnable & IRQ_MEDIA_CHANGE) != 0 && mediaChanged;
        if (level != irqLevel) {
            irqLevel = level;
            irq.set(level);
        }
    }

    // ---- saved state (spec 1.5, 11.1.5) ----

    /**
     * Every register and flag, the motor and its stop cycle, the head cylinder, the command in
     * flight with its absolute completion cycle, and the media's presence, write protect and id.
     * The media's contents are persisted on their own.
     */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeBoolean(media != null);
        out.writeBoolean(writeProtected);
        HddController.writeId(out, media == null ? null : media.id());
        out.writeInt(lba);
        out.writeInt(count);
        out.writeInt(dmaAddr);
        out.writeInt(irqEnable);
        out.writeInt(errorCode);
        out.writeBoolean(busy);
        out.writeBoolean(done);
        out.writeBoolean(error);
        out.writeBoolean(mediaChanged);
        out.writeBoolean(irqLevel);
        out.writeBoolean(motorOn);
        out.writeLong(motorStopCycle);
        out.writeInt(headCylinder);
        out.writeInt(activeCommand);
        out.writeInt(activeLba);
        out.writeInt(activeCount);
        out.writeInt(activeAddr);
        out.writeLong(completionCycle);
        out.writeBoolean(activeMediaChanged);
    }

    /**
     * Never fails over the media: removable disks may change while a PC is unloaded. When the
     * attached media differs from the saved one (presence, id or write protect), the controller
     * behaves as if the host had swapped it at this moment: MEDIA_CHANGED set and a command in
     * flight ends with error 9. The attached media and its tab stay as the host set them.
     */
    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "floppy controller", STATE_VERSION);
        boolean savedPresent = in.readBoolean();
        boolean savedProtected = in.readBoolean();
        UUID savedId = HddController.readId(in);
        int l = in.readInt();
        int c = in.readInt();
        int addr = in.readInt();
        int en = in.readInt() & 3;
        int code = in.readInt();
        StateIo.check(code >= 0 && code <= ERR_MEDIA_CHANGED, "floppy error code " + code + " does not exist");
        boolean b = in.readBoolean(), d = in.readBoolean(), e = in.readBoolean(), mc = in.readBoolean(),
                level = in.readBoolean(), motor = in.readBoolean();
        long stop = in.readLong();
        int head = in.readInt();
        StateIo.check(head >= 0 && head < CYLINDERS, "floppy head cylinder " + head + " is out of range");
        int cmd = in.readInt();
        StateIo.check(cmd >= 0 && cmd <= CMD_MOTOR_OFF, "floppy command " + cmd + " does not exist");
        int aLba = in.readInt();
        int aCount = StateIo.readCount(in, MAX_COUNT, "floppy sector count in flight");
        StateIo.check(aLba >= 0 && aLba + aCount <= SECTORS, "floppy transfer in flight is outside the disk");
        int aAddr = in.readInt();
        long completion = in.readLong();
        boolean aChanged = in.readBoolean();

        lba = l;
        count = c;
        dmaAddr = addr;
        irqEnable = en;
        errorCode = code;
        busy = b;
        done = d;
        error = e;
        mediaChanged = mc;
        irqLevel = level;
        motorOn = motor;
        motorStopCycle = stop;
        headCylinder = head;
        activeCommand = cmd;
        activeLba = aLba;
        activeCount = aCount;
        activeAddr = aAddr;
        completionCycle = completion;
        activeMediaChanged = aChanged;

        boolean present = media != null;
        boolean same = savedPresent == present
                && (!present || savedProtected == writeProtected && Objects.equals(savedId, media.id()));
        if (!same) {
            mediaChanged = true;
            if (busy)
                activeMediaChanged = true;
        }
    }
}
