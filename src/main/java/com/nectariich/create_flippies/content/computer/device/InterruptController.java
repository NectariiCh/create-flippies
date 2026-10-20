package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * The interrupt controller (spec 3.2): 32 level-sensitive input lines (line 0 unused) routed to
 * 8 outputs. Output k is high while any line routed to k is both pending and enabled. CLAIM has
 * no side effect: lines are levels, so a handler clears the source in the device.
 *
 * Devices drive lines through {@link #line(int)}; outputs drive CPU pins through the sinks the
 * platform installs with {@link #setOutput}. Line levels are inputs, so {@link #reset()} keeps
 * them (each device lowers its own line when it resets).
 */
public final class InterruptController implements BusDevice {

    /** Register offsets (spec 3.2). */
    public static final int PENDING = 0x00, ENABLE = 0x04, CLAIM = 0x08, VECTOR_BASE = 0x0C,
            ROUTE0 = 0x10, CLAIM_OUT0 = 0x20;
    /** Registers decoded: through CLAIM_OUT7. */
    public static final int SIZE = 0x40;

    public static final int LINES = 32;
    public static final int OUTPUTS = 8;
    /** VECTOR_BASE reset value (spec 3.2). */
    public static final int VECTOR_BASE_RESET = 0x08;

    /** Line 0 is unused: never pending, never enabled. */
    private static final int LINE_MASK = ~1;
    /** ROUTE registers: 3 bits per line, the fourth reserved. */
    private static final int ROUTE_MASK = 0x7777_7777;
    private static final int STATE_VERSION = 1;

    private int levels;
    private int enable;
    private int vectorBase = VECTOR_BASE_RESET;
    private final int[] route = new int[4];
    /** Per output, the lines routed to it; recomputed on ROUTE writes. */
    private final int[] routedTo = new int[OUTPUTS];
    private final IrqSink[] outputs = new IrqSink[OUTPUTS];
    private int outputLevels;

    public InterruptController() {
        Arrays.fill(outputs, IrqSink.NONE);
        recomputeRoutes();
    }

    /** The sink a device drives line {@code n} (1..31) with. */
    public IrqSink line(int n) {
        if (n < 1 || n >= LINES)
            throw new IllegalArgumentException("interrupt line must be 1..31: " + n);
        return level -> setLine(n, level);
    }

    public void setLine(int n, boolean level) {
        if (n < 1 || n >= LINES)
            throw new IllegalArgumentException("interrupt line must be 1..31: " + n);
        int bit = 1 << n;
        int next = level ? levels | bit : levels & ~bit;
        if (next != levels) {
            levels = next;
            update();
        }
    }

    /** Connects output {@code k} and drives the sink with the output's current level at once. */
    public void setOutput(int k, IrqSink sink) {
        outputs[k] = sink == null ? IrqSink.NONE : sink;
        outputs[k].set(output(k));
    }

    public boolean output(int k) {
        return (outputLevels & (1 << k)) != 0;
    }

    /** Lowest line pending and enabled, any output; 0 if none. */
    public int claim() {
        return lowest(levels & enable & LINE_MASK);
    }

    /** Lowest line pending and enabled and routed to output {@code k}; 0 if none. */
    public int claim(int k) {
        return lowest(levels & enable & routedTo[k] & LINE_MASK);
    }

    /** The vector an 8086 or 8088 acknowledge cycle reads: VECTOR_BASE + the claimed line. */
    public int acknowledgeVector() {
        return (vectorBase + claim()) & 0xFF;
    }

    private static int lowest(int bits) {
        return bits == 0 ? 0 : Integer.numberOfTrailingZeros(bits);
    }

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        int reg = offset & ~3;
        int v;
        if (reg >= CLAIM_OUT0 && reg < CLAIM_OUT0 + 4 * OUTPUTS) {
            v = claim((reg - CLAIM_OUT0) >>> 2);
        } else if (reg >= ROUTE0 && reg < ROUTE0 + 16) {
            v = route[(reg - ROUTE0) >>> 2];
        } else {
            v = switch (reg) {
                case PENDING -> levels & LINE_MASK;
                case ENABLE -> enable;
                case CLAIM -> claim();
                case VECTOR_BASE -> vectorBase;
                default -> 0;
            };
        }
        return Regs.read(v, offset, width);
    }

    @Override
    public void write(int offset, int width, int value) {
        int reg = offset & ~3;
        if (reg == ENABLE) {
            enable = Regs.merge(enable, offset, width, value) & LINE_MASK;
            update();
        } else if (reg == VECTOR_BASE) {
            vectorBase = Regs.merge(vectorBase, offset, width, value) & 0xFF;
        } else if (reg >= ROUTE0 && reg < ROUTE0 + 16) {
            int i = (reg - ROUTE0) >>> 2;
            route[i] = Regs.merge(route[i], offset, width, value) & ROUTE_MASK;
            recomputeRoutes();
            update();
        }
    }

    /** ENABLE, VECTOR_BASE and ROUTE back to reset; line levels are inputs and stay. */
    @Override
    public void reset() {
        enable = 0;
        vectorBase = VECTOR_BASE_RESET;
        Arrays.fill(route, 0);
        recomputeRoutes();
        update();
    }

    /** Line levels, ENABLE, VECTOR_BASE, ROUTE and the output levels the CPU pins were last driven with. */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeInt(levels);
        out.writeInt(enable);
        out.writeInt(vectorBase);
        for (int r : route)
            out.writeInt(r);
        out.writeInt(outputLevels);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "interrupt controller", STATE_VERSION);
        levels = in.readInt() & LINE_MASK;
        enable = in.readInt() & LINE_MASK;
        vectorBase = in.readInt() & 0xFF;
        for (int i = 0; i < route.length; i++)
            route[i] = in.readInt() & ROUTE_MASK;
        outputLevels = in.readInt() & ((1 << OUTPUTS) - 1);
        recomputeRoutes();
    }

    private void recomputeRoutes() {
        Arrays.fill(routedTo, 0);
        for (int n = 0; n < LINES; n++) {
            int out = (route[n >>> 3] >>> (4 * (n & 7))) & 7;
            routedTo[out] |= 1 << n;
        }
    }

    private void update() {
        int active = levels & enable & LINE_MASK;
        int next = 0;
        for (int k = 0; k < OUTPUTS; k++)
            if ((active & routedTo[k]) != 0)
                next |= 1 << k;
        int changed = next ^ outputLevels;
        outputLevels = next;
        for (int k = 0; changed != 0; k++, changed >>>= 1)
            if ((changed & 1) != 0)
                outputs[k].set((next & (1 << k)) != 0);
    }
}
