package com.nectariich.create_flippies.content.computer.device;

import com.nectariich.create_flippies.content.computer.bus.BusDevice;
import com.nectariich.create_flippies.content.computer.bus.CycleClock;
import com.nectariich.create_flippies.content.computer.bus.IrqSink;
import com.nectariich.create_flippies.content.computer.bus.StateIo;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * A COM port: the register-level subset of a 16550 UART in spec 3.5. Registers are 8 bits at
 * offsets 0..7; a wider access reads the register zero-extended and writes its low byte.
 *
 * Timing is lazy and drift-free. The transmitter moves one byte per character time from the TX
 * queue to the host output; byte n of a stream completes at {@code start + charCycles(n)}, where
 * {@code start} is the cycle the stream last started from idle (or the line settings last
 * changed) and {@code charCycles} is computed exactly from the character length, divisor and CPU
 * clock. Host input reaches the RX FIFO at the same pace. Nothing is ever added up from rounded
 * per-byte durations.
 *
 * The TX queue holds the byte in the shift register (the head) plus the bytes waiting in the
 * FIFO or holding register. THR is empty (LSR.THRE) when nothing is waiting; the transmitter is
 * empty (LSR.TEMT) when the queue is empty. The link is perfect: no parity, framing or break
 * errors; the only receive error is overrun.
 */
public final class Uart16550 implements BusDevice {

    /** Register offsets (spec 3.5). RBR/THR/DLL share 0, IER/DLM share 1, IIR/FCR share 2. */
    public static final int RBR = 0, THR = 0, DLL = 0, IER = 1, DLM = 1, IIR = 2, FCR = 2, LCR = 3,
            MCR = 4, LSR = 5, MSR = 6, SCR = 7;
    public static final int SIZE = 8;

    /** The UART's input clock; baud = 115200 / divisor (spec 3.5). */
    public static final int INPUT_CLOCK_HZ = 1_843_200;
    /** FIFO depth each way when FIFOs are enabled. */
    public static final int FIFO_DEPTH = 16;
    /** Bytes the host may queue towards the guest (spec 3.5). */
    public static final int HOST_INPUT_CAPACITY = 4096;
    /** Bytes of guest output kept for the host; the oldest are dropped on overflow (spec 3.5). */
    public static final int HOST_OUTPUT_CAPACITY = 64 * 1024;

    /** IER bits. */
    public static final int IER_RDA = 1, IER_THRE = 2, IER_RLS = 4, IER_MS = 8;
    /** IIR identifications, bit 0 clear (interrupt pending). */
    public static final int IIR_NONE = 0x01, IIR_RLS = 0x06, IIR_RDA = 0x04, IIR_TIMEOUT = 0x0C,
            IIR_THRE = 0x02, IIR_MODEM = 0x00, IIR_FIFO_BITS = 0xC0;
    /** LSR bits. */
    public static final int LSR_DR = 0x01, LSR_OE = 0x02, LSR_THRE = 0x20, LSR_TEMT = 0x40;
    /** MCR bits. */
    public static final int MCR_DTR = 0x01, MCR_RTS = 0x02, MCR_OUT1 = 0x04, MCR_OUT2 = 0x08,
            MCR_LOOP = 0x10;
    /** MSR bits. */
    public static final int MSR_DCTS = 0x01, MSR_DDSR = 0x02, MSR_TERI = 0x04, MSR_DDCD = 0x08,
            MSR_CTS = 0x10, MSR_DSR = 0x20, MSR_RI = 0x40, MSR_DCD = 0x80;

    private static final int[] TRIGGER_LEVELS = { 1, 4, 8, 14 };
    /** Twice the input clock: character lengths are counted in half bits (1.5 stop bits). */
    private static final long HALF_BIT_DIVISOR = 2L * INPUT_CLOCK_HZ;
    private static final int STATE_VERSION = 1;

    private final CycleClock clock;
    private final int clockHz;
    private final IrqSink irq;

