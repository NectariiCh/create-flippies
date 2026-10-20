package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * The PS/2 controller (spec 3.6, 11.1.4): two identical ports at 0x00 and 0x10, each with DATA,
 * STATUS and CTRL. DATA is a one-byte register: reading it pops the next byte from the device,
 * writing it sends a command byte. Each port drives its own interrupt line while a byte is ready
 * and its IRQ is enabled.
 *
 * Either port takes either device: each port holds a keyboard and a mouse object and a selected
 * kind, and only the selected device can be plugged in. A new controller has port 0 on KEYBOARD
 * and port 1 on MOUSE, both unplugged until the host plugs them in with {@link #setDevice}.
 */
public final class Ps2Controller implements BusDevice {

    /** Port bases and register offsets inside a port (spec 3.6). */
    public static final int KEYBOARD_PORT = 0x00, MOUSE_PORT = 0x10, DATA = 0x0, STATUS = 0x4,
            CTRL = 0x8;
    public static final int SIZE = 0x20;
    public static final int PORTS = 2;

    /** STATUS bits; bits 15..8 hold the queued byte count. */
    public static final int STATUS_RX_READY = 1, STATUS_OVERRUN = 2, STATUS_PRESENT = 4;
    /** CTRL bits. */
    public static final int CTRL_IRQ_ENABLE = 1, CTRL_PORT_ENABLE = 2;

    /** 2 added the selected kind per port and both devices of each port (spec 11.1.4). */
    private static final int STATE_VERSION = 2;

    private final CycleClock clock;
    private final int clockHz;
    private final Ps2Keyboard[] keyboards = new Ps2Keyboard[PORTS];
    private final Ps2Mouse[] mice = new Ps2Mouse[PORTS];
    /** The selected kind per port, KEYBOARD or MOUSE; whether it is plugged in is the device's presence. */
    private final Ps2DeviceKind[] kinds = { Ps2DeviceKind.KEYBOARD, Ps2DeviceKind.MOUSE };
    private final IrqSink[] irqs;
    private final int[] ctrl = new int[PORTS];
    private final boolean[] levels = new boolean[PORTS];

    public Ps2Controller(CycleClock clock, int clockHz, IrqSink port0Irq, IrqSink port1Irq) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
        this.irqs = new IrqSink[] { Objects.requireNonNull(port0Irq), Objects.requireNonNull(port1Irq) };
        for (int p = 0; p < PORTS; p++) {
            keyboards[p] = unplugged(new Ps2Keyboard(clock, clockHz));
            mice[p] = unplugged(new Ps2Mouse(clock, clockHz));
            keyboards[p].setChangeListener(this::updateIrqs);
            mice[p].setChangeListener(this::updateIrqs);
        }
    }

    /** A device in its power-on state with nothing plugged in, before the controller listens to it. */
    private static <D extends Ps2Device> D unplugged(D d) {
        d.setPresent(false);
        d.reset();
        return d;
    }

    // ---- host side ----

    /**
     * Plugs a device of {@code kind} into {@code port}, or unplugs with NONE. Changing the kind
     * unplugs the old device (its queue empties) and plugs in the new one, which queues its
     * power-on bytes; plugging into an empty port does the same. The same kind again is a no-op.
     */
    public void setDevice(int port, Ps2DeviceKind kind) {
        checkPort(port);
        Objects.requireNonNull(kind, "kind");
        Ps2Device old = selected(port);
        if (kind == Ps2DeviceKind.NONE) {
            old.setPresent(false);
            return;
        }
        if (kind == kinds[port] && old.present())
            return;
        old.setPresent(false);
        kinds[port] = kind;
        Ps2Device d = selected(port);
        d.setPresent(true);
        d.setPortEnabled((ctrl[port] & CTRL_PORT_ENABLE) != 0);
        d.reset();
        updateIrqs();
    }

    /** What is plugged into {@code port}; NONE when nothing is. */
    public Ps2DeviceKind device(int port) {
        checkPort(port);
        return selected(port).present() ? kinds[port] : Ps2DeviceKind.NONE;
    }

    /** The keyboard object of {@code port}, whether or not it is the one plugged in. */
    public Ps2Keyboard keyboard(int port) {
        checkPort(port);
        return keyboards[port];
    }

    /** The mouse object of {@code port}, whether or not it is the one plugged in. */
    public Ps2Mouse mouse(int port) {
        checkPort(port);
        return mice[port];
    }

    /** {@code keyboard(0)}. */
    public Ps2Keyboard keyboard() {
        return keyboards[0];
    }

    /** {@code mouse(1)}. */
    public Ps2Mouse mouse() {
        return mice[1];
    }

    /** CTRL of port 0 or 1. */
    public int ctrl(int port) {
        return ctrl[port];
    }

    private static void checkPort(int port) {
        if (port < 0 || port >= PORTS)
            throw new IllegalArgumentException("no PS/2 port " + port);
    }

    private Ps2Device selected(int port) {
        return kinds[port] == Ps2DeviceKind.MOUSE ? mice[port] : keyboards[port];
    }

    @Override
    public int size() {
        return SIZE;
    }

    private int status(int port) {
        Ps2Device d = selected(port);
        int n = d.queued();
        return (n > 0 ? STATUS_RX_READY : 0) | (d.overrun() ? STATUS_OVERRUN : 0)
                | (d.present() ? STATUS_PRESENT : 0) | Math.min(n, 255) << 8;
    }

    @Override
    public int read(int offset, int width) {
        advanceTo(clock.now());
        if (offset >= SIZE)
            return 0;
        int port = offset >>> 4;
        int v;
        switch (offset & 0xC) {
            case DATA -> v = Regs.touchesByte0(offset) ? selected(port).pop() : 0;
            case STATUS -> v = Regs.read(status(port), offset, width);
            case CTRL -> v = Regs.read(ctrl[port], offset, width);
            default -> v = 0;
        }
        updateIrqs();
        return v;
    }

    @Override
    public void write(int offset, int width, int value) {
        advanceTo(clock.now());
        if (offset >= SIZE)
            return;
        int port = offset >>> 4;
        Ps2Device d = selected(port);
        switch (offset & 0xC) {
            case DATA -> {
                if (Regs.touchesByte0(offset) && d.present())
                    d.command(value & 0xFF);
            }
            case STATUS -> {
                if ((Regs.written(offset, width, value) & STATUS_OVERRUN) != 0)
                    d.setOverrun(false);
            }
            case CTRL -> {
                ctrl[port] = Regs.merge(ctrl[port], offset, width, value) & 3;
                boolean enabled = (ctrl[port] & CTRL_PORT_ENABLE) != 0;
                keyboards[port].setPortEnabled(enabled);
                mice[port].setPortEnabled(enabled);
            }
            default -> { }
        }
        updateIrqs();
        long next = nextEventCycle();
        if (next != Long.MAX_VALUE)
            clock.requestStopAt(next);
    }

    /** CTRL cleared and every device back to power-on; what is plugged in stays plugged in. */
    @Override
    public void reset() {
        for (int p = 0; p < PORTS; p++) {
            ctrl[p] = 0;
            keyboards[p].setPortEnabled(false);
            mice[p].setPortEnabled(false);
            keyboards[p].reset();
            mice[p].reset();
        }
        updateIrqs();
    }

    @Override
    public void advanceTo(long cycle) {
        for (int p = 0; p < PORTS; p++)
            selected(p).advanceTo(cycle);
        updateIrqs();
    }

    @Override
    public long nextEventCycle() {
        long next = Long.MAX_VALUE;
        for (int p = 0; p < PORTS; p++)
            if ((ctrl[p] & CTRL_IRQ_ENABLE) != 0)
                next = Math.min(next, selected(p).nextEventCycle());
        return next;
    }

    // ---- saved state (spec 1.5) ----

    /** Per port: CTRL, line level and the selected kind, then that port's keyboard and mouse state. */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        for (int p = 0; p < PORTS; p++) {
            out.writeInt(ctrl[p]);
            out.writeBoolean(levels[p]);
            StateIo.writeEnum(out, kinds[p]);
            keyboards[p].saveState(out);
            mice[p].saveState(out);
        }
    }

    /**
     * Version 1 (one keyboard on port 0, one mouse on port 1) loads as port 0 KEYBOARD and port 1
     * MOUSE, with the port 0 mouse and the port 1 keyboard unplugged in their power-on state.
     */
    @Override
    public void loadState(DataInput in) throws IOException {
        int version = StateIo.readVersion(in, "PS/2 controller", STATE_VERSION);
        if (version == 1) {
            for (int p = 0; p < PORTS; p++) {
                ctrl[p] = in.readInt() & 3;
                levels[p] = in.readBoolean();
            }
            kinds[0] = Ps2DeviceKind.KEYBOARD;
            kinds[1] = Ps2DeviceKind.MOUSE;
            keyboards[0].loadState(in);
            mice[1].loadState(in);
            copyState(unplugged(new Ps2Keyboard(clock, clockHz)), keyboards[1]);
            copyState(unplugged(new Ps2Mouse(clock, clockHz)), mice[0]);
            keyboards[1].setPortEnabled((ctrl[1] & CTRL_PORT_ENABLE) != 0);
            mice[0].setPortEnabled((ctrl[0] & CTRL_PORT_ENABLE) != 0);
            return;
        }
        for (int p = 0; p < PORTS; p++) {
            ctrl[p] = in.readInt() & 3;
            levels[p] = in.readBoolean();
            Ps2DeviceKind kind = StateIo.readEnum(in, Ps2DeviceKind.class);
            StateIo.check(kind != Ps2DeviceKind.NONE, "PS/2 port " + p + " has no selected kind");
            kinds[p] = kind;
            keyboards[p].loadState(in);
            mice[p].loadState(in);
        }
    }

    /** Copies one device's state into another through its saved form; no listener runs. */
    private static void copyState(Ps2Device from, Ps2Device to) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        from.saveState(new DataOutputStream(bytes));
        to.loadState(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    private void updateIrqs() {
        for (int p = 0; p < PORTS; p++) {
            boolean level = (ctrl[p] & CTRL_IRQ_ENABLE) != 0 && selected(p).queued() > 0;
            if (level != levels[p]) {
                levels[p] = level;
                irqs[p].set(level);
            }
        }
    }
}
