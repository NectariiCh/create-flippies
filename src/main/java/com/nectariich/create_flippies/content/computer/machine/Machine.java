package com.nectariich.create_flippies.content.computer.machine;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.StateFormatException;
import com.nectariich.create_flippies.content.computer.bus.StateIo;
import com.nectariich.create_flippies.content.computer.bus.SystemBus;
import com.nectariich.create_flippies.content.computer.cpu.Cpu;
import com.nectariich.create_flippies.content.computer.cpu.CpuFactory;
import com.nectariich.create_flippies.content.computer.cpu.CpuHost;
import com.nectariich.create_flippies.content.computer.device.FloppyController;
import com.nectariich.create_flippies.content.computer.device.HddController;
import com.nectariich.create_flippies.content.computer.device.Nic;
import com.nectariich.create_flippies.content.computer.device.Ps2Controller;
import com.nectariich.create_flippies.content.computer.device.Ps2Keyboard;
import com.nectariich.create_flippies.content.computer.device.Ps2Mouse;
import com.nectariich.create_flippies.content.computer.device.SysCon;
import com.nectariich.create_flippies.content.computer.device.Uart16550;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * One PC: a platform's board, a CPU and the clock that drives both (spec 1.4, 7.5).
 *
 * The game calls {@link #runTick} once per server tick. A tick is worth {@code clockHz / 20}
 * cycles (the remainder carries, so long runs are exact), run in segments: each one ends at the
 * tick's end, at the earliest device event, or earlier where a device asks the CPU to stop. After
 * each segment the machine performs the guest's recorded requests (power off, reset, WAIT) and
 * catches every device up. A CPU idle in its wait instruction costs no host time: the clock jumps
 * to the segment end. When the host budget runs out the rest of the tick is dropped, so emulated
 * time dilates rather than stalling the server; {@link #speed()} reports it.
 *
 * A new machine is {@link MachineState#OFF}; {@link #powerOn()} cold-boots it. Single-threaded:
 * every method, host input included, is called from the thread that runs ticks, between ticks.
 *
 * {@link #saveState()} and {@link #loadState} freeze and resume a machine exactly (spec 1.5): the
 * clock is saved with everything stamped against it, so a loaded machine carries on as if no time
 * had passed for it.
 */
public final class Machine {

    public static final long DEFAULT_HOST_BUDGET_NANOS = 2_000_000L;
    public static final long TICK_NANOS = 50_000_000L;

    /** Ticks {@link #speed()} averages over. */
    private static final int SPEED_TICKS = 20;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** First word of a saved state, ASCII "FLPS". */
    private static final int STATE_MAGIC = 0x464C5053;
    /**
     * Format version of the machine envelope (spec 1.5, 11.1.8); components carry their own. 2
     * added the strict FPU flag, 3 the pending device reset of the 68k RESET instruction, 4 the
     * in-game board (the MAC, COM3, the floppy controller, the network card, displays 1 and 2).
     * Versions 1 to 3 are rejected: the memory map changed under them.
     */
    public static final int STATE_VERSION = 4;

    /** COM ports, VGA displays and PS/2 ports on every board (spec 11.1). */
    public static final int COM_PORTS = 3, DISPLAYS = 3, PS2_PORTS = 2;

    private final MachineConfig config;
    private final Platform platform;
    private final CycleClock clock = new CycleClock();
    private final Board board;
    private final Cpu cpu;
    private final BusDevice[] devices;
    /** Every device by its tag in saved states, in saving order. */
    private final Map<String, BusDevice> stateDevices;

    private MachineState state = MachineState.OFF;
    private int exitCode;
    /** SysCon WAIT: the CPU is not run until one of its pins is asserted. */
    private boolean waitingForPins;
    /** The CPU's RESET instruction asked for every device to be reset; done between runs (spec 10.8.3). */
    private boolean deviceResetPending;
    private long cycleRemainder;
    /** Where the last tick's window ended; the next window starts there unless time was dropped. */
    private long windowEnd;
    private final long[] ranCycles = new long[SPEED_TICKS];
    private final long[] wantedCycles = new long[SPEED_TICKS];
    private int speedSlot;
    /** Over the last tick: cycles the CPU ran, and cycles the tick advanced; host-side, not saved. */
    private long lastBusyCycles;
    private long lastTickCycles;

    /** Picks the platform, validates the config, builds the board, creates the CPU and wires its pins. */
    public Machine(MachineConfig config, CpuFactory cpus) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(cpus, "cpus");
        this.platform = Platform.forModel(config.cpu());
        platform.validate(config);
        this.board = platform.build(config, clock);
        this.devices = board.devices().toArray(new BusDevice[0]);
        int resetPc = config.resetPc() != null ? config.resetPc() : platform.defaultResetPc();
        this.cpu = Objects.requireNonNull(cpus.create(new CpuHost(config.cpu(), config.clockHz(),
                board.memory(), board.io(), clock, null, this::requestDeviceReset, resetPc, config.strictFpu())),
                "CPU factory returned null");
        platform.wirePins(board, cpu);
        this.stateDevices = stateDevices(board);
    }

    private static Map<String, BusDevice> stateDevices(Board board) {
        Map<String, BusDevice> m = new LinkedHashMap<>();
        m.put("syscon", board.sysCon());
        m.put("timer", board.timer());
        m.put("rtc", board.rtc());
        m.put("com1", board.com(0));
        m.put("com2", board.com(1));
        m.put("com3", board.com(2));
        m.put("ps2", board.ps2());
        m.put("vga", board.vga(0));
        m.put("vga.1", board.vga(1));
        m.put("vga.2", board.vga(2));
        m.put("hdd", board.hdd());
        m.put("fdc", board.floppy());
        m.put("nic", board.nic());
        if (board.clint() != null)
            m.put("clint", board.clint());
        m.put("pic", board.pic());
        if (m.size() != board.devices().size())
            throw new IllegalStateException("a board device has no saved-state tag");
        return m;
    }

    // ---- power ----

    /** Cold boot: RAM and VRAM cleared, clock to 0, RTC offset cleared, devices and CPU reset. */
    public void powerOn() {
        Arrays.fill(board.ram(), (byte) 0);
        Arrays.fill(board.vga().vram(), (byte) 0);   // the one array all three displays share
        clock.reset();
        board.rtc().powerOn();
        resetHardware();
        state = MachineState.RUNNING;
        exitCode = 0;
        cycleRemainder = 0;
        windowEnd = 0;
        Arrays.fill(ranCycles, 0);
        Arrays.fill(wantedCycles, 0);
        lastBusyCycles = 0;
        lastTickCycles = 0;
    }

    public void powerOff() {
        state = MachineState.OFF;
    }

    /** The reset button: devices and CPU reset, RAM kept, clock keeps running. Ignored while off. */
    public void reset() {
        if (state != MachineState.RUNNING && state != MachineState.HALTED)
            return;
        resetHardware();
        state = MachineState.RUNNING;
    }

    /** Devices reset, the ROM overlay back on where the platform uses it (spec 1.5), CPU reset. */
    private void resetHardware() {
        for (BusDevice d : devices)
            d.reset();
        board.memory().setRomOverlay(platform.romOverlayAtReset());
        cpu.reset();
        platform.wirePins(board, cpu);
        waitingForPins = false;
        deviceResetPending = false;
    }

    /**
     * The CPU's RESET instruction (68000, 68040): recorded and performed between runs. The CPU
     * advanced the clock first, so it stops right after the instruction.
     */
    private void requestDeviceReset() {
        deviceResetPending = true;
        clock.requestStopAt(clock.now());
    }

    // ---- running ----

    /** Runs {@code nanos} of emulated time, spending at most about {@code hostBudgetNanos} of host time. */
    public void runTick(long nanos, long hostBudgetNanos) {
        if (nanos < 0 || hostBudgetNanos < 0)
            throw new IllegalArgumentException("negative tick or budget");
        if (state != MachineState.RUNNING && state != MachineState.HALTED) {
            lastBusyCycles = 0;
            lastTickCycles = 0;
            return;
        }
        long deadline = System.nanoTime() + hostBudgetNanos;
        long busy = 0;
        long wanted = cyclesFor(nanos);
        // The CPU may stop up to one instruction past a window's end. Starting the next window
        // where the last one was meant to end keeps long runs exact; when the budget dropped
        // time (the clock is behind), the window starts from the clock instead.
        long startCycle = Math.min(clock.now(), windowEnd);
        long windowEnd = startCycle + wanted;
        this.windowEnd = windowEnd;
        catchUp();

        while (clock.now() < windowEnd) {
            if (state == MachineState.HALTED) {
                // the rest of the window is idle time: devices still advance
                clock.advanceTo(windowEnd);
                catchUp();
                break;
            }
            long segmentEnd = windowEnd;
            for (BusDevice d : devices)
                segmentEnd = Math.min(segmentEnd, d.nextEventCycle());
            segmentEnd = Math.max(segmentEnd, clock.now() + 1);

            if (waitingForPins) {
                if (!platform.anyPinAsserted(board)) {
                    clock.advanceTo(segmentEnd);
                    catchUp();
                    continue;
                }
                waitingForPins = false;
            }

            long before = clock.now();
            Cpu.Exit exit = cpu.run(segmentEnd, deadline);
            busy += clock.now() - before;
            boolean machineChanged = performRequests();
            if (state == MachineState.OFF_BY_GUEST) {
                catchUp();
                break;
            }
            boolean outOfTime = false;
            switch (exit) {
                case WAITING -> {
                    if (!machineChanged)
                        clock.advanceTo(Math.min(segmentEnd, clock.horizon()));
                }
                case DEADLINE -> outOfTime = true;
                case HALTED -> state = MachineState.HALTED;
                case REACHED -> outOfTime = System.nanoTime() - deadline > 0;
            }
            catchUp();
            if (outOfTime)
                break;
        }

        if (state == MachineState.RUNNING || state == MachineState.HALTED)
            for (int n = 0; n < DISPLAYS; n++)
                board.vga(n).endFrame();
        lastBusyCycles = busy;
        lastTickCycles = clock.now() - startCycle;
        ranCycles[speedSlot] = clock.now() - startCycle;
        wantedCycles[speedSlot] = wanted;
        speedSlot = (speedSlot + 1) % SPEED_TICKS;
    }

    /**
     * Cycles worth {@code nanos} at the configured clock, carrying the remainder across ticks.
     * Split at whole seconds so a long window cannot overflow the product.
     */
    private long cyclesFor(long nanos) {
        long hz = config.clockHz();
        long whole = nanos / NANOS_PER_SECOND * hz;
        long num = hz * (nanos % NANOS_PER_SECOND) + cycleRemainder;
        cycleRemainder = num % NANOS_PER_SECOND;
        return whole + num / NANOS_PER_SECOND;
    }

    private void catchUp() {
        long now = clock.now();
        for (BusDevice d : devices)
            d.advanceTo(now);
    }

    /**
     * Acts on the CPU's device reset request and on SysCon requests; true if the machine was
     * reset or powered off. A device reset resets every device and re-drives the CPU pins, but
     * leaves the CPU, RAM and the ROM overlay alone (re-enabling the overlay would hide the
     * vector table under the running program).
     */
    private boolean performRequests() {
        if (deviceResetPending) {
            deviceResetPending = false;
            for (BusDevice d : devices)
                d.reset();
            platform.wirePins(board, cpu);
        }
        SysCon sysCon = board.sysCon();
        if (!sysCon.hasRequest())
            return false;
        SysCon.PowerRequest power = sysCon.powerRequest();
        boolean waitRequested = sysCon.waitRequested();
        boolean overlayOff = sysCon.romOverlayOffRequested();
        sysCon.clearRequests();
        switch (power) {
            case OFF -> {
                state = MachineState.OFF_BY_GUEST;
                exitCode = sysCon.exitCode();
                return true;
            }
            case RESET -> {
                resetHardware();
                state = MachineState.RUNNING;
                return true;
            }
            default -> { }
        }
        if (overlayOff && platform.romOverlayAtReset())
            board.memory().setRomOverlay(false);
        if (waitRequested)
            waitingForPins = true;
        return false;
    }

    // ---- saved state (spec 1.5) ----

    /**
     * The machine's complete state as bytes, ready for an NBT byte array. Layout, all big-endian
     * through {@link DataOutputStream}:
     * <ul>
     * <li>"FLPS", {@link #STATE_VERSION};</li>
     * <li>the configuration it must load into: CPU model name, clock Hz, RAM bytes, CRC-32 of the
     * boot ROM image, reset PC, strict FPU, MAC address;</li>
     * <li>machine fields: state, exit code, SysCon WAIT parking, tick remainder, window end, the
     * speed history; the clock's now and horizon; the ROM overlay; the pending device reset;</li>
     * <li>RAM by {@link StateIo#writePages} (non-zero 4 KiB pages, deflated);</li>
     * <li>the CPU, then every device, each a section: tag, byte length, then what its
     * {@code saveState} wrote (its own format version first);</li>
     * <li>CRC-32 of everything before it.</li>
     * </ul>
     * The disk and floppy images are not included; the HDD and floppy sections only record which
     * disk is attached (capacity and id).
     */
    public byte[] saveState() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(STATE_MAGIC);
            out.writeInt(STATE_VERSION);
            out.writeUTF(config.cpu().name());
            out.writeInt(config.clockHz());
            out.writeInt(config.ramBytes());
            out.writeInt(romCrc());
            out.writeInt(resetPc());
            out.writeBoolean(config.strictFpu());
            out.writeLong(config.macAddress());
            StateIo.writeEnum(out, state);
            out.writeInt(exitCode);
            out.writeBoolean(waitingForPins);
            out.writeLong(cycleRemainder);
            out.writeLong(windowEnd);
            out.writeInt(speedSlot);
            for (int i = 0; i < SPEED_TICKS; i++) {
                out.writeLong(ranCycles[i]);
                out.writeLong(wantedCycles[i]);
            }
            out.writeLong(clock.now());
            out.writeLong(clock.horizon());
            out.writeBoolean(board.memory().romOverlay());
            out.writeBoolean(deviceResetPending);
            StateIo.writePages(out, board.ram());
            writeSection(out, config.cpu().name(), cpu::saveState);
            out.writeInt(stateDevices.size());
            for (Map.Entry<String, BusDevice> e : stateDevices.entrySet())
                writeSection(out, e.getKey(), e.getValue()::saveState);
            out.flush();
            CRC32 crc = new CRC32();
            crc.update(bytes.toByteArray());
            out.writeInt((int) crc.getValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("saving machine state", e);
        }
    }

    /**
     * Replaces this machine's state with {@code data} from {@link #saveState()} and resumes where
     * it stopped, as if no time had passed. The machine must be built from the same config (CPU
     * model, clock, RAM size, boot ROM, reset PC, strict FPU, MAC) with the same disk attached
     * (presence, capacity, id). A different floppy is not an error: it loads as a media change.
     *
     * Throws StateFormatException, leaving the machine exactly as it was, for a mismatch with
     * this machine, an unknown version (envelope or any component), or corrupt or truncated
     * data. The game then cold-boots the PC ({@link #powerOn()}). Never leaves a half-loaded
     * machine: the current state is kept as a backup and put back on any failure.
     */
    public void loadState(byte[] data) throws StateFormatException {
        Objects.requireNonNull(data, "data");
        byte[] backup = saveState();
        try {
            readState(data);
        } catch (IOException | RuntimeException e) {
            try {
                readState(backup);
            } catch (IOException | RuntimeException again) {
                again.addSuppressed(e);
                throw new IllegalStateException("could not restore the machine after a failed load", again);
            }
            if (e instanceof StateFormatException sfe)
                throw sfe;
            throw new StateFormatException("saved machine state is corrupt: " + e, e);
        }
    }

    private void readState(byte[] data) throws IOException {
        StateIo.check(data.length >= 8, "saved machine state is only " + data.length + " bytes");
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        int stored = (data[data.length - 4] & 0xFF) << 24 | (data[data.length - 3] & 0xFF) << 16
                | (data[data.length - 2] & 0xFF) << 8 | data[data.length - 1] & 0xFF;
        StateIo.check((int) crc.getValue() == stored, "saved machine state is corrupt (checksum mismatch)");
        ByteArrayInputStream body = new ByteArrayInputStream(data, 0, data.length - 4);
        DataInputStream in = new DataInputStream(body);
        StateIo.check(in.readInt() == STATE_MAGIC, "not a saved machine state");
        int version = StateIo.readVersion(in, "machine", STATE_VERSION);
        StateIo.check(version >= 4, "machine state version " + version + " was saved before the in-game board; cold boot");
        String model = in.readUTF();
        StateIo.check(model.equals(config.cpu().name()), "saved state is for a " + model + " PC, this one is " + config.cpu().name());
        int hz = in.readInt();
        StateIo.check(hz == config.clockHz(), "saved CPU clock is " + hz + " Hz, this one runs at " + config.clockHz() + " Hz");
        int ram = in.readInt();
        StateIo.check(ram == config.ramBytes(), "saved RAM is " + ram + " bytes, this PC has " + config.ramBytes());
        StateIo.check(in.readInt() == romCrc(), "saved state was made with a different boot ROM");
        int pc = in.readInt();
        StateIo.check(pc == resetPc(), String.format("saved reset PC 0x%08x differs from 0x%08x", pc, resetPc()));
        boolean strict = in.readBoolean();
        StateIo.check(strict == config.strictFpu(), "saved state was made with the strict FPU "
                + (strict ? "on" : "off") + ", this PC has it " + (config.strictFpu() ? "on" : "off"));
        long mac = in.readLong();
        StateIo.check(mac == config.macAddress(), "saved MAC address " + Long.toHexString(mac) + " differs from "
                + Long.toHexString(config.macAddress()));

        MachineState st = StateIo.readEnum(in, MachineState.class);
        int code = in.readInt();
        boolean parked = in.readBoolean();
        long remainder = in.readLong();
        StateIo.check(remainder >= 0 && remainder < NANOS_PER_SECOND, "tick remainder " + remainder + " is out of range");
        long end = in.readLong();
        int slot = in.readInt();
        StateIo.check(slot >= 0 && slot < SPEED_TICKS, "speed slot " + slot + " is out of range");
        long[] ran = new long[SPEED_TICKS], wanted = new long[SPEED_TICKS];
        for (int i = 0; i < SPEED_TICKS; i++) {
            ran[i] = in.readLong();
            wanted[i] = in.readLong();
        }
        long now = in.readLong();
        long horizon = in.readLong();
        StateIo.check(now >= 0, "clock " + now + " is negative");
        boolean overlay = in.readBoolean();
        boolean deviceReset = in.readBoolean();

        // from here on the machine changes; loadState puts the backup back on any failure
        state = st;
        exitCode = code;
        waitingForPins = parked;
        cycleRemainder = remainder;
        windowEnd = end;
        speedSlot = slot;
        System.arraycopy(ran, 0, ranCycles, 0, SPEED_TICKS);
        System.arraycopy(wanted, 0, wantedCycles, 0, SPEED_TICKS);
        clock.reset();
        clock.advanceTo(now);
        clock.setHorizon(horizon);
        board.memory().setRomOverlay(overlay);
        deviceResetPending = deviceReset;
        StateIo.readPages(in, board.ram(), "RAM");

        readSection(in, config.cpu().name(), cpu::loadState);
        int n = StateIo.readCount(in, 64, "device count");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String tag = in.readUTF();
            BusDevice d = stateDevices.get(tag);
            StateIo.check(d != null, "saved state has a device \"" + tag + "\" this PC does not have");
            StateIo.check(seen.add(tag), "saved state has device \"" + tag + "\" twice");
            readPayload(in, tag, d::loadState);
        }
        for (String tag : stateDevices.keySet())
            StateIo.check(seen.contains(tag), "saved state has no device \"" + tag + "\"");
        StateIo.check(body.available() == 0, "saved machine state has " + body.available() + " bytes left over");
    }

    /**
     * A copy of {@code state} (from {@link #saveState()}) with the disk id {@code from} replaced
     * by {@code to}, the checksum made again. A PC snapshot placed in the world gets fresh disk
     * ids (its disks are copies), and the HDD section checks the id on load (spec 12.19).
     *
     * The id sits in the HDD or floppy section as a present flag and the two halves; it is
     * found by those 17 bytes. RAM is deflated in the state, so a stray match is as likely as
     * guessing a random UUID. Throws IllegalArgumentException unless it occurs exactly once.
     */
    public static byte[] withDiskId(byte[] state, java.util.UUID from, java.util.UUID to) {
        Objects.requireNonNull(state, "state");
        byte[] old = idBytes(from), now = idBytes(to);
        int at = -1;
        for (int i = 0; i + old.length <= state.length - 4; i++) {
            if (state[i] != old[0] || !Arrays.equals(state, i, i + old.length, old, 0, old.length))
                continue;
            if (at >= 0)
                throw new IllegalArgumentException("disk id " + from + " occurs more than once in the state");
            at = i;
        }
        if (at < 0)
            throw new IllegalArgumentException("disk id " + from + " is not in the state");
        byte[] copy = state.clone();
        System.arraycopy(now, 0, copy, at, now.length);
        CRC32 crc = new CRC32();
        crc.update(copy, 0, copy.length - 4);
        int value = (int) crc.getValue();
        for (int i = 0; i < 4; i++)
            copy[copy.length - 4 + i] = (byte) (value >>> (24 - 8 * i));
        return copy;
    }

    private static byte[] idBytes(java.util.UUID id) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(17);
        b.put((byte) 1).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return b.array();
    }

    /** What a component writes or reads in its section. */
    @FunctionalInterface
    private interface StateWriter {
        void write(DataOutput out) throws IOException;
    }

    @FunctionalInterface
    private interface StateReader {
        void read(DataInput in) throws IOException;
    }

    private static void writeSection(DataOutputStream out, String tag, StateWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream section = new DataOutputStream(bytes);
        writer.write(section);
        section.flush();
        out.writeUTF(tag);
        out.writeInt(bytes.size());
        bytes.writeTo(out);
    }

    /** Reads the section tag and checks it, then the payload. */
    private static void readSection(DataInputStream in, String tag, StateReader reader) throws IOException {
        String found = in.readUTF();
        StateIo.check(found.equals(tag), "saved state has section \"" + found + "\" where \"" + tag + "\" belongs");
        readPayload(in, tag, reader);
    }

    /** Reads a section's payload, the tag already read; the component must use exactly all of it. */
    private static void readPayload(DataInputStream in, String tag, StateReader reader) throws IOException {
        int length = StateIo.readCount(in, in.available(), "length of section \"" + tag + "\"");
        byte[] payload = new byte[length];
        in.readFully(payload);
        ByteArrayInputStream bytes = new ByteArrayInputStream(payload);
        reader.read(new DataInputStream(bytes));
        StateIo.check(bytes.available() == 0, "section \"" + tag + "\" has " + bytes.available() + " bytes left over");
    }

    private int romCrc() {
        CRC32 crc = new CRC32();
        crc.update(config.bootRom());
        return (int) crc.getValue();
    }

    private int resetPc() {
        return config.resetPc() != null ? config.resetPc() : platform.defaultResetPc();
    }

    // ---- state ----

    public MachineState state() {
        return state;
    }

    /** Valid in OFF_BY_GUEST: 0 for POWER 0x5555, the code for 0x3333 | code << 16. */
    public int exitCode() {
        return exitCode;
    }

    /** Emulated cycles run over cycles requested, over the last 20 ticks; 1.0 = full speed. */
    public double speed() {
        long ran = 0, wanted = 0;
        for (int i = 0; i < SPEED_TICKS; i++) {
            ran += ranCycles[i];
            wanted += wantedCycles[i];
        }
        return wanted == 0 ? 1.0 : (double) ran / wanted;
    }

    /** Whether the machine is parked by SysCon WAIT until an interrupt pin rises. */
    public boolean waitingForInterrupt() {
        return waitingForPins;
    }

    public MachineConfig config() {
        return config;
    }

    public Cpu cpu() {
        return cpu;
    }

    public Board board() {
        return board;
    }

    public SystemBus bus() {
        return board.memory();
    }

    public CycleClock clock() {
        return clock;
    }

    /** 0 = COM1, 1 = COM2, 2 = COM3. */
    public Uart16550 com(int index) {
        return board.com(index);
    }

    /** {@code keyboard(0)}. */
    public Ps2Keyboard keyboard() {
        return board.ps2().keyboard(0);
    }

    /** {@code mouse(1)}. */
    public Ps2Mouse mouse() {
        return board.ps2().mouse(1);
    }

    /** Display 0. */
    public VgaDisplay vga() {
        return board.vga(0);
    }

    /** Display 0..2. */
    public VgaDisplay vga(int index) {
        return board.vga(index);
    }

    public Ps2Controller ps2() {
        return board.ps2();
    }

    /** The keyboard object of PS/2 port 0 or 1; it receives keys only while plugged in there. */
    public Ps2Keyboard keyboard(int port) {
        return board.ps2().keyboard(port);
    }

    /** The mouse object of PS/2 port 0 or 1; it receives input only while plugged in there. */
    public Ps2Mouse mouse(int port) {
        return board.ps2().mouse(port);
    }

    public FloppyController floppy() {
        return board.floppy();
    }

    public Nic nic() {
        return board.nic();
    }

    /**
     * Over the last tick, the share of emulated cycles the CPU spent running (not waiting in WFI,
     * STOP or SysCon WAIT, not halted), 0..1; 0 while off. The game's power draw follows it.
     */
    public double busyFraction() {
        return lastTickCycles <= 0 ? 0 : Math.min(1.0, (double) lastBusyCycles / lastTickCycles);
    }

    /** SysCon LEDS bit 0, the guest's error light. */
    public boolean guestErrorLed() {
        return board.sysCon().errorLed();
    }

    /** The CPU's halt reason while the machine is HALTED, else null (for the case screen). */
    public String haltReason() {
        return state == MachineState.HALTED ? cpu.haltReason() : null;
    }

    public HddController hdd() {
        return board.hdd();
    }

    public SysCon sysCon() {
        return board.sysCon();
    }

    /** SysCon DEBUG_OUT text so far. */
    public String debugLog() {
        return board.sysCon().debugLog();
    }

    /** Copies {@code bytes} into RAM at bus address {@code address}; IllegalArgumentException if they do not fit. */
    public void loadRam(int address, byte[] bytes) {
        SystemBus bus = board.memory();
        long off = Integer.toUnsignedLong(address) - Integer.toUnsignedLong(bus.ramBase());
        if (off < 0 || off + bytes.length > bus.ramSize())
            throw new IllegalArgumentException(String.format("%d bytes at 0x%08x do not fit in RAM", bytes.length, address));
        System.arraycopy(bytes, 0, board.ram(), (int) off, bytes.length);
    }
}