    // registers
    private int ier;
    private int lcr;
    private int mcr;
    private int scr;
    private int divisorLatch = 1;
    private boolean fifoEnabled;
    private int rxTrigger = 1;
    private boolean overrun;
    private int msrInputs;
    private int msrDeltas;
    /** The THR-empty interrupt condition, cleared by an IIR read reporting it or a THR write. */
    private boolean threPending;

    // receive FIFO
    private final byte[] rx = new byte[FIFO_DEPTH];
    private int rxHead;
    private int rxCount;
    /** Cycle the RX FIFO was last pushed or popped, for the character timeout. */
    private long rxActivity;

    // transmit queue: head in the shift register, then up to FIFO_DEPTH waiting
    private final byte[] tx = new byte[FIFO_DEPTH + 1];
    private int txHead;
    private int txCount;
    private long txStart;
    private long txDone;

    // host input on its way in
    private final byte[] in = new byte[HOST_INPUT_CAPACITY];
    private int inHead;
    private int inCount;
    private long rxStart;
    private long rxDone;

    // guest output for the host
    private final byte[] out = new byte[HOST_OUTPUT_CAPACITY];
    private int outHead;
    private int outCount;
    private long outDropped;

    private boolean hostConnected;
    private long lastCycle;
    private boolean irqLevel;

