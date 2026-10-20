package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * The system controller (spec 3.1): machine facts the guest reads, the power and reset register,
 * a debug output byte stream, and the WAIT and ROM_OVERLAY_OFF requests.
 *
 * Requests that change the machine are only recorded here; each one calls
 * {@code clock.requestStopAt(clock.now())} so the CPU stops after the writing instruction, and
 * the machine reads and clears them between runs.
 */
public final class SysCon implements BusDevice {

    /** Register offsets (spec 3.1). */
    public static final int POWER = 0x00, RAM_BASE = 0x04, RAM_SIZE = 0x08, CPU_HZ = 0x0C,
            MACHINE_ID = 0x10, DEBUG_OUT = 0x14, VRAM_BASE = 0x18, VRAM_SIZE = 0x1C, PRESENT = 0x20,
            ROM_OVERLAY_OFF = 0x24, WAIT = 0x28, VRAM1_BASE = 0x2C, VRAM2_BASE = 0x30, LEDS = 0x34;
    /** Registers decoded. */
    public static final int SIZE = 0x38;

    /** ASCII "FLPC" read little-endian (spec 3.1). */
    public static final int MACHINE_ID_VALUE = 0x43504C46;

    /** POWER values, low 16 bits; the same encoding as QEMU's test finisher (spec 3.1). */
    public static final int POWER_OFF = 0x5555, POWER_FAIL = 0x3333, POWER_RESET = 0x7777;

    /**
     * PRESENT bits (spec 3.1, 11.1.1). Bits 1 and 2 mean "a device is plugged into PS/2 port 0"
     * and "port 1" (keyboard and mouse by default).
     */
    public static final int PRESENT_HDD = 1, PRESENT_KEYBOARD = 2, PRESENT_MOUSE = 4,
            PRESENT_COM1 = 8, PRESENT_COM2 = 16, PRESENT_FLOPPY = 32, PRESENT_COM3 = 64,
            PRESENT_NIC_LINK = 128;
    /** LEDS bit 0: the guest's error light (spec 11.1.1). */
    public static final int LED_ERROR = 1;

    /** Debug log characters kept; older text is dropped first. */
    public static final int DEBUG_LOG_LIMIT = 64 * 1024;

    /** 2 added LEDS (spec 11.1.1). */
    private static final int STATE_VERSION = 2;

    /** A recorded POWER request. */
    public enum PowerRequest { NONE, OFF, RESET }

    private final CycleClock clock;
    private final int ramBase;
    private final int ramSize;
    private final int cpuHz;
    private final int vramBase;
    private final int vram1Base;
    private final int vram2Base;
    private final int vramSize;
    private IntSupplier present = () -> 0;
    private int leds;

    private int powerLatch;
    private PowerRequest powerRequest = PowerRequest.NONE;
    private int exitCode;
    private boolean romOverlayOffRequested;
    private boolean waitRequested;
    private final StringBuilder debugLog = new StringBuilder();

    /** One display: VRAM1_BASE and VRAM2_BASE read 0. */
    public SysCon(CycleClock clock, int ramBase, int ramSize, int cpuHz, int vramBase, int vramSize) {
        this(clock, ramBase, ramSize, cpuHz, vramBase, 0, 0, vramSize);
    }

    /** Three displays (spec 11.1.3): their framebuffer bases and the size of each. */
    public SysCon(CycleClock clock, int ramBase, int ramSize, int cpuHz, int vramBase, int vram1Base,
                  int vram2Base, int vramSize) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ramBase = ramBase;
        this.ramSize = ramSize;
        this.cpuHz = cpuHz;
        this.vramBase = vramBase;
        this.vram1Base = vram1Base;
        this.vram2Base = vram2Base;
        this.vramSize = vramSize;
    }

    /** Where PRESENT reads from; the platform wires it to the HDD, PS/2 and COM state. */
    public void setPresentSource(IntSupplier present) {
        this.present = Objects.requireNonNull(present, "present");
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        int v = switch (offset & ~3) {
            case RAM_BASE -> ramBase;
            case RAM_SIZE -> ramSize;
            case CPU_HZ -> cpuHz;
            case MACHINE_ID -> MACHINE_ID_VALUE;
            case VRAM_BASE -> vramBase;
            case VRAM_SIZE -> vramSize;
            case PRESENT -> present.getAsInt() & 0xFF;
            case VRAM1_BASE -> vram1Base;
            case VRAM2_BASE -> vram2Base;
            case LEDS -> leds;
            default -> 0;
        };
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        switch (offset & ~3) {
            case POWER -> {
                powerLatch = Regs.merge(powerLatch, offset, width, value);
                if (Regs.touchesByte3(offset, width))
                    power(powerLatch);
            }
            case DEBUG_OUT -> {
                if (Regs.touchesByte0(offset))
                    appendDebug(value & 0xFF);
            }
            case ROM_OVERLAY_OFF -> {
                romOverlayOffRequested = true;
                clock.requestStopAt(clock.now());
            }
            case WAIT -> {
                waitRequested = true;
                clock.requestStopAt(clock.now());
            }
            case LEDS -> leds = Regs.merge(leds, offset, width, value) & LED_ERROR;
            default -> { }
        }
    }

    private void power(int value) {
        switch (value & 0xFFFF) {
            case POWER_OFF -> {
                powerRequest = PowerRequest.OFF;
                exitCode = 0;
            }
            case POWER_FAIL -> {
                powerRequest = PowerRequest.OFF;
                exitCode = value >>> 16;
            }
            case POWER_RESET -> powerRequest = PowerRequest.RESET;
            default -> {
                return;
            }
        }
        clock.requestStopAt(clock.now());
    }

    private void appendDebug(int b) {
        if (debugLog.length() >= DEBUG_LOG_LIMIT)
            debugLog.delete(0, DEBUG_LOG_LIMIT / 2);
        debugLog.append((char) b);
    }

    /** Requests, the POWER latch and LEDS cleared; the debug log is host-side and survives. */
    @Override
    public void reset() {
        powerLatch = 0;
        leds = 0;
        clearRequests();
    }

    /** LEDS bit 0, the guest's error light (spec 11.1.1). */
    public boolean errorLed() {
        return (leds & LED_ERROR) != 0;
    }

    // ---- saved state (spec 1.5) ----

    /** The POWER latch, the recorded requests and exit code, LEDS, and the debug log (Latin-1 bytes). */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeInt(powerLatch);
        StateIo.writeEnum(out, powerRequest);
        out.writeInt(exitCode);
        out.writeBoolean(romOverlayOffRequested);
        out.writeBoolean(waitRequested);
        out.writeInt(leds);
        out.writeInt(debugLog.length());
        for (int i = 0; i < debugLog.length(); i++)
            out.writeByte(debugLog.charAt(i));
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        int version = StateIo.readVersion(in, "system controller", STATE_VERSION);
        powerLatch = in.readInt();
        powerRequest = StateIo.readEnum(in, PowerRequest.class);
        exitCode = in.readInt();
        romOverlayOffRequested = in.readBoolean();
        waitRequested = in.readBoolean();
        leds = version >= 2 ? in.readInt() & LED_ERROR : 0;
        int n = StateIo.readCount(in, DEBUG_LOG_LIMIT, "debug log length");
        debugLog.setLength(0);
        for (int i = 0; i < n; i++)
            debugLog.append((char) in.readUnsignedByte());
    }

    // ---- machine side ----

    public PowerRequest powerRequest() {
        return powerRequest;
    }

    /** The guest's exit code of the last power-off request (0 for 0x5555). */
    public int exitCode() {
        return exitCode;
    }

    public boolean romOverlayOffRequested() {
        return romOverlayOffRequested;
    }

    public boolean waitRequested() {
        return waitRequested;
    }

    public boolean hasRequest() {
        return powerRequest != PowerRequest.NONE || romOverlayOffRequested || waitRequested;
    }

    /** Called by the machine once it has acted on the requests. */
    public void clearRequests() {
        powerRequest = PowerRequest.NONE;
        romOverlayOffRequested = false;
        waitRequested = false;
    }

    /** DEBUG_OUT bytes so far, as Latin-1 characters. */
    public String debugLog() {
        return debugLog.toString();
    }

    public void clearDebugLog() {
        debugLog.setLength(0);
    }
}