    public Uart16550(CycleClock clock, int clockHz, IrqSink irq) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockHz = clockHz;
        this.irq = Objects.requireNonNull(irq, "irq");
        reset();
    }

    // ---- timing ----

    private int divisor() {
        return divisorLatch == 0 ? 1 : divisorLatch;
    }

    /** Half bits per character: start + data + parity + stop (1, or 1.5 / 2 with LCR bit 2). */
    private int charHalfBits() {
        int data = 5 + (lcr & 3);
        int parity = (lcr & 0x08) != 0 ? 1 : 0;
        int stopHalves = (lcr & 0x04) == 0 ? 2 : data == 5 ? 3 : 4;
        return 2 * (1 + data + parity) + stopHalves;
    }

    /** Cycles taken by {@code n} characters, floor(n * bits * divisor * 16 * clockHz / 1_843_200), overflow-safe. */
    long charCycles(long n) {
        long a = n * charHalfBits() * divisor() * 16L;
        return a / HALF_BIT_DIVISOR * clockHz + a % HALF_BIT_DIVISOR * clockHz / HALF_BIT_DIVISOR;
    }

    private boolean loopback() {
        return (mcr & MCR_LOOP) != 0;
    }

    private int rxCapacity() {
        return fifoEnabled ? FIFO_DEPTH : 1;
    }

    private int waitingCapacity() {
        return fifoEnabled ? FIFO_DEPTH : 1;
    }

    private int txWaiting() {
        return txCount == 0 ? 0 : txCount - 1;
    }

    @Override
    public void advanceTo(long cycle) {
        if (cycle < lastCycle)
            cycle = lastCycle;
        // transmitter
        while (txCount > 0) {
            long done = txStart + charCycles(txDone + 1);
            if (done > cycle)
                break;
            byte b = tx[txHead];
            txHead = (txHead + 1) % tx.length;
            txCount--;
            txDone++;
            if (loopback())
                receive(b, done);
            else
                emit(b);
            if (txCount > 0 && txWaiting() == 0)
                threPending = true;
        }
        // receiver, from the host
        if (!loopback()) {
            while (inCount > 0) {
                long at = rxStart + charCycles(rxDone + 1);
                if (at > cycle)
                    break;
                byte b = in[inHead];
                inHead = (inHead + 1) % in.length;
                inCount--;
                rxDone++;
                receive(b, at);
            }
        }
        lastCycle = cycle;
        updateIrq();
    }

    private void receive(byte b, long at) {
        if (rxCount == rxCapacity()) {
            overrun = true;
            return;
        }
        rx[(rxHead + rxCount) % rx.length] = b;
        rxCount++;
        rxActivity = at;
    }

    private void emit(byte b) {
        if (outCount == out.length) {
            outHead = (outHead + 1) % out.length;
            outCount--;
            outDropped++;
        }
        out[(outHead + outCount) % out.length] = b;
        outCount++;
    }

    /** Restarts both streams from now, after the character time changed. */
    private void restartStreams() {
        long now = clock.now();
        if (txCount > 0) {
            txStart = now;
            txDone = 0;
        }
        rxStart = now;
        rxDone = 0;
    }

    private boolean timedOut(long cycle) {
        return fifoEnabled && rxCount > 0 && cycle >= rxActivity + charCycles(4);
    }

    @Override
    public long nextEventCycle() {
        long next = Long.MAX_VALUE;
        if (txCount > 0 && ((ier & IER_THRE) != 0 || (loopback() && (ier & (IER_RDA | IER_RLS)) != 0)))
            next = txStart + charCycles(txDone + 1);
        if (!loopback() && inCount > 0 && (ier & (IER_RDA | IER_RLS)) != 0)
            next = Math.min(next, rxStart + charCycles(rxDone + 1));
        if (fifoEnabled && rxCount > 0 && (ier & IER_RDA) != 0) {
            long t = rxActivity + charCycles(4);
            if (t > lastCycle)
                next = Math.min(next, t);
        }
        return next;
    }

    // ---- interrupts ----

    /** IIR as a read would return it now, without the read's side effect. */
    int iirValue() {
        int fifoBits = fifoEnabled ? IIR_FIFO_BITS : 0;
        int trigger = fifoEnabled ? rxTrigger : 1;
        if ((ier & IER_RLS) != 0 && overrun)
            return IIR_RLS | fifoBits;
        if ((ier & IER_RDA) != 0 && rxCount >= trigger)
            return IIR_RDA | fifoBits;
        if ((ier & IER_RDA) != 0 && timedOut(lastCycle))
            return IIR_TIMEOUT | fifoBits;
        if ((ier & IER_THRE) != 0 && threPending)
            return IIR_THRE | fifoBits;
        if ((ier & IER_MS) != 0 && msrDeltas != 0)
            return IIR_MODEM | fifoBits;
        return IIR_NONE | fifoBits;
    }

    private void updateIrq() {
        boolean level = (iirValue() & 1) == 0;
        if (level != irqLevel) {
            irqLevel = level;
            irq.set(level);
        }
    }

    // ---- registers ----

    @Override
    public int size() {
        return SIZE;
    }

    @Override
    public int read(int offset, int width) {
        advanceTo(clock.now());
        boolean dlab = (lcr & 0x80) != 0;
        int v;
        switch (offset) {
            case RBR -> v = dlab ? divisorLatch & 0xFF : popRx();
            case IER -> v = dlab ? divisorLatch >>> 8 : ier;
            case IIR -> {
                v = iirValue();
                if ((v & 0x0F) == IIR_THRE)
                    threPending = false;
            }
            case LCR -> v = lcr;
            case MCR -> v = mcr;
            case LSR -> {
                v = (rxCount > 0 ? LSR_DR : 0) | (overrun ? LSR_OE : 0)
                        | (txWaiting() == 0 ? LSR_THRE : 0) | (txCount == 0 ? LSR_TEMT : 0);
                overrun = false;
            }
            case MSR -> {
                v = msrInputs | msrDeltas;
                msrDeltas = 0;
            }
            case SCR -> v = scr;
            default -> v = 0;
        }
        updateIrq();
        return v;
    }

    private int popRx() {
        rxActivity = clock.now();
        if (rxCount == 0)
            return 0;
        int b = rx[rxHead] & 0xFF;
        rxHead = (rxHead + 1) % rx.length;
        rxCount--;
        return b;
    }

    @Override
    public void write(int offset, int width, int value) {
        advanceTo(clock.now());
        int b = value & 0xFF;
        boolean dlab = (lcr & 0x80) != 0;
        switch (offset) {
            case THR -> {
                if (dlab)
                    setDivisor((divisorLatch & 0xFF00) | b);
                else
                    writeThr((byte) b);
            }
            case IER -> {
                if (dlab) {
                    setDivisor((divisorLatch & 0xFF) | b << 8);
                } else {
                    int old = ier;
                    ier = b & 0x0F;
                    if ((old & IER_THRE) == 0 && (ier & IER_THRE) != 0 && txWaiting() == 0)
                        threPending = true;
                }
            }
            case FCR -> writeFcr(b);
            case LCR -> {
                int oldBits = charHalfBits();
                lcr = b;
                if (charHalfBits() != oldBits)
                    restartStreams();
            }
            case MCR -> {
                boolean wasLoop = loopback();
                mcr = b & 0x1F;
                if (wasLoop != loopback()) {
                    rxStart = clock.now();
                    rxDone = 0;
                }
                updateModemInputs();
            }
            case SCR -> scr = b;
            default -> { }
        }
        updateIrq();
        long next = nextEventCycle();
        if (next != Long.MAX_VALUE)
            clock.requestStopAt(next);
    }

    private void setDivisor(int value) {
        if (value != divisorLatch) {
            divisorLatch = value;
            restartStreams();
        }
    }

    private void writeThr(byte b) {
        threPending = false;
        if (txWaiting() >= waitingCapacity())
            return;
        tx[(txHead + txCount) % tx.length] = b;
        txCount++;
        if (txCount == 1) {
            // idle transmitter: the byte goes straight to the shift register, THR is empty again
            txStart = clock.now();
            txDone = 0;
            threPending = true;
        }
    }

    private void writeFcr(int b) {
        boolean enable = (b & 1) != 0;
        boolean clearRx = (b & 2) != 0 || enable != fifoEnabled;
        boolean clearTx = (b & 4) != 0 || enable != fifoEnabled;
        fifoEnabled = enable;
        rxTrigger = TRIGGER_LEVELS[(b >>> 6) & 3];
        if (clearRx) {
            rxHead = 0;
            rxCount = 0;
        }
        if (clearTx && txWaiting() > 0) {
            txCount = 1;
            threPending = true;
        }
    }

    private void updateModemInputs() {
        int next;
        if (loopback()) {
            next = ((mcr & MCR_RTS) != 0 ? MSR_CTS : 0) | ((mcr & MCR_DTR) != 0 ? MSR_DSR : 0)
                    | ((mcr & MCR_OUT1) != 0 ? MSR_RI : 0) | ((mcr & MCR_OUT2) != 0 ? MSR_DCD : 0);
        } else {
            next = hostConnected ? MSR_CTS | MSR_DSR | MSR_DCD : 0;
        }
        int changed = next ^ msrInputs;
        if ((changed & MSR_CTS) != 0)
            msrDeltas |= MSR_DCTS;
        if ((changed & MSR_DSR) != 0)
            msrDeltas |= MSR_DDSR;
        if ((msrInputs & MSR_RI) != 0 && (next & MSR_RI) == 0)
            msrDeltas |= MSR_TERI;
        if ((changed & MSR_DCD) != 0)
            msrDeltas |= MSR_DDCD;
        msrInputs = next;
    }

    /** Registers and FIFOs to reset values; the host buffers and connection survive. */
    @Override
    public void reset() {
        ier = 0;
        lcr = 0;
        mcr = 0;
        scr = 0;
        divisorLatch = 1;
        fifoEnabled = false;
        rxTrigger = 1;
        overrun = false;
        threPending = false;
        rxHead = rxCount = 0;
        txHead = txCount = 0;
        txStart = txDone = 0;
        long now = clock.now();
        lastCycle = now;
        rxActivity = now;
        rxStart = now;
        rxDone = 0;
        msrInputs = hostConnected ? MSR_CTS | MSR_DSR | MSR_DCD : 0;
        msrDeltas = 0;
        updateIrq();
    }

    // ---- saved state (spec 1.5) ----

    /**
     * Every register, the RX FIFO, the TX queue with the byte in the shift register and its stream
     * stamps, host input still on its way in, guest output not yet read by the host, the host
     * connection and the line level. Ring buffers are stored oldest byte first.
     */
    @Override
    public void saveState(DataOutput out) throws IOException {
        out.writeInt(STATE_VERSION);
        out.writeInt(ier);
        out.writeInt(lcr);
        out.writeInt(mcr);
        out.writeInt(scr);
        out.writeInt(divisorLatch);
        out.writeBoolean(fifoEnabled);
        out.writeInt(rxTrigger);
        out.writeBoolean(overrun);
        out.writeInt(msrInputs);
        out.writeInt(msrDeltas);
        out.writeBoolean(threPending);
        StateIo.writeRing(out, rx, rxHead, rxCount);
        out.writeLong(rxActivity);
        StateIo.writeRing(out, tx, txHead, txCount);
        out.writeLong(txStart);
        out.writeLong(txDone);
        StateIo.writeRing(out, in, inHead, inCount);
        out.writeLong(rxStart);
        out.writeLong(rxDone);
        StateIo.writeRing(out, this.out, outHead, outCount);
        out.writeLong(outDropped);
        out.writeBoolean(hostConnected);
        out.writeLong(lastCycle);
        out.writeBoolean(irqLevel);
    }

    @Override
    public void loadState(DataInput in) throws IOException {
        StateIo.readVersion(in, "UART", STATE_VERSION);
        ier = in.readInt() & 0x0F;
        lcr = in.readInt() & 0xFF;
        mcr = in.readInt() & 0x1F;
        scr = in.readInt() & 0xFF;
        divisorLatch = in.readInt() & 0xFFFF;
        fifoEnabled = in.readBoolean();
        rxTrigger = in.readInt();
        StateIo.check(rxTrigger == 1 || rxTrigger == 4 || rxTrigger == 8 || rxTrigger == 14,
                "UART RX trigger level " + rxTrigger + " is not 1, 4, 8 or 14");
        overrun = in.readBoolean();
        msrInputs = in.readInt() & 0xF0;
        msrDeltas = in.readInt() & 0x0F;
        threPending = in.readBoolean();
        rxHead = 0;
        rxCount = StateIo.readRing(in, rx, "UART RX FIFO");
        rxActivity = in.readLong();
        txHead = 0;
        txCount = StateIo.readRing(in, tx, "UART TX queue");
        txStart = in.readLong();
        txDone = in.readLong();
        inHead = 0;
        inCount = StateIo.readRing(in, this.in, "UART host input");
        rxStart = in.readLong();
        rxDone = in.readLong();
        outHead = 0;
        outCount = StateIo.readRing(in, out, "UART host output");
        outDropped = in.readLong();
        hostConnected = in.readBoolean();
        lastCycle = in.readLong();
        irqLevel = in.readBoolean();
    }

    // ---- host side ----

    /** Queues bytes from the host towards the guest; returns how many fitted. */
    public int hostWrite(byte[] src, int off, int len) {
        Objects.checkFromIndexSize(off, len, src.length);
        advanceTo(clock.now());
        int n = Math.min(len, in.length - inCount);
        if (n > 0 && inCount == 0) {
            rxStart = clock.now();
            rxDone = 0;
        }
        for (int i = 0; i < n; i++)
            in[(inHead + inCount + i) % in.length] = src[off + i];
        inCount += n;
        return n;
    }

    /** Drains up to {@code len} transmitted bytes; returns how many were copied. */
    public int hostRead(byte[] dst, int off, int len) {
        Objects.checkFromIndexSize(off, len, dst.length);
        advanceTo(clock.now());
        int n = Math.min(len, outCount);
        for (int i = 0; i < n; i++)
            dst[off + i] = out[(outHead + i) % out.length];
        outHead = (outHead + n) % out.length;
        outCount -= n;
        return n;
    }

    /** Transmitted bytes waiting for {@link #hostRead}. */
    public int hostAvailable() {
        advanceTo(clock.now());
        return outCount;
    }

    /** Output bytes dropped because the host did not read them in time. */
    public long hostDropped() {
        return outDropped;
    }

    /** Host input bytes not yet received by the UART. */
    public int hostPending() {
        return inCount;
    }

    public void setHostConnected(boolean connected) {
        advanceTo(clock.now());
        hostConnected = connected;
        updateModemInputs();
        updateIrq();
    }

    public boolean hostConnected() {
        return hostConnected;
    }
}
